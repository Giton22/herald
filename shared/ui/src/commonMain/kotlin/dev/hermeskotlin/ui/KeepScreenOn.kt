package dev.hermeskotlin.ui

import androidx.compose.runtime.Composable

/** Keeps the screen from timing out while [on], since the screen going dark stops the app. No-op elsewhere. */
@Composable
expect fun KeepScreenOn(on: Boolean)
