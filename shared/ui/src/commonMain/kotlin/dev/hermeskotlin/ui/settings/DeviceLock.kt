package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.Composable

/** Whether the device has a screen lock (PIN, pattern or password), which App lock falls back to. */
@Composable
expect fun deviceHasScreenLock(): Boolean
