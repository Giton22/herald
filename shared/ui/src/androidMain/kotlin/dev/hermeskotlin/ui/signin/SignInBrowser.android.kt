package dev.hermeskotlin.ui.signin

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler

// The system browser; the sign-in screen's Cancel stops the wait.
@Composable
internal actual fun rememberSignInBrowser(onCancelled: () -> Unit): (url: String) -> Unit {
    val uriHandler = LocalUriHandler.current
    return { uriHandler.openUri(it) }
}
