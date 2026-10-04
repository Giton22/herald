package dev.hermeskotlin.designsystem.components

import androidx.compose.ui.unit.dp

/**
 * The smallest area a control takes taps in, Android's 48dp guideline. Icons and fills can be smaller;
 * the control around them isn't. Give packed controls this size outright: Compose only stretches a
 * smaller target as far as its neighbours allow.
 */
val MinTouchTarget = 48.dp
