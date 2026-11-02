package dev.hermeskotlin.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sparkles
import com.composeunstyled.Text
import com.composeunstyled.TextInput
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.UnstyledTextField
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.SegmentedControl
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.surfaceElevated
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.desktop.WindowChrome.dragsWindow
import dev.hermeskotlin.ui.HeraldTheme
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo

/** Where a quick entry prompt goes. */
enum class QuickTarget(val label: String) { NewChat("New chat"), OpenChat("This chat") }

private val SIZE = DpSize(620.dp, 132.dp)

/**
 * Hermes Desktop's quick entry: a small composer over whatever app is in front, summoned by Ctrl+Shift+Space.
 * Enter sends to a new chat or the open one and puts it away; Escape, or clicking elsewhere, puts it away too.
 */
@Composable
fun QuickEntryWindow(visible: Boolean, onDismiss: () -> Unit, onSend: (String, QuickTarget) -> Unit) {
    if (!visible) return
    val state = rememberWindowState(size = SIZE, position = spotlightPosition())
    // Where the system rounds a frameless window, it does, with its shadow and edge; elsewhere the window is
    // see-through and draws its own rounded panel.
    val native = WindowChrome.nativeCorners
    Window(
        onCloseRequest = onDismiss,
        state = state,
        title = "Kyllene quick entry",
        undecorated = true,
        transparent = !native,
        resizable = false,
        alwaysOnTop = true,
        onKeyEvent = { event -> (event.type == KeyEventType.KeyDown && event.key == Key.Escape).also { if (it) onDismiss() } },
    ) {
        // Like a spotlight, it goes once something else is clicked.
        LaunchedEffect(window) {
            WindowChrome.roundCorners(window, CORNER)
            window.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) = Unit

                override fun windowLostFocus(e: java.awt.event.WindowEvent?) = onDismiss()
            })
            window.toFront()
        }
        CompositionLocalProvider(LocalClipboard provides rememberPatientClipboard()) {
            HeraldTheme { QuickEntry(window, framed = !native, onSend = onSend) }
        }
    }
}

/** The quick entry's corners where macOS rounds them; Windows 11 has its own size. */
private val CORNER = 12.dp

@Composable
private fun QuickEntry(window: java.awt.Window, framed: Boolean, onSend: (String, QuickTarget) -> Unit) {
    val text = remember { TextFieldState() }
    val focus = remember { FocusRequester() }
    var target by remember { mutableStateOf(QuickTarget.NewChat) }
    LaunchedEffect(Unit) {
        text.clearText()
        focus.requestFocus()
    }
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(
        Modifier.fillMaxSize()
            .then(
                if (framed) Modifier.background(Theme[colors][surfaceElevated], shape).border(1.dp, Theme[colors][strokeStrong], shape)
                else Modifier.background(Theme[colors][surfaceElevated]),
            )
            // Its free space moves it, as a title bar would.
            .dragsWindow(window)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UnstyledIcon(Lucide.Sparkles, contentDescription = null, tint = Theme[colors][accent], modifier = Modifier.size(18.dp))
            UnstyledTextField(
                state = text,
                textStyle = Theme[typography][body],
                textColor = Theme[colors][textColor],
                cursorBrush = SolidColor(Theme[colors][accent]),
                selectionColors = LocalTextSelectionColors.current,
                lineLimits = TextFieldLineLimits.SingleLine,
                // A screen reader names the field, as it doesn't read a placeholder as one.
                modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "Ask Hermes" }.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        (event.key == Key.Enter || event.key == Key.NumPadEnter) && !event.isShiftPressed -> true.also {
                            val prompt = text.text.toString().trim()
                            if (prompt.isNotEmpty()) onSend(prompt, target)
                        }
                        // Tab switches where it goes, as the chips below do.
                        event.key == Key.Tab && !event.isCtrlPressed -> true.also {
                            target = if (target == QuickTarget.NewChat) QuickTarget.OpenChat else QuickTarget.NewChat
                        }
                        else -> false
                    }
                },
            ) {
                TextInput(placeholder = { Text("Ask Hermes anything…", style = Theme[typography][body], color = Theme[colors][textTertiary]) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SegmentedControl(
                options = QuickTarget.entries,
                selected = target,
                onSelect = { target = it },
                optionLabel = { it.label },
                modifier = Modifier.weight(1f),
            )
            Text(
                "Enter to send · Tab to switch · Esc to close",
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** Centred on the screen the mouse is on, a little way down from its top, as Hermes places it. */
private fun spotlightPosition(): WindowPosition {
    val screens = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration } }.getOrDefault(emptyList())
    val pointer = runCatching { MouseInfo.getPointerInfo().location }.getOrNull()
    val screen = screens.firstOrNull { pointer != null && it.bounds.contains(pointer) } ?: screens.firstOrNull()
        ?: return WindowPosition(Alignment.TopCenter)
    // The bounds are in the screen's own scaled units, which is what dp are on the desktop.
    val bounds = screen.bounds
    return WindowPosition(
        x = (bounds.x + (bounds.width - SIZE.width.value) / 2).dp,
        y = (bounds.y + bounds.height * 0.22f).dp,
    )
}
