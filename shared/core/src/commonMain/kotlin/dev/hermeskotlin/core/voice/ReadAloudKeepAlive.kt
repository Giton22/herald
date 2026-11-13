package dev.hermeskotlin.core.voice

/**
 * Keeps a reply read aloud going while Herald is out of sight (the screen locked, another app in front), the way
 * music does. On Android, a media-playback foreground service with an ongoing notification that has a Stop.
 */
interface ReadAloudKeepAlive {
    /**
     * Holds the reading up until [release]. [onStop] runs when the person stops it from outside the app (the
     * notification's Stop). False when the platform refused at once, and [onLost] runs when it refuses later;
     * either way the reading should stop when the app goes away.
     */
    fun hold(onStop: () -> Unit, onLost: () -> Unit = {}): Boolean

    fun release()
}
