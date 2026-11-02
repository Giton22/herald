package dev.hermeskotlin.desktop

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import com.jetbrains.JBR
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Window
import java.awt.event.MouseEvent

/**
 * What the JetBrains Runtime lets a window without the system's frame (the quick entry) borrow from the system:
 * its rounded corners, with their shadow and edge, and its own way of moving a window with the mouse.
 */
internal object WindowChrome {
    private val os = System.getProperty("os.name").orEmpty()
    private val mac = os.startsWith("Mac")

    /**
     * Whether the system rounds a frameless window's corners here: Windows 11 and macOS. A window it rounds must be
     * opaque (Windows leaves a see-through window square, without a shadow), so this is asked before it's made.
     */
    val nativeCorners: Boolean by lazy {
        (os == "Windows 11" || mac) && runCatching { JBR.isRoundedCornersManagerSupported() }.getOrDefault(false)
    }

    /** Rounds [window]'s corners as the system rounds its own windows'; [radius] is macOS's, Windows has one size. */
    fun roundCorners(window: Window, radius: Dp) {
        if (!nativeCorners) return
        runCatching { JBR.getRoundedCornersManager().setRoundedCorners(window, if (mac) radius.value else "full") }
    }

    /**
     * Drags [window] by whatever of this nothing else took (a field keeps its own presses). Where the window manager
     * moves windows itself (Linux), it's handed the move, so it snaps and tiles as a title bar's drag does; elsewhere
     * the window follows the pointer.
     */
    fun Modifier.dragsWindow(window: Window): Modifier = pointerInput(window) {
        val native = runCatching { JBR.isWindowMoveSupported() }.getOrDefault(false)
        if (native) {
            // Only once the press moves, so a click or a selection in the field stays the field's.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                val drag = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                if (drag != null) runCatching { JBR.getWindowMove().startMovingTogetherWithMouse(window, MouseEvent.BUTTON1) }
            }
        } else {
            // From the pointer's place on the screen, not in the window, which moves under it.
            var from: Point? = null
            var start: Point? = null
            detectDragGestures(
                onDragStart = {
                    from = MouseInfo.getPointerInfo()?.location
                    start = window.location
                },
                onDragEnd = { from = null },
                onDragCancel = { from = null },
            ) { change, _ ->
                change.consume()
                val pointer = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                val origin = from ?: return@detectDragGestures
                val place = start ?: return@detectDragGestures
                window.setLocation(place.x + pointer.x - origin.x, place.y + pointer.y - origin.y)
            }
        }
    }
}
