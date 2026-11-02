package dev.hermeskotlin.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hermeskotlin.designsystem.LocalDesktopLayout

/**
 * The smallest area a control takes taps in, Android's 48dp guideline; on the desktop, where a mouse points
 * exactly, a tighter 36dp. Icons and fills can be smaller; the control around them isn't. Give packed controls
 * this size outright: Compose only stretches a smaller target as far as its neighbours allow.
 */
val MinTouchTarget: Dp
    @Composable @ReadOnlyComposable
    get() = if (LocalDesktopLayout.current) DesktopTarget else 48.dp

/** How small a control may be under a mouse. */
private val DesktopTarget = 36.dp
