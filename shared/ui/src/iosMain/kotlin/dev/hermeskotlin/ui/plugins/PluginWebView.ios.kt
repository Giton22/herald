package dev.hermeskotlin.ui.plugins

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

// Plugin pages need a WKWebView with the app's sign-in; until then the page closes straight away.
@Composable
actual fun PluginWebView(url: String, gateway: String, title: String, onClose: () -> Unit) {
    LaunchedEffect(url) { onClose() }
}
