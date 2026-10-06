package dev.hermeskotlin.core.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A GPT-Live call over WebRTC: the phone's microphone with WebRTC's echo cancelling (the voice comes out of the
 * speaker, so without it the model hears itself), the voice played as a call, and the `oai-events`
 * data channel. Desktop's VoiceLiveSession, on Android.
 */
class AndroidLiveCall(context: Context) : LiveCall {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val appContext = context.applicationContext
    private val incoming = Channel<String>(Channel.UNLIMITED)
    override val events: ReceiveChannel<String> = incoming

    private var adm: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var channel: DataChannel? = null
    private var audioTrack: org.webrtc.AudioTrack? = null
    private var audioSource: org.webrtc.AudioSource? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var routed = false

    @Volatile private var closed = false
    @Volatile private var micLevel = 0f
    @Volatile private var loudAt = 0L
    @Volatile private var statsPending = false

    override suspend fun connect(answer: suspend (offerSdp: String) -> String) {
        initialize(appContext)
        routeAudio()
        // WebRTC's own echo canceller rather than the phone's: with the voice on the speaker, many phones'
        // cancellers go half-duplex and all but mute the microphone while it talks, so you can't cut in.
        val adm = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setSamplesReadyCallback { samples -> micLevel = level(samples.data) }
            .createAudioDeviceModule()
        this.adm = adm
        val factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
        this.factory = factory

        val gathered = CompletableDeferred<Unit>()
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
        ) ?: error("Couldn't set up the call.")
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
        val channel = peer.createDataChannel("oai-events", DataChannel.Init()) ?: error("Couldn't open the call's event channel.")
        this.channel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()).also(buffer.data::get)
                incoming.trySend(bytes.decodeToString())
            }

            override fun onStateChange() {
                if (channel.state() == DataChannel.State.CLOSED) incoming.close()
            }

            override fun onBufferedAmountChange(previous: Long) = Unit
        })

        val offer = peer.createSdp { observer -> peer.createOffer(observer, MediaConstraints()) }
        peer.applySdp { observer -> peer.setLocalDescription(observer, offer) }
        // The vendor answers with whatever candidates are in the offer, so a slow gathering only waits so long.
        withTimeoutOrNull(ICE_GATHER_MS) { gathered.await() }
        val local = peer.localDescription?.description ?: error("Couldn't make the call offer.")
        val remote = answer(local)
        peer.applySdp { observer -> peer.setRemoteDescription(observer, SessionDescription(SessionDescription.Type.ANSWER, remote)) }
    }

    override fun send(event: String): Boolean {
        val channel = channel ?: return false
        if (closed || channel.state() != DataChannel.State.OPEN) return false
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(event.encodeToByteArray()), false))
    }

    /** The voice's output level from the call's stats, with a short hangover so pauses between words count. */
    override fun speaking(): Boolean {
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

    override fun micLevel(): Float = micLevel

    override fun setMuted(muted: Boolean) {
        audioTrack?.setEnabled(!muted)
        if (muted) micLevel = 0f
    }

    override fun close() {
        if (closed) return
        closed = true
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
        unrouteAudio()
    }

    /** A call goes to the speaker (or the headset when one is in), with the phone's call processing. */
    @Suppress("DEPRECATION")
    private fun routeAudio() {
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        routed = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val headset = devices.firstOrNull { it.type in HEADSETS }
            (headset ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER })?.let(audioManager::setCommunicationDevice)
        } else {
            audioManager.isSpeakerphoneOn = !audioManager.isWiredHeadsetOn && !audioManager.isBluetoothScoOn
        }
    }

    @Suppress("DEPRECATION")
    private fun unrouteAudio() {
        if (!routed) return
        routed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
        else audioManager.isSpeakerphoneOn = false
        audioManager.mode = previousMode
    }

    private companion object {
        const val ICE_GATHER_MS = 10_000L
        const val SPEAKING_LEVEL = 0.02
        const val SPEAKING_HANGOVER_MS = 400L
        val HEADSETS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

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

/** Creates an SDP and waits for it. */
private suspend fun PeerConnection.createSdp(start: (SdpObserver) -> Unit): SessionDescription = suspendCoroutine { continuation ->
    start(object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = continuation.resume(description)
        override fun onCreateFailure(error: String?) = continuation.resumeWithException(IllegalStateException(error ?: "Couldn't make the call offer."))
        override fun onSetSuccess() = Unit
        override fun onSetFailure(error: String?) = Unit
    })
}

/** Applies an SDP and waits for it. */
private suspend fun PeerConnection.applySdp(start: (SdpObserver) -> Unit): Unit = suspendCoroutine { continuation ->
    start(object : SdpObserver {
        override fun onSetSuccess() = continuation.resume(Unit)
        override fun onSetFailure(error: String?) = continuation.resumeWithException(IllegalStateException(error ?: "The call was refused."))
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onCreateFailure(error: String?) = Unit
    })
}
