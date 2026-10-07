package dev.hermeskotlin.ui.plugins

import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.auth.WebCookieJar
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.chat.BarButton
import org.koin.compose.koinInject

/**
 * The plugin page in a WebView: the gateway's own HTML and JS for it, loaded at the gateway's origin.
 * The app's session cookies go into the view's jar before the first request, so the gateway sees the
 * same signed-in session every other call carries. No chrome beyond close — the page is the page.
 */
@Composable
actual fun PluginWebView(url: String, title: String, onClose: () -> Unit) {
    val cookies = koinInject<WebCookieJar>()
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String?) {
                    loading = false
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    // Sub-resource misses still draw the page; only the page itself failing is a failure.
                    if (request.isForMainFrame) {
                        loading = false
                        failed = true
                    }
                }
            }
        }
    }
    LaunchedEffect(url) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        val values = runCatching { cookies.cookiesFor(url) }.getOrDefault(emptyList())
        if (values.isEmpty()) {
            webView.loadUrl(url)
        } else {
            // Load once the jar is in: every cookie's callback runs on this (main) thread, so the last
            // one releases the first navigation with the session already visible to the gateway.
            var pending = values.size
            values.forEach { value ->
                cookieManager.setCookie(url, value) {
                    if (--pending == 0) webView.loadUrl(url)
                }
            }
        }
        cookieManager.flush()
    }
    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }
    PlatformBackHandler(enabled = true) { onClose() }
    Column(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BarButton(Lucide.X, "Close the page", onClick = onClose)
            Text(
                title,
                style = Theme[typography][body].copy(fontWeight = FontWeight.SemiBold),
                color = Theme[colors][text],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (loading) Spinner(Modifier.size(16.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            if (failed) {
                Column(
                    Modifier.fillMaxSize().background(Theme[colors][background]).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    Text("Couldn't open the page.", style = Theme[typography][body], color = Theme[colors][textTertiary])
                    Button("Try again", onClick = {
                        failed = false
                        loading = true
                        webView.reload()
                    }, variant = ButtonVariant.Secondary)
                }
            }
        }
    }
}
