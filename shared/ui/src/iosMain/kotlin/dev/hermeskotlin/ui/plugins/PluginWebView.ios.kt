@file:OptIn(ExperimentalForeignApi::class)

package dev.hermeskotlin.ui.plugins

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.compose.koinInject
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSError
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.setValue
import platform.UIKit.UIApplication
import platform.WebKit.WKHTTPCookieStore
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationResponse
import platform.WebKit.WKNavigationResponsePolicy
import platform.WebKit.WKNavigationTypeLinkActivated
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.time.Clock

/**
 * The plugin page in a WKWebView, as on Android: the gateway's own page at the gateway's origin, lent the app's
 * access token before the first request ([WebCookieJar]) and a fresh one each time it renews. The view keeps its
 * cookies in memory only, so no copy of the session is left on disk when the page closes.
 */
@Composable
actual fun PluginWebView(url: String, gateway: String, title: String, onClose: () -> Unit) {
    val cookies = koinInject<WebCookieJar>()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    // What the first load sent beyond cookies; a retry sends it again, as a plain reload wouldn't.
    var headers by remember { mutableStateOf(emptyMap<String, String>()) }
    val pageUrl = remember(url) { NSURL.URLWithString(url) }
    val loginPath = remember(gateway) { (NSURL.URLWithString(gateway)?.path ?: "").trimEnd('/') + "/login" }

    val webView = remember {
        val config = WKWebViewConfiguration().apply { websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore() }
        WKWebView(frame = CGRectZero.readValue(), configuration = config)
    }

    /** Puts the app's current sign-in in the view's jar; [fresh] first drops whatever the jar held. */
    suspend fun lend(fresh: Boolean): WebSession {
        val session = unlessFailed { cookies.sessionFor(url) } ?: WebSession(emptyList(), emptyMap(), null)
        currentCoroutineContext().ensureActive()
        headers = session.headers
        webView.configuration.websiteDataStore.httpCookieStore.put(pageUrl, session.cookies, fresh)
        return session
    }

    fun load(target: String) {
        val request = NSMutableURLRequest(uRL = NSURL.URLWithString(target) ?: return)
        headers.forEach { (name, value) -> request.setValue(value, forHTTPHeaderField = name) }
        webView.loadRequest(request)
    }

    /** Renews the app's session (only if it lapsed), then lends it and opens [target] again. */
    suspend fun renewAndReload(target: String) {
        unlessFailed { cookies.renew(gateway, url) }
        lend(fresh = false)
        load(target)
    }

    // When the borrowed token ran out before the app lent a new one, the gateway sends the page to its sign-in.
    // Renew instead and open the page again; twice in a row means the app is signed out too.
    var lastRecovery by remember { mutableStateOf(0L) }
    val navigation = remember {
        PageNavigation(
            page = pageUrl,
            loginPath = loginPath,
            onLogin = {
                val now = Clock.System.now().toEpochMilliseconds()
                if (now - lastRecovery < RECOVERY_WINDOW_MS) {
                    loading = false
                    failed = true
                } else {
                    lastRecovery = now
                    scope.launch { renewAndReload(url) }
                }
            },
            onFinished = { loading = false },
            onFailed = {
                loading = false
                failed = true
            },
        )
    }
    webView.navigationDelegate = navigation

    LaunchedEffect(url) {
        // The jar first, so the first navigation already carries the session.
        var session = lend(fresh = true)
        load(url)
        // Keep the borrowed token current: once it lapses, the app renews its own session and lends the new
        // token, before the page needs it again.
        while (true) {
            val expiresAt = session.expiresAt ?: break
            delay((expiresAt - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0) + RENEW_AFTER_MS)
            unlessFailed { cookies.renew(gateway, url) }
            session = lend(fresh = false)
            // Not renewed (offline, or signed out): try again in a while rather than at once.
            if ((session.expiresAt ?: 0) <= expiresAt) delay(RENEW_RETRY_MS)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.navigationDelegate = null
        }
    }
    // Back (the edge swipe) walks the page's own history first.
    PlatformBackHandler(enabled = true) { if (webView.canGoBack) webView.goBack() else onClose() }
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
            UIKitView(factory = { webView }, modifier = Modifier.fillMaxSize())
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
                        val target = webView.URL?.absoluteString?.takeUnless { it == "about:blank" } ?: url
                        scope.launch { renewAndReload(target) }
                    }, variant = ButtonVariant.Secondary)
                }
            }
        }
    }
}

