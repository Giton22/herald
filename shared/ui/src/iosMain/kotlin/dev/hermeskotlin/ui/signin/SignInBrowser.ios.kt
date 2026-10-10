package dev.hermeskotlin.ui.signin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.hermeskotlin.ui.platform.keyWindow
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorCodeCanceledLogin
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorDomain
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.UIKit.UIWindow
import platform.darwin.NSObject

@Composable
internal actual fun rememberSignInBrowser(onCancelled: () -> Unit): (url: String) -> Unit {
    val cancelled by rememberUpdatedState(onCancelled)
    val browser = remember { SignInSession { cancelled() } }
    DisposableEffect(browser) { onDispose { browser.close() } }
    return browser::open
}

/**
 * One sign-in page at a time in an [ASWebAuthenticationSession]. It ends when the page goes to the app's own
 * link (the loopback listener's answer once it has the code), or when the person closes it.
 */
private class SignInSession(private val onCancelled: () -> Unit) {
    private var session: ASWebAuthenticationSession? = null
    private val anchor = Anchor()

    fun open(url: String) {
        session?.cancel()
        val target = NSURL.URLWithString(url) ?: return
        val scheme = NSBundle.mainBundle.bundleIdentifier ?: return
        val started = ASWebAuthenticationSession(uRL = target, callbackURLScheme = scheme) { _, error ->
            session = null
            val closed = error != null && error.domain == ASWebAuthenticationSessionErrorDomain && error.code == ASWebAuthenticationSessionErrorCodeCanceledLogin
            if (closed) onCancelled()
        }
        // Shares cookies with Safari, so an identity provider the person is signed in to doesn't ask again.
        started.prefersEphemeralWebBrowserSession = false
        started.presentationContextProvider = anchor
        session = started
        if (!started.start()) {
            session = null
            onCancelled()
        }
    }

    fun close() {
        session?.cancel()
        session = null
    }

    private class Anchor : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): UIWindow =
            keyWindow() ?: UIWindow()
    }
}
