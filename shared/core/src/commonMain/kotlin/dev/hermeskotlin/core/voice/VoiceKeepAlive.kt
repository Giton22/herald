package dev.hermeskotlin.core.voice

/**
 * Keeps a voice chat going while Herald is out of sight (the screen off, another app in front), the way a
 * phone call does. On Android, a microphone foreground service with an ongoing notification.
 */
interface VoiceKeepAlive {
    /**
     * Holds the voice chat up until [release]. [onEnd] runs when the person ends it from outside the app (the
     * notification's End). False when the platform refused, so the chat should end when the app goes away.
     */
    fun hold(onEnd: () -> Unit): Boolean

    fun release()
}
