package dev.hermeskotlin.ui.plugins

import androidx.compose.runtime.Composable

/**
 * A plugin's page from the gateway, full-screen: the same page the dashboard serves ([url] is on the
 * gateway's own origin), opened with the app's session cookies carried in, so the gateway sees the
 * session every other call uses and nothing asks to sign in again. Viewing only — no controls beyond
 * closing; managing plugins (installing, switching them on) stays where it lives today.
 */
@Composable
expect fun PluginWebView(url: String, title: String, onClose: () -> Unit)
