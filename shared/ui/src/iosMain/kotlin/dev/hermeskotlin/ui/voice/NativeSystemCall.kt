package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.voice.IosVoiceKeepAlive

/**
 * The system's call screen for a voice chat, which the Swift host implements with CallKit: the chat shows on
 * the Lock Screen like a call and can be ended there. Called on the main thread.
 */
interface NativeSystemCall {
    /** Shows the call; [onEnd] runs, on the main thread, when it's ended from outside Herald. */
    fun start(onEnd: () -> Unit)

    /** The chat ended in Herald: takes the call down. */
    fun end()
}

/** Called by the Swift host at launch. Without it, a voice chat carries on in the background without one. */
fun setSystemCall(call: NativeSystemCall) {
    IosVoiceKeepAlive.systemCall = object : IosVoiceKeepAlive.SystemCall {
        override fun start(onEnd: () -> Unit) = call.start(onEnd)

        override fun end() = call.end()
    }
}
