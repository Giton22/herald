package dev.hermeskotlin.ui

import androidx.compose.runtime.Composable

/**
 * Asks whether the screen is going away only to come straight back, a turn of the phone or a dark mode switch say,
 * rather than the person leaving it. Always false where screens aren't rebuilt for that.
 */
@Composable
expect fun rememberChangingConfigurations(): () -> Boolean
