package dev.hermeskotlin.core.voice

import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionReasonAppWasSuspended
import platform.AVFAudio.AVAudioSessionInterruptionReasonKey
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.darwin.NSObjectProtocol

/**
 * Keeps a voice chat going with Herald out of sight, as the microphone service does on Android. iOS needs no
 * service for it: with the `audio` background mode an app keeps running while it plays or records, and
 * [VoiceAudioSession.holdChat] keeps audio going for the whole chat, also while the agent thinks. What ends
 * the chat from outside is the system taking the audio away: a phone call, or the media services resetting.
 */
class IosVoiceKeepAlive : VoiceKeepAlive {
    private var observers: List<NSObjectProtocol> = emptyList()
    private var holding = false

    /**
     * The system's call screen (CallKit), which the Swift host provides: the chat shows on the Lock Screen and
     * in the status bar like a call, and can be ended there. Main thread only.
     */
    interface SystemCall {
        /**
         * Shows the call; CallKit then activates the audio session ([onActivated]). [onEnd] runs when it's
         * ended from outside Herald, [onUnavailable] when there's no call screen after all (refused, or no
         * activation in time).
         */
        fun start(onEnd: () -> Unit, onActivated: () -> Unit, onUnavailable: () -> Unit)

        fun end()
    }

    override fun hold(onEnd: () -> Unit, onLost: () -> Unit): Boolean {
        release()
        holding = true
        val call = systemCall
        VoiceAudioSession.holdChat(activate = call == null)
        call?.start(
            onEnd,
            onActivated = { if (holding) VoiceAudioSession.callKitActivated() },
            onUnavailable = { if (holding) VoiceAudioSession.activateChat() },
        )
        val center = NSNotificationCenter.defaultCenter
        val main = NSOperationQueue.mainQueue
        observers = listOf(
            center.addObserverForName(AVAudioSessionInterruptionNotification, null, main) { note ->
                val info = note?.userInfo
                val type = (info?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue
                val reason = (info?.get(AVAudioSessionInterruptionReasonKey) as? NSNumber)?.unsignedLongValue
                // Told late that the app had been suspended: nothing took the audio, the chat carries on.
                if (type == AVAudioSessionInterruptionTypeBegan && reason != AVAudioSessionInterruptionReasonAppWasSuspended) onEnd()
            },
            center.addObserverForName(AVAudioSessionMediaServicesWereResetNotification, null, main) { _ -> onEnd() },
        )
        return true
    }

    override fun release() {
        observers.forEach(NSNotificationCenter.defaultCenter::removeObserver)
        observers = emptyList()
        if (holding) {
            holding = false
            systemCall?.end()
            VoiceAudioSession.releaseChat()
        }
    }

    companion object {
        /** Set by the UI from the Swift host at launch; null where there's no call screen. */
        var systemCall: SystemCall? = null
    }
}
