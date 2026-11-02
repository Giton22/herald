package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.rememberCursorPositionProvider
import kotlinx.coroutines.delay

/**
 * Foundation's TooltipArea gives its content no minimum size, so a size the caller set on [modifier] wouldn't
 * reach the button inside; this box passes it on, as the button alone would have taken it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun Tooltip(text: String, modifier: Modifier, content: @Composable () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    // A click puts the label away until the mouse leaves and comes back.
    var pressed by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    // Reached with Tab, a button names itself too, under it, since there's no pointer to follow.
    var focused by remember { mutableStateOf(false) }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(hovered, pressed) {
        if (shown) lastHiddenAt = System.nanoTime()
        shown = false
        if (hovered && !pressed) {
            // Moving along a toolbar with a label up, the next one shows at once, as desktop tooltips do.
            if (System.nanoTime() - lastHiddenAt > TOOLTIP_DELAY_MS * 1_000_000) delay(TOOLTIP_DELAY_MS)
            shown = true
        }
    }
    val below = with(LocalDensity.current) { 6.dp.roundToPx() }
    Box(
        modifier
            .onFocusChanged { focused = it.hasFocus && inputMode.inputMode == InputMode.Keyboard }
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) {
                hovered = false
                pressed = false
            }
            .onPointerEvent(PointerEventType.Press) { pressed = true },
        propagateMinConstraints = true,
    ) {
        content()
        when {
            // Under the pointer, clear of it.
            text.isEmpty() -> Unit
            shown -> Popup(popupPositionProvider = rememberCursorPositionProvider(DpOffset(0.dp, 18.dp))) { TooltipLabel(text) }
            focused -> Popup(popupPositionProvider = remember(below) { UnderAnchor(below) }) { TooltipLabel(text) }
        }
    }
}

/** Centred under the element, [gap] below it; above it when there's no room beneath; kept inside the window. */
private class UnderAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val under = anchorBounds.bottom + gap
        val y = if (under + popupContentSize.height <= windowSize.height) under else anchorBounds.top - gap - popupContentSize.height
        return IntOffset(x, y.coerceAtLeast(0))
    }
}

/** How long the mouse rests before the label shows. */
private const val TOOLTIP_DELAY_MS = 500L

/** When a label last went away, so the next one within [TOOLTIP_DELAY_MS] skips the wait. UI thread only. */
private var lastHiddenAt = 0L
