package dev.hermeskotlin.ui.platform

import androidx.compose.ui.platform.UriHandler
import platform.Foundation.NSURL
import platform.SafariServices.SFSafariViewController
import platform.UIKit.UIApplication

/** Web links open in Safari's in-app view, as Android opens a Custom Tab; any other link goes to its app. */
internal object SafariUriHandler : UriHandler {
    override fun openUri(uri: String) {
        val url = NSURL.URLWithString(uri) ?: return
        val web = url.scheme?.lowercase() in setOf("http", "https")
        if (web && present(SFSafariViewController(uRL = url))) return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }
}
