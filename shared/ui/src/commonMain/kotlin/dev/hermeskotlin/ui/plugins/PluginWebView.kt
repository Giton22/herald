package dev.hermeskotlin.ui.plugins

import androidx.compose.runtime.Composable

/**
 * A plugin's page from the gateway, full-screen: the same page the dashboard serves ([url] is on the
 * gateway's own origin), opened with the app's sign-in lent to it, so the gateway sees the session every
 * other call uses and nothing asks to sign in again. [gateway] is the gateway the app is signed in to,
 * whose session the app renews for the page. Viewing only — no controls beyond closing; managing plugins
 * (installing, switching them on) stays where it lives today.
 */
@Composable
expect fun PluginWebView(url: String, gateway: String, title: String, onClose: () -> Unit)
