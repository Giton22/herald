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

    override fun hold(onEnd: () -> Unit, onLost: () -> Unit): Boolean {
        release()
        holding = true
        VoiceAudioSession.holdChat()
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
            VoiceAudioSession.releaseChat()
        }
    }
}
