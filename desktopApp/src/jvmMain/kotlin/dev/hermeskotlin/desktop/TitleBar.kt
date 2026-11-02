package dev.hermeskotlin.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import dev.hermeskotlin.ui.App
import dev.hermeskotlin.ui.TitleBar
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import java.awt.Frame

private val TITLE_BAR_HEIGHT = 40.dp

/**
 * The window's title bar as the JetBrains Runtime lets the app draw it: the app's own strip in its theme, with
 * the system's window buttons, dragging, snapping and resizing left as they are. Null on another JVM, which keeps
 * the system's title bar.
 */
class DesktopTitleBar private constructor(private val bar: WindowDecorations.CustomTitleBar) {

    /** The system's window buttons follow the app's theme. */
    fun dark(on: Boolean) = bar.putProperty("controls.dark", on)

    /**
     * The strip itself; only the space the system's buttons leave is the app's. Without [title] it's bare, for a
     * window whose own sidebar already names the app.
     */
    @Composable
    fun Content(icon: Painter? = null, title: String? = null) {
        Row(
            Modifier.fillMaxWidth().height(TITLE_BAR_HEIGHT).draggable(bar)
                .padding(start = bar.leftInset.dp + 14.dp, end = bar.rightInset.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            if (title != null) Text(title, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        }
    }

    /**
     * The bar for a window whose screens share its row ([App]'s sidebar and chat headers): [TitleBar.frame] goes on
     * the window's root and sees each event in the bar's height after the app's controls have, so a click on one
     * stays the app's and the rest of the row drags the window.
     */
    @Composable
    fun forScreens(): TitleBar {
        // The runtime measures the system's buttons once the window is up, and again when they change (another
        // screen's scale, full screen on a Mac): looked at often until they're known, then every second.
        var insets by remember { mutableStateOf(bar.leftInset to bar.rightInset) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(if (insets.first == 0f && insets.second == 0f) 100 else 1000)
                insets = bar.leftInset to bar.rightInset
            }
        }
        return remember(insets) {
            TitleBar(insets.first.dp, insets.second.dp, TITLE_BAR_HEIGHT, Modifier.draggable(bar, below = TITLE_BAR_HEIGHT))
        }
    }

    /**
     * Compose takes every mouse event in the window, so the runtime would see the strip as the app's and never
     * drag it. Each event nothing in the strip took says it's the title bar's: drag, double-click to maximize,
     * Windows' snap layouts. One a control took (a press until its release) stays the app's. With [below], this
     * sits on the whole window and only the events above that line are the strip's.
     */
    private fun Modifier.draggable(bar: WindowDecorations.CustomTitleBar, below: Dp? = null): Modifier = pointerInput(bar, below) {
        val limit = below?.toPx()
        awaitPointerEventScope {
            var inControl = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                // With no button down, no control is being pressed, though its release went to a popup this never saw.
                if (inControl && event.type != PointerEventType.Release && !event.buttons.areAnyPressed) inControl = false
                event.changes.forEach {
                    if (limit != null && it.position.y >= limit && !inControl) return@forEach
                    if (!it.isConsumed && !inControl) {
                        bar.forceHitTest(false)
                    } else {
                        if (event.type == PointerEventType.Press) inControl = true
                        if (event.type == PointerEventType.Release) inControl = false
                        bar.forceHitTest(true)
                    }
                }
            }
        }
    }

    companion object {
        /** Takes over [window]'s title bar while in composition; null where the runtime can't. */
        @Composable
        fun rememberFor(window: Frame): DesktopTitleBar? {
            val titleBar = remember(window) {
                if (!runCatching { JBR.isWindowDecorationsSupported() }.getOrDefault(false)) return@remember null
                val decorations = JBR.getWindowDecorations()
                val bar = decorations.createCustomTitleBar().apply { height = TITLE_BAR_HEIGHT.value }
                decorations.setCustomTitleBar(window, bar)
                DesktopTitleBar(bar)
            }
            DisposableEffect(window) {
                onDispose { if (titleBar != null) JBR.getWindowDecorations().setCustomTitleBar(window, null) }
            }
            return titleBar
        }
    }
}
