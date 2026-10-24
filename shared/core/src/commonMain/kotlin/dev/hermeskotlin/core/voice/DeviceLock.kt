package dev.hermeskotlin.core.voice

/**
 * Whether the phone is locked right now. A voice chat doesn't take an approval while it is: as with a
 * notification's Approve, a locked phone would otherwise run a command for whoever holds it.
 */
fun interface DeviceLock {
    fun locked(): Boolean

    companion object {
        /** A device without a lock to ask about. */
        val None = DeviceLock { false }
    }
}
