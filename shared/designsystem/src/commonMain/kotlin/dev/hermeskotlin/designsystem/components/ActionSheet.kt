package dev.hermeskotlin.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.roundToIntRect
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surfaceElevated

/**
 * A short list of actions on something ([SheetHeader] and [SheetAction] rows): a [BottomSheet] on a phone; on a
 * desktop, a menu that opens where the pointer is, as a context menu does, with the same rows made compact.
 * Picking a row, a click outside or Escape calls [onDismiss]. Opened from a button, [anchor] is that button in
 * window coordinates: the desktop's menu then hangs from it, as a menu button's does, wherever the pointer is.
 */
@Composable
fun ActionSheet(visible: Boolean, onDismiss: () -> Unit, anchor: Rect? = null, content: @Composable ColumnScope.() -> Unit) {
    if (!LocalDesktopLayout.current) return BottomSheet(visible, onDismiss, content = content)
    ContextMenu(visible, onDismiss, anchor, content)
}

/**
 * A desktop's context menu: a [MenuPanel] of [MenuRow]s that opens where the pointer is, for a right-click, or
 * from [anchor] when a button opened it. A press outside it (with either button), Escape, or picking a row (the
 * row's own action says so) calls [onDismiss].
 */
@Composable
fun ContextMenu(visible: Boolean, onDismiss: () -> Unit, anchor: Rect? = null, content: @Composable ColumnScope.() -> Unit) {
    // Each opening is a menu of its own, kept composed while it fades out: one opened while the last still fades
    // opens at the pointer and fades in, not where the last one was.
    // A plain holder, not state: it changes only in the pass that reads it.
    val menu = remember { arrayOf(MutableTransitionState(false)) }
    if (visible && !menu[0].targetState) menu[0] = MutableTransitionState(false)
    val shown = menu[0]
    shown.targetState = visible
    if (!shown.currentState && !shown.targetState) return
    key(shown) {
        val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
        val pointer = rememberPointerPosition()
        // Where the button was when this opening began.
        val position = remember { anchor?.let { FromButton(it.roundToIntRect(), gap) } ?: pointer }
        MenuPopup(position, onDismiss, active = shown.targetState) {
            AnimatedVisibility(
                visibleState = shown,
                enter = fadeIn() + scaleIn(initialScale = 0.94f, transformOrigin = TransformOrigin(0f, 0f)),
                exit = fadeOut(),
            ) {
                MenuPanel(content = content)
            }
        }
    }
}

/**
 * The popup a desktop menu sits in, at [position]. It takes the keyboard; Escape or a press outside it calls
 * [onDismiss]. On a desktop, a right-click outside it also reaches what is under the pointer, so that its own
 * menu opens in the same click, as a desktop's menus do. Once it closes ([active] false) while it fades out, it
 * lets go of the keyboard and the pointer: a click then goes to what it's on, and a dialog the picked row opened
 * takes the keyboard.
 */
@Composable
expect fun MenuPopup(position: PopupPositionProvider, onDismiss: () -> Unit, active: Boolean = true, content: @Composable () -> Unit)

/**
 * A desktop menu's panel, raised on the elevated surface: [MenuRow]s, [MenuSeparator]s, or a [SheetHeader] and
 * [SheetAction]s, which take their compact menu look in it. It scrolls when the window is shorter than the rows.
 */
@Composable
fun MenuPanel(modifier: Modifier = Modifier, minWidth: Dp = 220.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    // The panel takes the keyboard as it opens, so the arrow keys walk its rows, as a desktop menu's do, and
    // Enter picks the one they're on.
    val focus = LocalFocusManager.current
    val inputMode = LocalInputModeManager.current
    val panel = remember { FocusRequester() }
    // The panel itself holds the focus until a row takes it: from there Down is the first row and Up the last.
    var onPanel by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { panel.requestFocus() } }
    CompositionLocalProvider(LocalInMenu provides true) {
        Column(
            modifier
                .focusRequester(panel)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    fun first() {
                        panel.requestFocus()
                        focus.moveFocus(FocusDirection.Next)
                    }
                    // Down to the end: where the focus stops, or comes round to the panel (a popup's focus wraps).
                    fun last() {
                        first()
                        repeat(MAX_MENU_ROWS) {
                            if (!focus.moveFocus(FocusDirection.Next)) return
                            if (onPanel) {
                                focus.moveFocus(FocusDirection.Previous)
                                return
                            }
                        }
                    }
                    if (event.key !in MENU_KEYS) return@onPreviewKeyEvent false
                    // As Tab does, so the row the keys are on shows its focus ring.
                    inputMode.requestInputMode(InputMode.Keyboard)
                    // Past the last row Down comes round to the first, and Up past the first to the last.
                    when (event.key) {
                        Key.MoveHome, Key.PageUp -> first()
                        Key.MoveEnd, Key.PageDown -> last()
                        Key.DirectionDown -> if (onPanel || !focus.moveFocus(FocusDirection.Next) || onPanel) first()
                        else -> if (onPanel || !focus.moveFocus(FocusDirection.Previous) || onPanel) last()
                    }
                    true
                }
                .onFocusChanged { onPanel = it.isFocused }
                .focusable()
                .widthIn(min = minWidth, max = 300.dp)
                .width(IntrinsicSize.Max)
                .heightIn(max = (window - MENU_MARGIN * 2).coerceAtLeast(120.dp))
                .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.22f))
                .clip(shape)
                .background(Theme[colors][surfaceElevated])
                .border(1.dp, Theme[colors][stroke], shape)
                .verticalScrollWithBar()
                .padding(vertical = 4.dp),
            content = content,
        )
    }
}

/** The keys that walk a menu's rows. */
private val MENU_KEYS = setOf(Key.DirectionDown, Key.DirectionUp, Key.MoveHome, Key.MoveEnd, Key.PageUp, Key.PageDown)

/** More rows than any menu has: how far End walks before it stops. */
private const val MAX_MENU_ROWS = 64

/** True inside a desktop [ActionSheet], where the sheet's rows are a menu's: compact. */
internal val LocalInMenu = staticCompositionLocalOf { false }

/** Where a desktop menu opens: at the pointer, kept inside the window. A phone never asks. */
@Composable
internal expect fun rememberPointerPosition(): PopupPositionProvider

/**
 * A menu hung from the button that opened it, [gap] off it: under it, or over it when there's no room below;
 * lined up with the button's start in the window's left half and its end in the right half, so it opens
 * towards the middle. Kept inside the window.
 */
private class FromButton(private val button: IntRect, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        val x = if (button.center.x < windowSize.width / 2) button.left else button.right - w
        val under = button.bottom + gap
        val y = if (under + h <= windowSize.height) under else button.top - gap - h
        return IntOffset(x.coerceIn(0, (windowSize.width - w).coerceAtLeast(0)), y.coerceIn(0, (windowSize.height - h).coerceAtLeast(0)))
    }
}

/** Room a menu keeps from the window's top and bottom. */
private val MENU_MARGIN = 12.dp
