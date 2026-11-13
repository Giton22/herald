package dev.hermeskotlin.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the desktop's own light/dark setting is dark, where the host reads it itself because Compose's
 * `isSystemInDarkTheme()` doesn't follow it: on Linux Compose reads it once and never again. Null leaves the
 * answer to Compose, as on Android, Windows and macOS, and until a host has one.
 */
object SystemColorScheme {
    var dark by mutableStateOf<Boolean?>(null)
}
