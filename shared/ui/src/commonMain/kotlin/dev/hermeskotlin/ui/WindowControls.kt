package dev.hermeskotlin.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The desktop window's title bar, which the app draws itself: the screens run up to the window's top edge, and
 * the system's window buttons sit over its first [height]: [start] wide at the left (a Mac's), [end] at the right
 * (Windows' and Linux's). [frame] goes on the window's root, where it tells a drag on the bar from a click on the
 * app's own controls in it.
 */
@Immutable
class TitleBar(val start: Dp, val end: Dp, val height: Dp, val frame: Modifier)

/** The window buttons over the screens, in pixels: the zoom the screens are drawn at doesn't move them. */
@Immutable
internal class WindowControls(val start: Int, val end: Int, val height: Int) {
    companion object {
        val None = WindowControls(0, 0, 0)
    }
}

internal val LocalWindowControls = staticCompositionLocalOf { WindowControls.None }

/**
 * Pads this row clear of the system's window buttons where it runs under them along the window's top edge, so
 * the sidebar's header and the chat's sit in the title bar's row without a button of the app's going under
 * Close. Nothing changes where there are no such buttons (the phone) or the row is lower down.
 */
internal fun Modifier.clearOfWindowControls(): Modifier = composed {
    val controls = LocalWindowControls.current
    if (controls.height == 0) return@composed this
    val windowWidth = LocalWindowInfo.current.containerSize.width
    var start by remember { mutableIntStateOf(0) }
    var end by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    this
        .onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInRoot()
            val under = bounds.top < controls.height
            start = if (under) (controls.start - bounds.left).toInt().coerceAtLeast(0) else 0
            end = if (under) (bounds.right - (windowWidth - controls.end)).toInt().coerceAtLeast(0) else 0
        }
        .padding(start = with(density) { start.toDp() }, end = with(density) { end.toDp() })
}

/** How far down the system's window buttons reach, at the zoom the screens are drawn at; zero on a phone. */
@Composable
internal fun windowControlsHeight(): Dp = with(LocalDensity.current) { LocalWindowControls.current.height.toDp() }

/** Room between the app's last control in the title bar's row and the system's first button. */
internal val WINDOW_CONTROLS_GAP = 8.dp
