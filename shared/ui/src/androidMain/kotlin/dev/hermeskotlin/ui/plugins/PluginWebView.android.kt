package dev.hermeskotlin.ui.plugins

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.hermeskotlin.core.auth.WebSession
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
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.compose.koinInject

/**
 * The plugin page in a WebView: the gateway's own HTML and JS for it, loaded at the gateway's origin.
 * The app lends the view its access token before the first request ([WebCookieJar]), so the gateway sees
 * the same signed-in session every other call carries, and lends a fresh one each time it renews. No
 * chrome beyond close — the page is the page.
 */
@Composable
actual fun PluginWebView(url: String, gateway: String, title: String, onClose: () -> Unit) {
    val cookies = koinInject<WebCookieJar>()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    // What the first load sent beyond cookies; a retry sends it again, as a plain reload wouldn't.
    var headers by remember { mutableStateOf(emptyMap<String, String>()) }
    val loginPath = remember(gateway) { (Uri.parse(gateway).path ?: "").trimEnd('/') + "/login" }

    /** Puts the app's current sign-in in the view's jar; [fresh] first drops whatever the jar held. */
    suspend fun lend(fresh: Boolean): WebSession {
        val session = unlessFailed { cookies.sessionFor(url) } ?: WebSession(emptyList(), emptyMap(), null)
        // A page closed meanwhile has cleared the jar: lend nothing after that.
        currentCoroutineContext().ensureActive()
        headers = session.headers
        CookieManager.getInstance().put(url, session.cookies, fresh)
        return session
    }

    /** Renews the app's session (only if it lapsed), then lends it and opens [target] again. */
    suspend fun renewAndReload(webView: WebView, target: String) {
        unlessFailed { cookies.renew(gateway, url) }
        lend(fresh = false)
        webView.loadUrl(target, headers)
    }

    // When the borrowed token ran out before the app lent a new one, the gateway sends the page to its
    // sign-in. Renew instead and open the page again; twice in a row means the app is signed out too.
    var lastRecovery by remember { mutableStateOf(0L) }
    fun recover(webView: WebView) {
        val now = System.currentTimeMillis()
        if (now - lastRecovery < RECOVERY_WINDOW_MS) {
            loading = false
            failed = true
            return
        }
        lastRecovery = now
        scope.launch { renewAndReload(webView, url) }
    }

    val webView = remember {
        WebView(context).apply {
            // AndroidView leaves a view at wrap_content, and a WebView that wraps its height gives the
            // page a zero-high viewport: every vh/dvh is 0 and the dashboard's h-dvh shell draws nothing.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // The gateway's own pages stay here; anywhere else is the browser's to show, not a
                    // page passed off under this plugin's title. Embeds (iframes) load where they are.
                    if (!request.isForMainFrame) return false
                    if (sameOrigin(request.url, Uri.parse(url))) {
                        if (request.url.path != loginPath) return false
                        recover(view)
                        return true
                    }
                    if (request.isRedirect) {
                        // The gateway sent the page elsewhere (a sign-in in front of it): nothing to show here.
                        loading = false
                        failed = true
                        return true
                    }
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (_: ActivityNotFoundException) {
                    }
                    return true
                }

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

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    // The gateway refusing the page (signed out, plugin gone) is a failure too, not a JSON body to show.
                    if (request.isForMainFrame && response.statusCode >= 400) {
                        loading = false
                        failed = true
                    }
                }
            }
        }
    }
    LaunchedEffect(url) {
        // The jar first, so the first navigation already carries the session.
        var session = lend(fresh = true)
        webView.loadUrl(url, headers)
        // Keep the borrowed token current: once it lapses, the app renews its own session and lends the
        // new token, before the page needs it again.
        while (true) {
            val expiresAt = session.expiresAt ?: break
            delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0) + RENEW_AFTER_MS)
            unlessFailed { cookies.renew(gateway, url) }
            session = lend(fresh = false)
            // Not renewed (offline, or signed out): try again in a while rather than at once.
            if ((session.expiresAt ?: 0) <= expiresAt) delay(RENEW_RETRY_MS)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.destroy()
            // The view's jar is on disk and outlives the page: leave no copy of the session there once it
            // closes. The app's own jar still has it, and the next page is lent it fresh.
            CookieManager.getInstance().removeAllCookies(null)
        }
    }
    // Back walks the page's own history first (the dashboard moves between its pages in place).
    PlatformBackHandler(enabled = true) { if (webView.canGoBack()) webView.goBack() else onClose() }
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
                        val target = webView.url?.takeUnless { it == "about:blank" } ?: url
                        scope.launch { renewAndReload(webView, target) }
                    }, variant = ButtonVariant.Secondary)
                }
            }
        }
    }
}

/** Past the lent token's expiry, so the app's own jar has dropped it too and the next call renews. */
private const val RENEW_AFTER_MS = 2_000L
private const val RENEW_RETRY_MS = 60_000L
/** A second trip to the sign-in this soon after a renewal means the renewal didn't take. */
private const val RECOVERY_WINDOW_MS = 30_000L

/** [block]'s result, or null when it failed; a cancellation still cancels (a closed page must stop). */
private suspend fun <T> unlessFailed(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** Sets [values] for [url], after dropping everything in the jar when [fresh]; returns once they're in. */
private suspend fun CookieManager.put(url: String, values: List<String>, fresh: Boolean) {
    setAcceptCookie(true)
    if (fresh) suspendCancellableCoroutine { done -> removeAllCookies { done.resume(Unit) } }
    values.forEach { value -> suspendCancellableCoroutine { done -> setCookie(url, value) { done.resume(Unit) } } }
    flush()
}

/** Same scheme, host and port: the gateway's origin, which the page may move around in. */
private fun sameOrigin(a: Uri, b: Uri): Boolean =
    a.scheme.equals(b.scheme, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) && a.effectivePort == b.effectivePort

/** The port, with the scheme's default filled in: `https://host` and `https://host:443` are one origin. */
private val Uri.effectivePort: Int
    get() = port.takeIf { it != -1 } ?: when (scheme?.lowercase()) {
        "https" -> 443
        "http" -> 80
        else -> -1
    }
