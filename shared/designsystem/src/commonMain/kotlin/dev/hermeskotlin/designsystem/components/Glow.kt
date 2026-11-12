package dev.hermeskotlin.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalGraphicsContext
import kotlin.math.ceil

/**
 * Whether the app's colored glows draw (the Glow setting): the [halo] behind each screen; the soft light under
 * prompts, the app mark, Send, New chat, the main buttons and live subagent cards; the voice screen's glows; and
 * the assistant edge's wide halo (its crisp line stays). Off, the same things sit flat. Plain black depth shadows
 * aren't glows and draw either way.
 */
val LocalGlow = compositionLocalOf { true }

/**
 * A colored [shadow] under the element in [shape], when [LocalGlow] is on.
 *
 * The shadow is painted from a bitmap as large as the element, so an element too large for one ([glowFits]) sits
 * flat: a prompt many screens tall would otherwise ask for a bitmap Android refuses to draw, and crash the app
 * each time the chat opens.
 */
@Composable
fun Modifier.glow(shape: Shape, shadow: Shadow): Modifier {
    if (!LocalGlow.current) return this
    val shadows = LocalGraphicsContext.current.shadowContext
    val painter = remember(shadows, shape, shadow) { shadows.createDropShadowPainter(shape, shadow) }
    return drawBehind {
        if (glowFits(size, shadow.radius.toPx(), shadow.spread.toPx())) with(painter) { draw(size) }
    }
}

/**
 * Whether the shadow of an element of [size] pixels, blurred by [radius] and grown by [spread], fits the bitmap
 * a glow may take. The bitmap has a byte per pixel and covers the element plus the blur and spread on each side.
 */
internal fun glowFits(size: Size, radius: Float, spread: Float): Boolean {
    val outset = (radius + spread) * 2
    return ceil(size.width + outset).toLong() * ceil(size.height + outset).toLong() <= GLOW_MAX_PIXELS
}

/** About three phone screens of a prompt. Android refuses to draw a bitmap over 100 MB; this stays far below. */
private const val GLOW_MAX_PIXELS = 8_000_000L
