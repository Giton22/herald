package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

// A phone, as on Android: no pointer, hover or scrollbars; actions show in a bottom sheet.

// Should a menu open anyway, it sits in the middle.
@Composable
internal actual fun rememberPointerPosition(): PopupPositionProvider = Centered

@Composable
actual fun MenuPopup(position: PopupPositionProvider, onDismiss: () -> Unit, active: Boolean, content: @Composable () -> Unit) =
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = active), content = content)

private object Centered : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset((windowSize.width - popupContentSize.width) / 2, (windowSize.height - popupContentSize.height) / 2)
}

actual val ResizeHorizontalPointer: PointerIcon = PointerIcon.Default

@Composable
actual fun VerticalScrollbar(state: ScrollState, modifier: Modifier) = Unit

@Composable
actual fun VerticalScrollbar(state: LazyListState, modifier: Modifier) = Unit

@Composable
actual fun HorizontalScrollbar(state: ScrollState, modifier: Modifier) = Unit

@Composable
actual fun Tooltip(text: String, modifier: Modifier, content: @Composable () -> Unit) = Box(modifier) { content() }
