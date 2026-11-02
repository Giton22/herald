package dev.hermeskotlin.ui.screen

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.MotionEvent
import android.view.View
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
    // The stream in hand now: a viewer that was replaced says nothing about this one.
    val current = rememberUpdatedState(streamUrl)
    // A viewer per stream: the page of one stream is not the page of another, and what it says is its own.
    val webView = remember(streamUrl) {
        ScreenWebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            // The screen is the picture; a scrollbar or a selection would only be in the way.
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            // A drag across the screen moves the picture, and the browser may not take the swipe for
            // itself: that is how it navigates back.
            overScrollMode = View.OVER_SCROLL_NEVER
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
                    // What a viewer that was replaced says is about the screen it was watching.
                    screenEventAbout(streamUrl, current.value, answer)?.let { event(it) }
                    return true
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    // There is nowhere to go back to: the page is the app's own. A history would let the
                    // browser take a swipe across the screen for a back gesture, and the user's finger
                    // would never reach the picture.
                    view.clearHistory()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    // The page that is there now is the only one: a swipe across it is the user's, not
                    // the browser's way back.
                    view.clearHistory()
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
    // The lease is not in it: the page is told when the lease changes, and a page that was loaded again
    // would spend the ticket a second time.
    val address = remember(streamUrl) {
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

                override fun setZoom(factor: Float) {
                    webView.evaluateJavascript("window.kylleneScreen && window.kylleneScreen.setZoom($factor)", null)
                }
            },
        )
    }
    AndroidView(factory = { webView }, modifier = modifier)
}

/** The log the page's own words go to. */
private const val TAG = "BotScreen"

/**
 * The page, in a browser that keeps its hands off the user's finger.
 *
 * A drag across the screen moves the picture, and neither the browser nor anything around it may
 * take that gesture for itself: the browser would navigate back, and the picture would never move.
 */
private class ScreenWebView(context: Context) : WebView(context) {
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // The gesture is the picture's, and no parent may take it away from it.
        requestDisallowInterceptTouchEvent(true)
        return super.onTouchEvent(event)
    }
}
