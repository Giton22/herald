package dev.hermeskotlin.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.text

/** A scrollbar to drag on the desktop, for a column scrolled with [state]; a phone scrolls by touch and shows none. */
@Composable
expect fun VerticalScrollbar(state: ScrollState, modifier: Modifier = Modifier)

/** A scrollbar to drag on the desktop, for a lazy list scrolled with [state]; a phone scrolls by touch and shows none. */
@Composable
expect fun VerticalScrollbar(state: LazyListState, modifier: Modifier = Modifier)

/**
 * A list scrolled with [state] ([content]), with the desktop's scrollbar along its right edge; [bottomInset] keeps
 * the bar clear of a footer floating over the list's end. A phone shows the list alone.
 */
@Composable
fun ScrollbarBox(state: LazyListState, modifier: Modifier = Modifier, bottomInset: Dp = 0.dp, content: @Composable () -> Unit) {
    Box(modifier) {
        content()
        // As tall as the list, however it's sized: a list capped at a height doesn't grow to fit the bar.
        Box(Modifier.matchParentSize().padding(top = 2.dp, bottom = bottomInset, end = 1.dp)) {
            VerticalScrollbar(state, Modifier.align(Alignment.TopEnd).fillMaxHeight())
        }
    }
}

/** A [ScrollbarBox] that keeps its list's state itself and hands it to [content], for a list nothing else scrolls. */
@Composable
fun ScrollbarBox(modifier: Modifier = Modifier, bottomInset: Dp = 0.dp, content: @Composable (LazyListState) -> Unit) {
    val state = rememberLazyListState()
    ScrollbarBox(state, modifier, bottomInset) { content(state) }
}

/** The same for a column scrolled with [state]. */
@Composable
fun ScrollbarBox(state: ScrollState, modifier: Modifier = Modifier, bottomInset: Dp = 0.dp, content: @Composable () -> Unit) {
    Box(modifier) {
        content()
        Box(Modifier.matchParentSize().padding(top = 2.dp, bottom = bottomInset, end = 1.dp)) {
            VerticalScrollbar(state, Modifier.align(Alignment.TopEnd).fillMaxHeight())
        }
    }
}

/**
 * The desktop's scrollbar drawn over the right edge of a column scrolled with [state], for a column that can't be
 * wrapped in a [ScrollbarBox]: put it just before `verticalScroll(state)`, so it measures what's in view. The same
 * thin thumb, faint until the mouse is on it; dragged, or clicked beside it to jump there. A phone shows none.
 */
fun Modifier.scrollbar(state: ScrollState): Modifier = composed {
    if (!LocalDesktopLayout.current) return@composed this
    val ink = Theme[colors][text]
    var hot by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (hot) 0.40f else 0.18f, tween(200))
    // At the end edge, as the other bars are: the left one in a right-to-left layout.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    drawWithContent {
        drawContent()
        thumb(state)?.let { (top, height) ->
            val width = THICKNESS.toPx()
            drawRoundRect(
                ink.copy(alpha = alpha),
                topLeft = Offset(if (rtl) EDGE.toPx() else size.width - width - EDGE.toPx(), top),
                size = Size(width, height),
                cornerRadius = CornerRadius(width / 2),
            )
        }
    }.pointerInput(state, rtl) {
        awaitPointerEventScope {
            while (true) {
                // Before what's scrolled sees it, so a press on the bar doesn't also click what's under it.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                val x = if (rtl) change.position.x else size.width - change.position.x
                val onBar = x <= GRAB.toPx() && state.maxValue in 1 until Int.MAX_VALUE
                when (event.type) {
                    PointerEventType.Exit -> hot = false
                    PointerEventType.Move, PointerEventType.Enter -> hot = onBar
                    PointerEventType.Press -> if (onBar && event.buttons.isPrimaryPressed) {
                        change.consume()
                        val (top, height) = thumb(state) ?: continue
                        // A press beside the thumb brings it there first, as a page's bar does.
                        if (change.position.y !in top..top + height) state.dispatchRawDelta(perPixel(state, height) * (change.position.y - top - height / 2))
                        var last = change.position.y
                        while (true) {
                            val drag = awaitPointerEvent(PointerEventPass.Initial)
                            val moved = drag.changes.firstOrNull() ?: break
                            moved.consume()
                            if (!moved.pressed) break
                            state.dispatchRawDelta(perPixel(state, height) * (moved.position.y - last))
                            last = moved.position.y
                        }
                        hot = false
                    }
                }
            }
        }
    }
}

/** `verticalScroll(rememberScrollState())` with the desktop's [scrollbar] over its right edge. */
fun Modifier.verticalScrollWithBar(): Modifier = composed {
    val state = rememberScrollState()
    scrollbar(state).verticalScroll(state)
}

/** Where the thumb is and how tall, for a view of the scrolled column; null when it all fits. */
private fun DrawScope.thumb(state: ScrollState): Pair<Float, Float>? = thumbIn(size.height, state, MIN_THUMB.toPx())

private fun PointerInputScope.thumb(state: ScrollState): Pair<Float, Float>? = thumbIn(size.height.toFloat(), state, MIN_THUMB.toPx())

private fun thumbIn(view: Float, state: ScrollState, minimum: Float): Pair<Float, Float>? {
    val max = state.maxValue
    if (max <= 0 || max == Int.MAX_VALUE || view <= 0f) return null
    val height = (view * view / (view + max)).coerceIn(minimum.coerceAtMost(view), view)
    return (view - height) * state.value / max to height
}

/** How far the column scrolls for each pixel the thumb moves. */
private fun PointerInputScope.perPixel(state: ScrollState, thumb: Float): Float =
    state.maxValue / (size.height - thumb).coerceAtLeast(1f)

private val THICKNESS = 6.dp
private val EDGE = 1.dp
/** Only as wide as the thumb and its gap: a wider strip would take clicks meant for the rows under it. */
private val GRAB = THICKNESS + EDGE
private val MIN_THUMB = 32.dp

/** A scrollbar to drag on the desktop, for a row scrolled sideways with [state]; a phone scrolls by touch and shows none. */
@Composable
expect fun HorizontalScrollbar(state: ScrollState, modifier: Modifier = Modifier)