/**
 * Keeps the page on the gateway: its own pages load here (its sign-in page means the lent token lapsed), a link
 * elsewhere opens in Safari, and a redirect elsewhere (a sign-in in front of the gateway) or an error page fails.
 */
private class PageNavigation(
    private val page: NSURL?,
    private val loginPath: String,
    private val onLogin: () -> Unit,
    private val onFinished: () -> Unit,
    private val onFailed: () -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val target = decidePolicyForNavigationAction.request.URL
        // Embeds (iframes) load where they are.
        val mainFrame = decidePolicyForNavigationAction.targetFrame?.mainFrame ?: true
        when {
            !mainFrame || target == null -> decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            sameOrigin(target, page) -> if (target.path == loginPath) {
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                onLogin()
            } else {
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            }
            decidePolicyForNavigationAction.navigationType == WKNavigationTypeLinkActivated -> {
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                UIApplication.sharedApplication.openURL(target, options = emptyMap<Any?, Any>(), completionHandler = null)
            }
            else -> {
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                onFailed()
            }
        }
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationResponse: WKNavigationResponse,
        decisionHandler: (WKNavigationResponsePolicy) -> Unit,
    ) {
        // The gateway refusing the page (signed out, plugin gone) is a failure, not a JSON body to show.
        val status = (decidePolicyForNavigationResponse.response as? NSHTTPURLResponse)?.statusCode ?: 0
        if (decidePolicyForNavigationResponse.forMainFrame && status >= 400) {
            decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel)
            onFailed()
        } else {
            decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow)
        }
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) = onFinished()

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) = failed(withError)

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) = failed(withError)

    private fun failed(error: NSError) {
        // A navigation this delegate cancelled (the sign-in, a link to Safari) isn't the page failing.
        if (error.domain == "NSURLErrorDomain" && error.code == NSURL_ERROR_CANCELLED) return
        if (error.domain == "WebKitErrorDomain" && error.code == FRAME_LOAD_INTERRUPTED) return
        onFailed()
    }
}

/** Same scheme, host and port: the gateway's origin, which the page may move around in. */
private fun sameOrigin(a: NSURL, b: NSURL?): Boolean =
    b != null && a.scheme.equals(b.scheme, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) && a.effectivePort == b.effectivePort

/** The port, with the scheme's default filled in: `https://host` and `https://host:443` are one origin. */
private val NSURL.effectivePort: Long
    get() = port?.longValue ?: when (scheme?.lowercase()) {
        "https" -> 443
        "http" -> 80
        else -> -1
    }

/** Sets [values] (Set-Cookie lines) for [url], after dropping everything in the jar when [fresh]. */
private suspend fun WKHTTPCookieStore.put(url: NSURL?, values: List<String>, fresh: Boolean) {
    if (fresh) {
        val all = suspendCancellableCoroutine { done -> getAllCookies { done.resume(it.orEmpty().filterIsInstance<NSHTTPCookie>()) } }
        all.forEach { cookie -> suspendCancellableCoroutine { done -> deleteCookie(cookie) { done.resume(Unit) } } }
    }
    url ?: return
    values.flatMap { value ->
        NSHTTPCookie.cookiesWithResponseHeaderFields(mapOf<Any?, Any?>("Set-Cookie" to value), forURL = url).filterIsInstance<NSHTTPCookie>()
    }.forEach { cookie -> suspendCancellableCoroutine { done -> setCookie(cookie) { done.resume(Unit) } } }
}

/** [block]'s result, or null when it failed; a cancellation still cancels (a closed page must stop). */
private suspend fun <T> unlessFailed(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** Past the lent token's expiry, so the app's own jar has dropped it too and the next call renews. */
private const val RENEW_AFTER_MS = 2_000L
private const val RENEW_RETRY_MS = 60_000L
/** A second trip to the sign-in this soon after a renewal means the renewal didn't take. */
private const val RECOVERY_WINDOW_MS = 30_000L
private const val NSURL_ERROR_CANCELLED = -999L
/** WebKit's "frame load interrupted": a navigation the policy cancelled. */
private const val FRAME_LOAD_INTERRUPTED = 102L
