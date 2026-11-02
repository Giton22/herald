package dev.hermeskotlin.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberCursorPositionProvider
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.MouseEvent

// A hair past the pointer, so the first row isn't under it.
@Composable
internal actual fun rememberPointerPosition(): PopupPositionProvider = rememberCursorPositionProvider(DpOffset(2.dp, 2.dp))

@Composable
actual fun MenuPopup(position: PopupPositionProvider, onDismiss: () -> Unit, active: Boolean, content: @Composable () -> Unit) {
    // The right-click outside that closed this menu, to hand on to what was under it. A plain holder: only the
    // popup's leaving reads it.
    val passOn = remember { arrayOfNulls<MouseEvent>(1) }
    // Gone at once, with no fade: the popup takes every press while it's there.
    var gone by remember { mutableStateOf(false) }
    if (gone) return
    // Posted when the popup leaves, by this or by its owner, so it's handled once the popup's layer is gone.
    DisposableEffect(Unit) { onDispose { passOn[0]?.repost() } }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = {
            // Already closing, it lets presses through to what's under it: one handed on again would arrive twice.
            if (!active) return@Popup
            // Compose handles a window's input as it comes, so the event in hand is the press outside.
            val event = EventQueue.getCurrentEvent() as? MouseEvent
            if (event != null && event.id == MouseEvent.MOUSE_PRESSED && event.button == MouseEvent.BUTTON3) {
                passOn[0] = event
                gone = true
            }
            onDismiss()
        },
        // Closed and fading out, it neither takes the keyboard nor blocks presses outside it.
        properties = PopupProperties(focusable = active),
        content = content,
    )
}

/** Posts this press, and its release, as new input: a click of the same button at the same point. */
private fun MouseEvent.repost() {
    val queue = Toolkit.getDefaultToolkit().systemEventQueue
    val source = component
    queue.postEvent(MouseEvent(source, MouseEvent.MOUSE_PRESSED, `when`, modifiersEx, x, y, xOnScreen, yOnScreen, 1, isPopupTrigger, button))
    queue.postEvent(MouseEvent(source, MouseEvent.MOUSE_RELEASED, `when`, modifiersEx and MouseEvent.BUTTON3_DOWN_MASK.inv(), x, y, xOnScreen, yOnScreen, 1, false, button))
}
