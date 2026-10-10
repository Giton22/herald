package dev.hermeskotlin.ui.signin

import androidx.compose.runtime.Composable

/**
 * Opens the gateway's sign-in page for the browser sign-in. The app's loopback listener has to keep running
 * while the page is up, so iOS shows it in an in-app browser session rather than switching to Safari.
 * [onCancelled] runs when the person closes that session before the gateway sends them back.
 */
@Composable
internal expect fun rememberSignInBrowser(onCancelled: () -> Unit): (url: String) -> Unit
