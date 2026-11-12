package dev.hermeskotlin.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import dev.hermeskotlin.ui.rememberDesktopWeb

/**
 * The bot's screen, in the app's own browser.
 *
 * The browser is the one the desktop app carries for widgets and plugin pages (Chromium in the JetBrains
 * Runtime). Without it there is nowhere to draw the screen, and the pane is told so.
 */
@Composable
actual fun ScreenViewer(
    streamUrl: String,
    viewOnly: Boolean,
    onHandle: (ScreenViewerHandle) -> Unit,
    onEvent: (ScreenViewerEvent) -> Unit,
    modifier: Modifier,
) {
    val web = rememberDesktopWeb()
    LaunchedEffect(web) {
        if (web == null) onEvent(ScreenViewerEvent.Failed("This runtime has no browser to show the screen in."))
    }
    if (web == null) return
    web.ScreenPage(streamUrl, viewOnly, onHandle, onEvent, modifier)
}
