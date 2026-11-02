package dev.hermeskotlin.designsystem.components

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.focusable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import com.composeunstyled.DialogPanel
import com.composeunstyled.Scrim
import com.composeunstyled.Text
import com.composeunstyled.UnstyledDialog
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.title as titleStyle
import dev.hermeskotlin.designsystem.typography

/**
 * On the desktop, gives back the input mode a dialog opened in once it closes. A rename box turns the keyboard's
 * mode on to select its name; kept on after a dialog opened with the mouse, it would leave a focus ring on
 * whatever takes the focus next.
 */
@Composable
internal fun RestoreInputMode(visible: Boolean) {
    if (!LocalDesktopLayout.current) return
    val manager = LocalInputModeManager.current
    // Taken while composing, before any effect of the dialog's own can change it.
    val opened = remember { arrayOfNulls<InputMode>(1) }
    if (visible && opened[0] == null) opened[0] = manager.inputMode
    val restore = {
        opened[0]?.let { was ->
            opened[0] = null
            if (manager.inputMode != was) manager.requestInputMode(was)
        }
    }
    LaunchedEffect(visible) { if (!visible) restore() }
    // A dialog taken away while still open (its screen left) gives it back too.
    DisposableEffect(Unit) { onDispose { restore() } }
}

/**
 * How many dialogs and sheets a window has open now. A desktop's keys that act on what's behind them (stepping to
 * another chat) wait while any is, as they would in a desktop app's modal window. One per window, through
 * [LocalOpenModals]: a sheet in one window doesn't hold another's keys.
 */
class OpenModals {
    private var count by mutableIntStateOf(0)

    val any: Boolean get() = count > 0

    companion object {
        /** Counts one in this window's [OpenModals] while [visible], and no longer once it's gone from the screen. */
        @Composable
        fun Count(visible: Boolean) {
            if (!visible) return
            val modals = LocalOpenModals.current
            DisposableEffect(modals) {
                modals.count++
                onDispose { modals.count-- }
            }
        }
    }
}

/** The window's [OpenModals]; the app's theme gives each window its own. */
val LocalOpenModals = staticCompositionLocalOf { OpenModals() }

/**
 * Centered modal panel: [title], optional [message], free [content] (e.g. a text field) and a
 * right-aligned row of [actions]. Tapping the scrim or Back calls [onDismissRequest].
 */
@Composable
fun Dialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    message: String? = null,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    RestoreInputMode(visible)
    OpenModals.Count(visible)
    UnstyledDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        overlay = { Scrim(scrimColor = Color.Black.copy(alpha = 0.45f), enter = fadeIn(), exit = fadeOut()) },
    ) {
        val desktop = LocalDesktopLayout.current
        // A desktop's dialog takes the keyboard as it opens, so Escape closes it as a desktop dialog's does, even
        // one with nothing focusable but its buttons; a field or button that took the focus first keeps it.
        val focus = remember { FocusRequester() }
        var focusInside by remember { mutableStateOf(false) }
        if (desktop) {
            LaunchedEffect(Unit) {
                withFrameNanos {}
                withFrameNanos {}
                if (!focusInside) runCatching { focus.requestFocus() }
            }
        }
        Box(Modifier.fillMaxSize().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
            DialogPanel(
                paneTitle = title,
                enter = fadeIn() + scaleIn(initialScale = 0.96f),
                exit = fadeOut() + scaleOut(targetScale = 0.96f),
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().then(
                    if (!desktop) Modifier else Modifier
                        .onFocusChanged { focusInside = it.hasFocus }
                        .focusRequester(focus)
                        .focusable()
                        .onKeyEvent { event ->
                            val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                            if (escape) onDismissRequest()
                            escape
                        },
                ),
            ) {
                Surface(Modifier.fillMaxWidth(), elevated = true) {
                    Column(Modifier.padding(if (desktop) 18.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(if (desktop) 12.dp else 16.dp)) {
                        // A desktop dialog's title is a heading, not a page's.
                        val heading = Theme[typography][titleStyle]
                        Text(
                            title,
                            style = if (desktop) heading.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold) else heading,
                            color = Theme[colors][textColor],
                        )
                        if (message != null) {
                            Text(message, style = Theme[typography][body], color = Theme[colors][textSecondary])
                        }
                        content()
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            content = actions,
                        )
                    }
                }
            }
        }
    }
}
