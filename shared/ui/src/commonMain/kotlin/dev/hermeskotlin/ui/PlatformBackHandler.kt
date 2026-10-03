package dev.hermeskotlin.ui

import androidx.compose.runtime.Composable

/** System back gesture/button. No-op on platforms without one. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
