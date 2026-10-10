package dev.hermeskotlin.core.voice

import kotlin.concurrent.AtomicInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import platform.Foundation.NSOperationQueue

/**
 * How live calls are made on iOS: WebRTC comes from the Swift host (a Swift package), which hands its maker to
 * the shared UI at launch, and the UI sets it here. Null until then: voice chats use the chained mode.
 */
object IosLiveCalls {
    var make: (() -> LiveCall?)? = null

    internal fun create(): LiveCall? = make?.invoke()?.let(::AudioSessionCall)
}

/**
 * Holds the app's audio session for [call]'s whole life, from connecting to closing, so the release a
 * recording or a reply left pending doesn't deactivate it under the call.
 */
private class AudioSessionCall(private val call: LiveCall) : LiveCall by call {
    /** 0 before connecting, 1 while held, 2 once closed. */
    private val state = AtomicInt(0)

    override suspend fun connect(answer: suspend (offerSdp: String) -> String) {
        withContext(NonCancellable + Dispatchers.Main) {
            if (state.compareAndSet(0, 1)) VoiceAudioSession.startCall()
        }
        call.connect(answer)
    }

    override fun close() {
        call.close()
        if (state.getAndSet(2) == 1) NSOperationQueue.mainQueue.addOperationWithBlock { VoiceAudioSession.endCall() }
    }
}
