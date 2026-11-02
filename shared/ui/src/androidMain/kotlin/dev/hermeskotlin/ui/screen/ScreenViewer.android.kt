package dev.hermeskotlin.ui.screen

import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The bot's screen, in a WebView.
 *
 * The page is the app's own, from the assets, so it has no origin of its own: the gateway accepts a
 * display stream to a page without one and refuses one from anywhere else. The page opens the stream and
 * says how it goes by asking for a `herald://` address, which is caught here and answered with nothing.
 */
@Composable
actual fun ScreenViewer(
    streamUrl: String,
    viewOnly: Boolean,
    onHandle: (ScreenViewerHandle) -> Unit,
    onEvent: (ScreenViewerEvent) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val event by rememberUpdatedState(onEvent)
    val handle by rememberUpdatedState(onHandle)
    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            // The screen is the picture; a scrollbar or a selection would only be in the way.
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            // What the page says about itself: the screen's own log, in the app's log.
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.i(TAG, "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // The page cannot talk to the app any other way: it asks for an address of its own
                    // and the app reads the answer out of it.
                    val answer = request.url.toString().let(::screenEvent) ?: return false
                    event(answer)
                    return true
                }
            }
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    // The page's address is in the fragment: a file: address has no query of its own to carry it.
    val address = remember(streamUrl, viewOnly) {
        "file:///android_asset/screen-viewer.html#ws=" + Uri.encode(streamUrl) + "&viewOnly=" + if (viewOnly) "1" else "0"
    }
    LaunchedEffect(address) { webView.loadUrl(address) }
    LaunchedEffect(webView) {
        handle(
            object : ScreenViewerHandle {
                override fun viewOnly(value: Boolean) {
                    webView.evaluateJavascript("window.kylleneScreen && window.kylleneScreen.setViewOnly($value)", null)
                }

                override fun key(keysym: Int, down: Boolean) {
                    webView.evaluateJavascript("window.kylleneScreen && window.kylleneScreen.sendKey($keysym, $down)", null)
                }
            },
        )
    }
    // What the user may do changes with the lease, not with the page: the page is told.
    LaunchedEffect(webView, viewOnly) {
        webView.evaluateJavascript("window.kylleneScreen && window.kylleneScreen.setViewOnly($viewOnly)", null)
    }
    AndroidView(factory = { webView }, modifier = modifier)
}

/** The log the page's own words go to. */
private const val TAG = "BotScreen"
