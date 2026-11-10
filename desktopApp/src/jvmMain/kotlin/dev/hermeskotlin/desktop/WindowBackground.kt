package dev.hermeskotlin.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.awt.Window
import javax.swing.RootPaneContainer

/**
 * Skia clears each frame to the window's own background, which the system's look and feel makes light grey. Where
 * the picture hasn't caught up with the window (the first frame, a window grown by a drag, one moved to another
 * screen) that grey shows as white strips on a dark theme. Giving the window the app's color makes the gap match
 * what's drawn there. The window's own panes are set too, since a look and feel may paint over a window's color.
 */
internal fun paintWindowBackground(window: Window, color: Color) {
    val paint = java.awt.Color(color.toArgb(), false)
    window.background = paint
    (window as? RootPaneContainer)?.let {
        it.rootPane.background = paint
        it.contentPane.background = paint
        it.layeredPane.background = paint
    }
}
