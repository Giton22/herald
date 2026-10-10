package dev.hermeskotlin.core.voice

import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.darwin.NSObjectProtocol

/**
 * Keeps a voice chat going with Herald out of sight, as the microphone service does on Android. iOS needs no
 * service for it: the `audio` background mode keeps an app running while its audio session is active, so the
 * session stays on for the whole chat, also between a reply and listening again. What ends the chat from
 * outside is the system taking the audio away: a phone call, or the media services resetting.
 */
class IosVoiceKeepAlive : VoiceKeepAlive {
    private var observers: List<NSObjectProtocol> = emptyList()
    private var holding = false

    override fun hold(onEnd: () -> Unit, onLost: () -> Unit): Boolean {
        release()
        holding = true
        VoiceAudioSession.startCall()
        val center = NSNotificationCenter.defaultCenter
        val main = NSOperationQueue.mainQueue
        observers = listOf(
            center.addObserverForName(AVAudioSessionInterruptionNotification, null, main) { note ->
                val type = (note?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue
                if (type == AVAudioSessionInterruptionTypeBegan) onEnd()
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
            VoiceAudioSession.endCall()
        }
    }
}
