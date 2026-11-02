package dev.hermeskotlin.ui

import androidx.compose.runtime.Composable

// A desktop window isn't rebuilt for a theme switch or a resize.
@Composable
actual fun rememberChangingConfigurations(): () -> Boolean = { false }

// The desktop doesn't stop the app when the screen sleeps.
@Composable
actual fun KeepScreenOn(on: Boolean) = Unit
