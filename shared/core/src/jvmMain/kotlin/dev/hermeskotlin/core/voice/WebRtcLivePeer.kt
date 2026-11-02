package dev.hermeskotlin.core.voice

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpReceiver
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCRtpTransceiverInit
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.MediaStream
import dev.onvoid.webrtc.media.MediaType
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioLayer
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Native signaling only; real media stays disabled until the full-duplex integration. */
internal class WebRtcLivePeer(private val events: LivePeerEvents) : LivePeer {
    private val owner = Any()
    private val retired = AtomicBoolean()
    private val initialized = CompletableFuture<Unit>()
    private val released = CompletableFuture<Throwable?>()
    private val inCallback = ThreadLocal<Boolean>()
    private val extraChannels = ConcurrentLinkedQueue<RTCDataChannel>()
    private val outbox = EventOutbox(owner, ::transmit, { retire(it, true) }, { Dispatchers.IO.dispatch(EmptyCoroutineContext, it) })
    private val gathered = CompletableDeferred<Unit>()
    private var audio: AudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: RTCPeerConnection? = null
    private var channel: RTCDataChannel? = null
    private var transceiver: RTCRtpTransceiver? = null
    @Volatile private var cleaningThread: Thread? = null

    init {
        var failure: Throwable? = null
        try {
            audio = AudioDeviceModule(AudioLayer.kDummyAudio)
            factory = PeerConnectionFactory(audio)
            peer = factory!!.createPeerConnection(RTCConfiguration().apply { iceServers = emptyList() }, object : PeerConnectionObserver {
                override fun onIceCandidate(candidate: RTCIceCandidate) = Unit
                override fun onIceGatheringChange(state: RTCIceGatheringState) = callback {
                    if (state == RTCIceGatheringState.COMPLETE) gathered.complete(Unit)
                }
                override fun onConnectionChange(state: RTCPeerConnectionState) = callback {
                    if (state == RTCPeerConnectionState.FAILED) retire(IllegalStateException("The call disconnected."), true)
                    else if (state == RTCPeerConnectionState.CLOSED) retire(null, true)
                }
                override fun onDataChannel(extra: RTCDataChannel) = callback {
                    // This side creates oai-events. Retain unexpected owning handles until peer.close quiesces callbacks.
                    extraChannels.add(extra)
                    retire(IllegalStateException("The call opened an unexpected event channel."), true)
                }
                override fun onTrack(track: RTCRtpTransceiver) = callback { track.dispose() }
                override fun onAddTrack(receiver: RTCRtpReceiver, streams: Array<out MediaStream>) = callback { receiver.dispose() }
            }) ?: error("Couldn't create the call connection.")
            peer!!.setAudioRecording(false)
            peer!!.setAudioPlayout(false)
            transceiver = peer!!.addTransceiver(MediaType.AUDIO, RTCRtpTransceiverInit())
            val data = peer!!.createDataChannel("oai-events", RTCDataChannelInit()) ?: error("Couldn't create the call event channel.")
            channel = data
            data.registerObserver(object : RTCDataChannelObserver {
                override fun onBufferedAmountChange(previous: Long) = Unit
                override fun onStateChange() = callback {
                    if (!retired.get() && data.state == RTCDataChannelState.CLOSED) retire(null, true)
                }
                override fun onMessage(buffer: RTCDataChannelBuffer) = callback {
                    if (retired.get()) return@callback
                    if (buffer.binary || buffer.data.remaining() > LinuxLiveTransport.MAX_EVENT_BYTES) {
                        retire(IllegalStateException("The call received an unsupported event."), true)
                    } else {
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.duplicate().get(bytes)
                        if (!events.message(bytes, false)) retire(IllegalStateException("The call couldn't accept an event."), true)
                    }
                }
            })
        } catch (caught: Throwable) { failure = caught }
        finally { initialized.complete(Unit) }
        failure?.let { primary ->
            try { close() } catch (cleanup: Throwable) { if (cleanup !== primary) primary.addSuppressed(cleanup) }
            throw IllegalStateException("The call connection isn't available.", primary)
        }
    }

