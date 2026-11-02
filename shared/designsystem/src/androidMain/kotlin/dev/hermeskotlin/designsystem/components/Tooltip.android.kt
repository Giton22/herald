package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun Tooltip(text: String, modifier: Modifier, content: @Composable () -> Unit) = Box(modifier) { content() }
