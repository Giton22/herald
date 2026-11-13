package dev.hermeskotlin.core.voice

/**
 * Keeps a reply read aloud going while Herald is out of sight (the screen locked, another app in front), the way
 * music does. On Android, a media-playback foreground service with a media session: the lock screen and the
 * notification shade show its Pause, Resume and Stop.
 */
interface ReadAloudKeepAlive {
    /**
     * Holds the reading up until [release]. [controls] is what the platform's own controls (the notification, the
     * lock screen, a headset button) do to it. False when the platform refused at once, and [onLost] runs when it
     * refuses later; either way the reading should stop when the app goes away.
     */
    fun hold(controls: ReadAloudControls, onLost: () -> Unit = {}): Boolean

    /** The reading was paused, or went on, from the app itself: the platform's controls show it. */
    fun paused(paused: Boolean)

    fun release()
}

/** What the platform's controls do to the reply being read. */
interface ReadAloudControls {
    fun pause()

    fun resume()

    fun stop()
}
