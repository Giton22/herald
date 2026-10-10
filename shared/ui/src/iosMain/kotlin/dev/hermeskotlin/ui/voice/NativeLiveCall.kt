package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.voice.IosLiveCalls
import dev.hermeskotlin.core.voice.LiveCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * A live call's WebRTC side, which the Swift host implements with the WebRTC package (the same build as
 * Android's webrtc-android). Plain callbacks rather than suspend functions, which Swift can't implement.
 */
interface NativeLiveCall {
    /**
     * Opens the microphone and the `oai-events` channel and makes the offer: [NativeLiveCallListener.onOffer]
     * once ICE gathering is done or has taken long enough. The listener is held until the call closes.
     */
    fun start(listener: NativeLiveCallListener)

    /** Applies the vendor's answer: then [NativeLiveCallListener.onAccepted], or onFailed. */
    fun accept(answerSdp: String)

    /** Sends an event; false when the channel isn't open. */
    fun send(event: String): Boolean

    /** The voice is making sound right now; polled. */
    fun speaking(): Boolean

    /** The microphone level, 0..1; polled. */
    fun micLevel(): Float

    fun setMuted(muted: Boolean)

    /** Hangs up and gives the audio back. Safe to call more than once. */
    fun close()
}

/** What a [NativeLiveCall] reports, from any thread. */
interface NativeLiveCallListener {
    fun onOffer(sdp: String)

    fun onAccepted()

    /** Setting up failed; the call is over. */
    fun onFailed(message: String)

    /** A message from the event channel. */
    fun onEvent(event: String)

    /** The call dropped or was hung up. */
    fun onClosed()
}

/** Called by the Swift host at launch with how to make a call. Without it, voice chats use the chained mode. */
fun setLiveCallMaker(make: () -> NativeLiveCall) {
    IosLiveCalls.make = { IosLiveCall(make()) }
}

/** [LiveCall] over the host's [NativeLiveCall]. */
internal class IosLiveCall(private val native: NativeLiveCall) : LiveCall {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    override val events: ReceiveChannel<String> = incoming
    private val offer = CompletableDeferred<String>()
    private val accepted = CompletableDeferred<Unit>()

    private val listener = object : NativeLiveCallListener {
        override fun onOffer(sdp: String) {
            offer.complete(sdp)
        }

        override fun onAccepted() {
            accepted.complete(Unit)
        }

        override fun onFailed(message: String) {
            val failure = IllegalStateException(message)
            offer.completeExceptionally(failure)
            accepted.completeExceptionally(failure)
            incoming.close()
        }

        override fun onEvent(event: String) {
            incoming.trySend(event)
        }

        override fun onClosed() {
            offer.completeExceptionally(IllegalStateException("The call ended."))
            accepted.completeExceptionally(IllegalStateException("The call ended."))
            incoming.close()
        }
    }

    override suspend fun connect(answer: suspend (offerSdp: String) -> String) {
        native.start(listener)
        val remote = answer(offer.await())
        native.accept(remote)
        accepted.await()
    }

    override fun send(event: String): Boolean = native.send(event)

    override fun speaking(): Boolean = native.speaking()

    override fun micLevel(): Float = native.micLevel()

    override fun setMuted(muted: Boolean) = native.setMuted(muted)

    override fun close() {
        native.close()
        incoming.close()
    }
}
