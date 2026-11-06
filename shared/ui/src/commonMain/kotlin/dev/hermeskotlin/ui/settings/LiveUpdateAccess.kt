package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.Composable

/**
 * What Android needs before a Live Update can show: Android 16 or later, notifications on for Kyllene, and
 * Android's own permission to promote an ongoing notification. The desktop has no Live Updates.
 */
interface LiveUpdateAccess {
    /** This device has Live Updates at all: Android 16 or later, whatever the user's settings say. */
    val supported: Boolean

    /**
     * Android would show one now, as far as Kyllene can tell: notifications are on and promoted notifications are
     * allowed.
     */
    val allowed: Boolean

    /**
     * Asks for what is missing: first the notification permission, or Android's notification settings when
     * notifications are off or the permission was refused for good; then the promoted-notification page, by its
     * documented name, falling back to the app's notification page when nothing answers it.
     */
    fun request()
}

/** Reads again when the user comes back to the app, so a grant made in Android's settings shows at once. */
@Composable
expect fun rememberLiveUpdateAccess(): LiveUpdateAccess
