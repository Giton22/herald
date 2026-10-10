package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.voice.IosVoiceKeepAlive

/**
 * The system's call screen for a voice chat, which the Swift host implements with CallKit: the chat shows on
 * the Lock Screen like a call and can be ended there. Called on the main thread.
 */
interface NativeSystemCall {
    /**
     * Shows the call, whose activation of the audio session the chat waits for. On the main thread, [onEnd] runs
     * when it's ended from outside Herald, [onActivated] when CallKit activated the audio, and [onUnavailable]
     * when there's no call screen after all (CallKit refused it, or didn't activate the audio in time): the chat
     * then activates the audio itself.
     */
    fun start(onEnd: () -> Unit, onActivated: () -> Unit, onUnavailable: () -> Unit)

    /** The chat ended in Herald: takes the call down. */
    fun end()
}

/** Called by the Swift host at launch. Without it, a voice chat carries on in the background without one. */
fun setSystemCall(call: NativeSystemCall) {
    IosVoiceKeepAlive.systemCall = object : IosVoiceKeepAlive.SystemCall {
        override fun start(onEnd: () -> Unit, onActivated: () -> Unit, onUnavailable: () -> Unit) =
            call.start(onEnd, onActivated, onUnavailable)

        override fun end() = call.end()
    }
}
