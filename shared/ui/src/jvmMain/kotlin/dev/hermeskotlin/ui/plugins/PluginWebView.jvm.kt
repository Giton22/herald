package dev.hermeskotlin.ui.plugins

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import dev.hermeskotlin.ui.rememberDesktopWeb
import java.awt.Desktop
import java.net.URI

/**
 * The plugin page in the app's own browser, where the runtime has one. Otherwise it opens in the system's
 * browser, which signs in to the gateway on its own.
 */
@Composable
actual fun PluginWebView(url: String, gateway: String, title: String, onClose: () -> Unit) {
    val web = rememberDesktopWeb()
    if (web != null) {
        web.PluginPage(url, gateway, title, onClose)
        return
    }
    val close = rememberUpdatedState(onClose)
    LaunchedEffect(url) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
        close.value()
    }
}