    override suspend fun offer(): String = withContext(Dispatchers.IO) {
        val description = operation<RTCSessionDescription> { success, failure ->
            active().createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
                override fun onSuccess(sdp: RTCSessionDescription) = callback { success(sdp) }
                override fun onFailure(ignored: String?) = callback { failure() }
            })
        }
        apply(description, true)
        // Match the existing call: use the gathered local description after a bounded best-effort wait.
        withTimeoutOrNull(10_000) { gathered.await() }
        synchronized(owner) { active().localDescription?.sdp ?: error("Couldn't make the call offer.") }
    }

    override suspend fun answer(sdp: String) = withContext(Dispatchers.IO) {
        apply(RTCSessionDescription(RTCSdpType.ANSWER, sdp), false)
    }

    private suspend fun apply(sdp: RTCSessionDescription, local: Boolean) = operation<Unit> { success, failure ->
        val observer = object : SetSessionDescriptionObserver {
            override fun onSuccess() = callback { success(Unit) }
            override fun onFailure(ignored: String?) = callback { failure() }
        }
        if (local) active().setLocalDescription(sdp, observer) else active().setRemoteDescription(sdp, observer)
    }

    private suspend fun <T> operation(start: ((T) -> Unit, () -> Unit) -> Unit): T = withTimeout(10_000) {
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean()
            continuation.invokeOnCancellation { completed.set(true) }
            try {
                synchronized(owner) {
                    active()
                    start({ value -> if (completed.compareAndSet(false, true)) continuation.resume(value) }, {
                        if (completed.compareAndSet(false, true)) continuation.resumeWithException(IllegalStateException("The call's session description was refused."))
                    })
                }
            } catch (caught: Throwable) {
                if (completed.compareAndSet(false, true)) continuation.resumeWithException(caught)
            }
        }
    }

    private fun active(): RTCPeerConnection {
        check(!retired.get()) { "The call connection is closed." }
        return checkNotNull(peer)
    }

    override fun send(bytes: ByteArray): Boolean {
        if (retired.get() || bytes.size > LinuxLiveTransport.MAX_EVENT_BYTES) return false
        // Native send/getters can wait on WebRTC threads, so a consumer resumed inline by an event callback can't
        // send here: its event keeps its place and goes out from IO.
        return outbox.send(bytes, deferred = inCallback.get() == true)
    }

    /** False while the event channel isn't open; throws when the call can't go on. Runs under [owner]. */
    private fun transmit(bytes: ByteArray): Boolean {
        val data = channel
        if (retired.get() || data == null || data.state != RTCDataChannelState.OPEN) return false
        val buffered = data.bufferedAmount
        check(buffered >= 0 && buffered <= LinuxLiveTransport.MAX_EVENT_BYTES - bytes.size) { "The call's outgoing event queue is full." }
        try { data.send(RTCDataChannelBuffer(ByteBuffer.wrap(bytes), false)) }
        catch (caught: Exception) { throw IllegalStateException("The call couldn't send an event.", caught) }
        return true
    }

    private inline fun callback(block: () -> Unit) {
        val previous = inCallback.get()
        inCallback.set(true)
        try { block() } finally { if (previous == null) inCallback.remove() else inCallback.set(previous) }
    }

    private fun retire(failure: Throwable?, notify: Boolean) {
        if (!retired.compareAndSet(false, true)) return
        try {
            Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { release() })
        } catch (caught: Throwable) { released.complete(caught) }
        if (notify) events.ended(failure)
    }

    private fun release() {
        cleaningThread = Thread.currentThread()
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (caught: Throwable) {
                val primary = failure
                if (primary == null) failure = caught else if (primary !== caught) primary.addSuppressed(caught)
            }
        }
        try {
            initialized.join()
            synchronized(owner) {
                attempt { channel?.unregisterObserver() }
                attempt { channel?.close() }
                // close waits for the signaling callbacks and removes the native observer before returning.
                attempt { peer?.close() }
                while (true) {
                    val extra = extraChannels.poll() ?: break
                    attempt { extra.close() }; attempt { extra.dispose() }
                }
                attempt { channel?.dispose() }
                attempt { transceiver?.dispose() }
                attempt { factory?.dispose() }
                attempt { audio?.dispose() }
                channel = null; peer = null; transceiver = null; factory = null; audio = null
            }
        } catch (caught: Throwable) {
            val primary = failure
                if (primary == null) failure = caught else if (primary !== caught) primary.addSuppressed(caught)
        } finally {
            released.complete(failure)
            cleaningThread = null
        }
    }

    override fun retire() { retire(null, false) }

    override fun close() {
        retire()
        // A callback may resume an unconfined consumer that closes; it must return before native shutdown can finish.
        if (inCallback.get() != true && Thread.currentThread() !== cleaningThread) {
            released.join()?.let { throw IllegalStateException("The call connection couldn't stop.", it) }
        }
    }
}
