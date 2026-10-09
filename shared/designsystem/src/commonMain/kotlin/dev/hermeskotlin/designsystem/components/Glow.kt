package dev.hermeskotlin.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow

/**
 * Whether the app's colored glows draw (the Glow setting): the [halo] behind each screen; the soft light under
 * prompts, the app mark, Send, New chat, the main buttons and live subagent cards; the voice screen's glows; and
 * the assistant edge's wide halo (its crisp line stays). Off, the same things sit flat. Plain black depth shadows
 * aren't glows and draw either way.
 */
val LocalGlow = compositionLocalOf { true }

/** A colored [shadow] under the element in [shape], when [LocalGlow] is on. */
@Composable
fun Modifier.glow(shape: Shape, shadow: Shadow): Modifier = if (LocalGlow.current) dropShadow(shape, shadow) else this
