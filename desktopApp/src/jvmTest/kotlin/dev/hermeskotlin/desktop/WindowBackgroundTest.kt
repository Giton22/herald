package dev.hermeskotlin.desktop

import androidx.compose.ui.graphics.Color
import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeFalse

class WindowBackgroundTest {
    private fun rgb(c: java.awt.Color) = c.rgb and 0xFFFFFF

    @Test fun theWindowAndItsPanesTakeTheAppsColor() {
        assumeFalse("Needs a display", GraphicsEnvironment.isHeadless())
        var frame: JFrame? = null
        SwingUtilities.invokeAndWait { frame = JFrame() }
        val window = frame!!
        try {
            // The look and feel's own grey is what showed.
            val before = rgb(window.contentPane.background)
            SwingUtilities.invokeAndWait { paintWindowBackground(window, Color(0xFF141416)) }
            assertEquals(0x141416, rgb(window.background))
            assertEquals(0x141416, rgb(window.rootPane.background))
            assertEquals(0x141416, rgb(window.contentPane.background))
            assertEquals(0x141416, rgb(window.layeredPane.background))
            check(before != 0x141416)
            // A later theme change repaints the same window.
            SwingUtilities.invokeAndWait { paintWindowBackground(window, Color(0xFFF4F5F8)) }
            assertEquals(0xF4F5F8, rgb(window.contentPane.background))
        } finally {
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }

    @Test fun anAlphaInTheColorIsDropped() {
        assumeFalse("Needs a display", GraphicsEnvironment.isHeadless())
        var frame: JFrame? = null
        SwingUtilities.invokeAndWait { frame = JFrame() }
        val window = frame!!
        try {
            SwingUtilities.invokeAndWait { paintWindowBackground(window, Color(0x80141416)) }
            assertEquals(255, window.background.alpha)
        } finally {
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }
}
