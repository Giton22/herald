package dev.hermeskotlin.wear

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A live voice call on the watch, over WebRTC straight to the voice model: the watch's microphone with WebRTC's echo
 * cancelling (the voice comes out of the watch's speaker), and the `oai-events` channel. The phone trades the
 * [offer] for the model's answer ([answer]), since only it reaches the gateway. Herald's phone call, on the wrist.
 * One call per instance.
 */
class WatchCall(context: Context) {
    private val appContext = context.applicationContext

    /** Why a call couldn't go on, in the watch's language: it shows under the orb. */
    private fun say(id: Int): String = appContext.getString(id)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val incoming = Channel<String>(Channel.UNLIMITED)

    /** Messages from the event channel. Closes when the call drops or is closed. */
    val events: ReceiveChannel<String> = incoming

    private var adm: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var channel: DataChannel? = null
    private var audioTrack: org.webrtc.AudioTrack? = null
    private var audioSource: org.webrtc.AudioSource? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var routed = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val waiting = ArrayDeque<String>()
    // Guards the native objects against close(), which frees them. No WebRTC callback may take it: close() holds it
    // while peer.close() waits on WebRTC's threads, so one blocked here would deadlock (flush() goes off-thread).
    private val lock = Any()
    private var answered = false

    @Volatile private var closed = false
    @Volatile private var micLevel = 0f
    @Volatile private var loudAt = 0L
    @Volatile private var statsPending = false

    /** Opens the microphone and makes the WebRTC offer, its candidates gathered. */
    suspend fun offer(): String {
        initialize(appContext)
        fastNetwork()
        val gathered = CompletableDeferred<Unit>()
        val peer = setUp(gathered)
        val offer = peer.createSdp(say(R.string.wear_call_offer_failed)) { observer -> whileOpen(observer::onCreateFailure) { peer.createOffer(observer, MediaConstraints()) } }
        peer.applySdp(say(R.string.wear_call_refused)) { observer -> whileOpen(observer::onSetFailure) { peer.setLocalDescription(observer, offer) } }
        // The model answers with whatever candidates are in the offer, so a slow gathering only waits so long.
        withTimeoutOrNull(ICE_GATHER_MS) { gathered.await() }
        return synchronized(lock) { peer.takeUnless { closed }?.localDescription?.description } ?: error(say(R.string.wear_call_offer_failed))
    }

    /** Runs [block] on the call's objects unless it's been closed (they're freed then), else [ended]. */
    private inline fun whileOpen(ended: (String) -> Unit, block: () -> Unit) {
        val open = synchronized(lock) { if (!closed) block(); !closed }
        if (!open) ended(say(R.string.wear_call_ended_detail))
    }

    // Under the lock, all of it: a hang-up meanwhile would free what's being set up.
    private fun setUp(gathered: CompletableDeferred<Unit>): PeerConnection = synchronized(lock) {
        if (closed) error(say(R.string.wear_call_ended_detail))
        routeAudio()
        // WebRTC's own echo canceller: a watch's speaker sits right by its microphone.
        val adm = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setSamplesReadyCallback { samples -> micLevel = level(samples.data) }
            .createAudioDeviceModule()
        this.adm = adm
        val factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
        this.factory = factory

        val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val peer = factory.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                    if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
                }

                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED) incoming.close()
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceCandidate(candidate: IceCandidate) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                override fun onAddStream(stream: MediaStream) = Unit
                override fun onRemoveStream(stream: MediaStream) = Unit
                override fun onDataChannel(channel: DataChannel) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
            },
        ) ?: error(say(R.string.wear_call_setup_failed))
        this.peer = peer

        val source = factory.createAudioSource(
            MediaConstraints().apply {
                for (name in listOf("googEchoCancellation", "googNoiseSuppression", "googAutoGainControl", "googHighpassFilter")) {
                    mandatory += MediaConstraints.KeyValuePair(name, "true")
                }
            },
        )
        audioSource = source
        val track = factory.createAudioTrack("mic", source)
        audioTrack = track
        peer.addTrack(track, listOf("mic"))

        // Before the offer, so the offer carries the channel.
        val channel = peer.createDataChannel("oai-events", DataChannel.Init()) ?: error(say(R.string.wear_call_events_failed))
        this.channel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()).also(buffer.data::get)
                incoming.trySend(bytes.decodeToString())
            }

            override fun onStateChange() {
                when (channel.state()) {
                    // Off WebRTC's thread: close() waits on that thread while holding the lock flush() takes.
                    DataChannel.State.OPEN -> thread(name = "call-flush") { flush() }
                    DataChannel.State.CLOSED -> incoming.close()
                    else -> Unit
                }
            }

            override fun onBufferedAmountChange(previous: Long) = Unit
        })
        peer
    }

    /** The model's answer, from the phone; the call then connects. The phone may send it again: only the first counts. */
    suspend fun answer(sdp: String) {
        val peer = synchronized(lock) { peer?.takeIf { !closed && !answered }?.also { answered = true } } ?: return
        peer.applySdp(say(R.string.wear_call_refused)) { observer ->
            whileOpen(observer::onSetFailure) { peer.setRemoteDescription(observer, SessionDescription(SessionDescription.Type.ANSWER, sdp)) }
        }
    }

    /** Sends an event on the call's channel; until it opens, it waits. */
    fun send(event: String) {
        synchronized(lock) {
            if (closed) return
            waiting.addLast(event)
        }
        flush()
    }

    // Under the lock: the channel is freed in native code once closed, and using it then crashes.
    private fun flush() = synchronized(lock) {
        val channel = channel ?: return
        if (closed || channel.state() != DataChannel.State.OPEN) return
        while (true) {
            val next = waiting.removeFirstOrNull() ?: return
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(next.encodeToByteArray()), false))
        }
    }

    /** The voice is making sound right now, from the call's stats, with a short hangover for pauses between words; polled. */
    fun speaking(): Boolean = synchronized(lock) {
        val peer = peer
        if (peer != null && !closed && !statsPending) {
            statsPending = true
            peer.getStats { report ->
                statsPending = false
                val level = report.statsMap.values
                    .firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "audio" }
                    ?.members?.get("audioLevel") as? Double
                if (level != null && level > SPEAKING_LEVEL) loudAt = System.currentTimeMillis()
            }
        }
        return System.currentTimeMillis() - loudAt < SPEAKING_HANGOVER_MS
    }

    /** The microphone level, 0..1; polled. */
    fun micLevel(): Float = micLevel

    fun setMuted(muted: Boolean) = synchronized(lock) {
        if (!closed) audioTrack?.setEnabled(!muted)
        if (muted) micLevel = 0f
    }

    /** Hangs up and frees the microphone, the speaker and the network. Safe to call more than once, from any thread. */
    fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        waiting.clear()
        incoming.close()
        channel?.unregisterObserver()
        channel?.close()
        peer?.close()
        channel?.dispose()
        // The peer frees the microphone track with its sender; freeing it again here crashes in native code.
        peer?.dispose()
        audioSource?.dispose()
        factory?.dispose()
        adm?.release()
        channel = null
        peer = null
        audioTrack = null
        unrouteAudio()
        releaseNetwork()
    }

    private fun releaseNetwork() {
        networkCallback?.let {
            connectivity?.bindProcessToNetwork(null)
            runCatching { connectivity?.unregisterNetworkCallback(it) }
        }
        networkCallback = null
    }

    /**
     * Wi-Fi or mobile data for the call when the watch has it: through the phone over Bluetooth, the default, a
     * call's audio may not get through or keep up. Without either within a moment, the call tries the default.
     */
    private suspend fun fastNetwork() {
        val connectivity = connectivity ?: return
        val got = CompletableDeferred<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            // Never after the call: binding then would hold the whole app on this network.
            override fun onAvailable(network: Network) = synchronized(lock) {
                // Still the call's request: one let go at the timeout may yet call back.
                if (!closed && networkCallback === this && connectivity.bindProcessToNetwork(network)) got.complete(network)
            }

            override fun onLost(network: Network) = synchronized(lock) {
                if (connectivity.boundNetworkForProcess == network) connectivity.bindProcessToNetwork(null)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        try {
            synchronized(lock) {
                if (closed) return
                connectivity.requestNetwork(request, callback)
                networkCallback = callback
            }
        } catch (_: RuntimeException) {
            return
        }
        // None in time: the request goes, so a network that turns up later doesn't take the app over mid-call.
        if (withTimeoutOrNull(FAST_NETWORK_MS) { got.await() } == null) synchronized(lock) { releaseNetwork() }
    }

    /** A call goes to the watch's speaker, or earbuds when they're in, with the system's call processing. */
    private fun routeAudio() {
        val audio = audioManager ?: return
        previousMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        routed = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audio.availableCommunicationDevices
            val headsets = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
            val headset = devices.firstOrNull { it.type in headsets }
            (headset ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER })?.let(audio::setCommunicationDevice)
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = true
        }
    }

    private fun unrouteAudio() {
        val audio = audioManager ?: return
        if (!routed) return
        routed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
        }
        audio.mode = previousMode
    }

    private companion object {
        const val ICE_GATHER_MS = 10_000L
        const val FAST_NETWORK_MS = 4_000L
        const val SPEAKING_LEVEL = 0.02
        const val SPEAKING_HANGOVER_MS = 400L

        @Volatile private var initialized = false

        fun initialize(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
                initialized = true
            }
        }

        /** The recorder's level: RMS of 16-bit PCM, scaled so 1.0 is loud speech. */
        fun level(pcm: ByteArray): Float {
            val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val count = samples.remaining()
            if (count == 0) return 0f
            var sum = 0.0
            while (samples.hasRemaining()) {
                val sample = samples.get().toDouble()
                sum += sample * sample
            }
            return min(1.0, sqrt(sum / count) / 256.0 / 42.0).toFloat()
        }
    }
}

/** Creates an SDP and waits for it; [failed] is said when WebRTC gives no reason. */
private suspend fun PeerConnection.createSdp(failed: String, start: (SdpObserver) -> Unit): SessionDescription = suspendCoroutine { continuation ->
    start(object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = continuation.resume(description)
        override fun onCreateFailure(error: String?) = continuation.resumeWithException(IllegalStateException(error ?: failed))
        override fun onSetSuccess() = Unit
        override fun onSetFailure(error: String?) = Unit
    })
}

/** Applies an SDP and waits for it; [refused] is said when WebRTC gives no reason. */
private suspend fun PeerConnection.applySdp(refused: String, start: (SdpObserver) -> Unit): Unit = suspendCoroutine { continuation ->
    start(object : SdpObserver {
        override fun onSetSuccess() = continuation.resume(Unit)
        override fun onSetFailure(error: String?) = continuation.resumeWithException(IllegalStateException(error ?: refused))
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onCreateFailure(error: String?) = Unit
    })
}
