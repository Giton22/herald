package dev.hermeskotlin.designsystem

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The few words the components say themselves, mostly to a screen reader. This module has no catalogue: the app
 * provides them in its own language through [LocalDesignWords], and until it does (previews, tests) they're English.
 */
data class DesignWords(
    val loading: String = "Loading",
    val clear: String = "Clear",
    val showPassword: String = "Show password",
    val hidePassword: String = "Hide password",
    val dismiss: String = "Dismiss",
    val copy: String = "Copy",
    val copied: String = "Copied",
    val openPicture: String = "Open the picture",
    val pictureFailed: String = "Picture couldn’t load",
)

val LocalDesignWords = staticCompositionLocalOf { DesignWords() }
