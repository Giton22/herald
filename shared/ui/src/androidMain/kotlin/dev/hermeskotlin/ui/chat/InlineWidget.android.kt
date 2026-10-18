package dev.hermeskotlin.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.hermeskotlin.designsystem.R
import java.io.ByteArrayInputStream
import kotlin.io.encoding.Base64
import kotlin.math.hypot

/** Untrusted widget HTML gets no gateway origin, cookies, storage, native bridge or network. */
@Composable
internal actual fun WidgetWebView(html: String, theme: WidgetTheme, name: String, modifier: Modifier, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    val failure = rememberUpdatedState(onFailure)
    val supported = remember { supportsWidgetIsolation(WebView.getCurrentWebViewPackage()?.versionName) }
    if (!supported) {
        LaunchedEffect(Unit) { failure.value("Update Android System WebView to run this widget.") }
        return
    }
    val font = remember { widgetFont(context) }
    val document = remember(html, theme, font) { widgetDocument(html, theme, font) }
    val currentDocument = rememberUpdatedState(document)
    val view = remember { createWidgetWebView(context, name, { currentDocument.value }) { failure.value("Couldn't display this widget.") } }
    DisposableEffect(view) {
        onDispose { view.stopLoading(); view.destroy() }
    }
    AndroidView(factory = { view }, modifier = modifier, update = {
        // By identity: the document is remembered, and comparing its text would read it all on each update.
        if (it.tag !== document) {
            it.tag = document
            it.loadUrl(WIDGET_URL)
        }
    })
}

private const val WIDGET_URL = "https://herald-widget.invalid/"

/** Geist as base64, which every widget's document embeds; read once, on the main thread like the widgets. */
private var cachedFont: String? = null

private fun widgetFont(context: Context): String = cachedFont
    ?: context.resources.openRawResource(R.font.geist_regular).use { Base64.encode(it.readBytes()) }.also { cachedFont = it }

/** The same restricted host for each widget; its document always arrives as app-supplied text. */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
internal fun createWidgetWebView(context: Context, name: String, document: () -> String, onFailure: () -> Unit): WebView =
    WebView(context).apply {
        // A wrap-content WebView gives percentage-height frames a zero-height document.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = name
        // Each drag goes to the widget or to the transcript, decided once from the widget's last report (as the title)
        // after a little movement, before the transcript's own touch slop: widgetKeepsDrag. A second finger (a pinch)
        // always stays in the widget. WebView owns clicks.
        var report = WidgetReport()
        var downX = 0f
        var downY = 0f
        var decided = false
        val decideAfter = ViewConfiguration.get(context).scaledTouchSlop / 2f
        val density = resources.displayMetrics.density
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    // A fast flick can pass the transcript's touch slop on its first move, before it's decided here.
                    decided = widgetKeepsTouch(report, downX / density, downY / density)
                    if (decided) view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    decided = true
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> if (!decided) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (hypot(dx, dy) >= decideAfter) {
                        decided = true
                        view.parent?.requestDisallowInterceptTouchEvent(widgetKeepsDrag(report, downX / density, downY / density, dx, dy))
                    }
                }
            }
            false
        }
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            // LocalDensity already includes Herald's and the system's text scales.
            textZoom = 100
            blockNetworkLoads = true
        }
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }

            override fun onReceivedTitle(view: WebView, title: String?) {
                widgetReport(title)?.let { report = it }
            }
        }
        webViewClient = object : WebViewClient() {
            // A reloaded document (a theme change) reports for itself; the last one's areas are gone.
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                report = WidgetReport()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                if (request.isForMainFrame && request.url.toString() == WIDGET_URL) {
                    // CSP and blockNetworkLoads don't cover WebRTC. This policy is inherited by srcdoc.
                    WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf("Connection-Allowlist" to "()"),
                        ByteArrayInputStream(document().encodeToByteArray()))
                } else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) view.post { onFailure() }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                view.post { onFailure() }
                return true
            }
        }
    }
