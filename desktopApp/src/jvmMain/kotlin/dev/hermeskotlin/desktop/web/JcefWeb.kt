package dev.hermeskotlin.desktop.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.auth.WebCookieJar
import dev.hermeskotlin.core.auth.WebSession
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.DesktopWeb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefFileDialogCallback
import org.cef.callback.CefJSDialogCallback
import org.cef.callback.CefMediaAccessCallback
import org.cef.callback.CefMenuModel
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.handler.CefDialogHandler
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefJSDialogHandler
import org.cef.handler.CefJSDialogHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.awt.Desktop
import java.net.URI
import java.util.Date
import java.util.Vector

/** Widgets and plugin pages in the Chromium the JetBrains Runtime carries; see [Jcef]. */
internal class JcefWeb(private val cookies: WebCookieJar, private val gateways: GatewayRepository) : DesktopWeb {

    private suspend fun app(): CefApp? {
        val saved = gateways.all().gateways.map { it.url }
        return Jcef.app { saved }
    }

    @Composable
    override fun Widget(document: String, name: String, modifier: Modifier, keepsWheel: (title: String?, down: Boolean) -> Boolean, onFailure: (String) -> Unit) {
        val failure by rememberUpdatedState(onFailure)
        val app by produceState<CefApp?>(null) {
            value = app()
            if (value == null) failure("Couldn't start the browser widgets show in.")
        }
        val cef = app
        if (cef == null) {
            Box(modifier)
            return
        }
        val content by rememberUpdatedState(document)
        val client = remember(cef) { cef.createClient() }
        val page = remember(client) {
            client.lockDown(document = { content }, onFailure = { failure("Couldn't display this widget.") })
            OffscreenBrowser(client, WIDGET_URL, transparent = true).also { page ->
                client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
                    override fun onTitleChange(browser: CefBrowser, title: String?) {
                        page.title = title
                    }
                })
            }
        }
        DisposableEffect(client) { onDispose { client.dispose() } }
        // A new document (a theme change) is loaded in place.
        var shown by remember(page) { mutableStateOf(document) }
        LaunchedEffect(document) {
            if (shown !== document) {
                shown = document
                page.browser.loadURL(WIDGET_URL)
            }
        }
        BrowserSurface(page, modifier) { down -> keepsWheel(page.title, down) }
    }

    @Composable
    override fun PluginPage(url: String, gateway: String, title: String, onClose: () -> Unit) {
        val close by rememberUpdatedState(onClose)
        val app by produceState<CefApp?>(null) {
            value = app()?.takeIf { Jcef.reaches(url) }
            if (value == null) {
                // Chromium couldn't start, or can't reach this gateway (saved after it started): the page opens where
                // it did before, in the system's browser.
                runCatching { Desktop.getDesktop().browse(URI(url)) }
                close()
            }
        }
        var loading by remember { mutableStateOf(true) }
        val cef = app
        Column(Modifier.fillMaxSize().background(Theme[colors][background])) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(Lucide.X, contentDescription = "Close the page", onClick = onClose)
                Text(
                    title,
                    style = Theme[typography][body].copy(fontWeight = FontWeight.SemiBold),
                    color = Theme[colors][textColor],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (loading) Spinner(Modifier.size(16.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (cef != null) GatewayPage(cef, url, gateway, onLoading = { loading = it })
            }
        }
    }

    /** The page itself, signed in with the app's session, with Try again over it when it fails. */
    @Composable
    private fun GatewayPage(cef: CefApp, url: String, gateway: String, onLoading: (Boolean) -> Unit) {
        val scope = rememberCoroutineScope()
        var failed by remember { mutableStateOf(false) }
        val reportLoading by rememberUpdatedState(onLoading)
        fun loaded() = reportLoading(false)
        fun fail(value: Boolean) {
            failed = value
            reportLoading(!value)
        }
        val origin = remember(url) { parseUrl(url) }
        val loginPath = remember(gateway) { (parseUrl(gateway)?.path ?: "").trimEnd('/') + "/login" }
        fun ownPage(address: String?): Boolean {
            val target = address?.let(::parseUrl) ?: return false
            return origin != null && sameOrigin(target, origin)
        }
        // What the gateway needs beyond cookies (an access service's headers): on the page's own loads, as on the
        // phone, and never on a request anywhere else.
        var headers by remember(url) { mutableStateOf(emptyMap<String, String>()) }
        var lastRecovery by remember(url) { mutableLongStateOf(0L) }
        // A new client for another page: its handlers check against that page's gateway.
        val client = remember(cef, url) { cef.createClient() }
        val page = remember(client) { OffscreenBrowser(client, "about:blank", transparent = false) }

        suspend fun lend(fresh: Boolean): WebSession {
            val session = unlessFailed { cookies.sessionFor(url) } ?: WebSession(emptyList(), emptyMap(), null)
            currentCoroutineContext().ensureActive()
            headers = session.headers
            CefCookieManager.getGlobalManager().put(url, session.cookies, fresh)
            return session
        }

        suspend fun renewAndReload(target: String) {
            unlessFailed { cookies.renew(gateway, url) }
            lend(fresh = false)
            page.browser.loadURL(target)
        }

        // When the lent token ran out before the app lent a new one, the gateway sends the page to its sign-in.
        // Renew instead and open the page again; twice in a row means the app is signed out too.
        fun recover() {
            val now = System.currentTimeMillis()
            if (now - lastRecovery < RECOVERY_WINDOW_MS) {
                fail(true)
                return
            }
            lastRecovery = now
            scope.launch { renewAndReload(url) }
        }

        DisposableEffect(client) {
            client.addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(browser: CefBrowser, frame: CefFrame, request: CefRequest, userGesture: Boolean, isRedirect: Boolean): Boolean {
                    // The gateway's own pages stay here; anywhere else is the browser's to show, not a page passed off
                    // under this plugin's title. Embeds (iframes) load where they are.
                    if (!frame.isMain) return false
                    if (request.url == "about:blank") return false
                    val target = parseUrl(request.url) ?: return true
                    if (origin != null && sameOrigin(target, origin)) {
                        if (target.path != loginPath) return false
                        scope.launch { recover() }
                        return true
                    }
                    if (isRedirect) {
                        // The gateway sent the page elsewhere (a sign-in in front of it): nothing to show here.
                        scope.launch { fail(true) }
                        return true
                    }
                    // Only a link the user followed, and only a web or mail link: a page can't open apps or
                    // Windows' own schemes on its own.
                    if (userGesture) openOutside(target)
                    return true
                }

                override fun getResourceRequestHandler(
                    browser: CefBrowser, frame: CefFrame, request: CefRequest, isNavigation: Boolean, isDownload: Boolean,
                    requestInitiator: String?, disableDefaultHandling: BoolRef,
                ): CefResourceRequestHandler = object : CefResourceRequestHandlerAdapter() {
                    override fun onBeforeResourceLoad(browser: CefBrowser, frame: CefFrame, request: CefRequest): Boolean {
                        val target = parseUrl(request.url)
                        val own = target != null && origin != null && sameOrigin(target, origin)
                        if (own && frame.isMain && request.resourceType == CefRequest.ResourceType.RT_MAIN_FRAME) {
                            headers.forEach { (name, value) -> request.setHeaderByName(name, value, true) }
                        } else if (headers.isNotEmpty()) {
                            // A redirect keeps the headers it started with: off the gateway, they come off.
                            val all = HashMap<String, String>().also { request.getHeaderMap(it) }
                            if (all.keys.removeAll { name -> headers.keys.any { it.equals(name, ignoreCase = true) } }) {
                                request.setHeaderMap(all)
                            }
                        }
                        return false
                    }

                    override fun onResourceResponse(browser: CefBrowser, frame: CefFrame, request: CefRequest, response: CefResponse): Boolean {
                        // The gateway refusing the page (signed out, plugin gone) is a failure, not a JSON body to show.
                        if (frame.isMain && request.resourceType == CefRequest.ResourceType.RT_MAIN_FRAME && response.status >= 400) {
                            scope.launch { fail(true) }
                        }
                        return false
                    }
                }
            })
            client.addLoadHandler(object : CefLoadHandlerAdapter() {
                // The plugin's page alone, as early as the document takes it and once more when it's in.
                override fun onLoadStart(browser: CefBrowser, frame: CefFrame, transitionType: CefRequest.TransitionType) {
                    if (frame.isMain && ownPage(frame.url)) frame.executeJavaScript(PLUGIN_ONLY, frame.url, 0)
                }

                override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (frame.isMain && ownPage(frame.url)) frame.executeJavaScript(PLUGIN_ONLY, frame.url, 0)
                    if (frame.isMain && browser.url != "about:blank") scope.launch { loaded() }
                }

                override fun onLoadError(browser: CefBrowser, frame: CefFrame, errorCode: CefLoadHandler.ErrorCode, errorText: String?, failedUrl: String?) {
                    // A load this page called off itself (a sign-in it renews past instead) isn't a failure.
                    if (frame.isMain && errorCode != CefLoadHandler.ErrorCode.ERR_ABORTED) scope.launch { fail(true) }
                }
            })
            client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
                // A link that opens a window opens in the system's browser.
                override fun onBeforePopup(browser: CefBrowser, frame: CefFrame, targetUrl: String?, targetFrameName: String?): Boolean {
                    targetUrl?.let(::parseUrl)?.let(::openOutside)
                    return true
                }
            })
            client.addContextMenuHandler(NoContextMenu)
            client.addDialogHandler(NoFileDialogs)
            onDispose {
                // Chromium's jar is on disk and outlives the page: leave no copy of the session there once it closes.
                // The app's own jar still has it, and the next page is lent it fresh. Only this gateway's: a page
                // open in another window keeps its own.
                CefCookieManager.getGlobalManager().deleteCookies(url, "")
                client.dispose()
            }
        }
        LaunchedEffect(url) {
            // The jar first, so the first navigation already carries the session.
            var session = lend(fresh = true)
            page.browser.loadURL(url)
            // Keep the lent token current: once it lapses, the app renews its own session and lends the new one.
            while (true) {
                val expiresAt = session.expiresAt ?: break
                delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0) + RENEW_AFTER_MS)
                unlessFailed { cookies.renew(gateway, url) }
                session = lend(fresh = false)
                if ((session.expiresAt ?: 0) <= expiresAt) delay(RENEW_RETRY_MS)
            }
        }
        Box(Modifier.fillMaxSize()) {
            BrowserSurface(page, Modifier.fillMaxSize())
            if (failed) {
                Column(
                    Modifier.fillMaxSize().background(Theme[colors][background]).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    Text("Couldn't open the page.", style = Theme[typography][body], color = Theme[colors][textTertiary])
                    Button("Try again", onClick = {
                        fail(false)
                        val target = page.browser.url?.takeUnless { it == "about:blank" } ?: url
                        scope.launch { renewAndReload(target) }
                    }, variant = ButtonVariant.Secondary)
                }
            }
        }
    }
}

/** Where a widget's document is served from: a name no network has, which the client answers itself. */
private const val WIDGET_URL = "https://herald-widget.invalid/"

/**
 * A plugin's page is a route inside the whole dashboard. This keeps only the page: no sidebar (the dashboard's
 * navigation, settings and sign-out), no phone-width header with its menu, and no page title, which the app's own bar
 * already shows; a title row that also holds the page's tools stays, without the title. The hooks are the dashboard's
 * own ids and roles, the same since its first plugin pages; a dashboard without them shows as it did.
 */
private const val PLUGIN_ONLY = """(function () {
  var id = 'herald-plugin-only';
  var css = '#app-sidebar, header:has(button[aria-controls="app-sidebar"]),'
    + ' header:has(button[aria-controls="app-sidebar"]) ~ div[aria-hidden="true"].h-14,'
    + ' header[role="banner"]:has(> div > div:only-child > h1:only-child),'
    + ' header[role="banner"] h1 { display: none !important; }';
  function add() {
    if (document.getElementById(id)) return;
    var parent = document.head || document.documentElement;
    if (!parent) return;
    var style = document.createElement('style');
    style.id = id;
    style.textContent = css;
    parent.appendChild(style);
  }
  add();
  document.addEventListener('DOMContentLoaded', add);
})();"""

/** Past the lent token's expiry, so the app's own jar has dropped it too and the next call renews. */
private const val RENEW_AFTER_MS = 2_000L
private const val RENEW_RETRY_MS = 60_000L

/** A second trip to the sign-in this soon after a renewal means the renewal didn't take. */
private const val RECOVERY_WINDOW_MS = 30_000L

/**
 * A widget's client: the only thing it loads is [document] at [WIDGET_URL]; anything else, from the network to
 * another page, a new window, the camera or a file, is refused. Inline data (pictures and the font) still loads.
 */
private fun CefClient.lockDown(document: () -> String, onFailure: () -> Unit) {
    addRequestHandler(object : CefRequestHandlerAdapter() {
        override fun onBeforeBrowse(browser: CefBrowser, frame: CefFrame, request: CefRequest, userGesture: Boolean, isRedirect: Boolean): Boolean =
            !(request.url == WIDGET_URL || request.url.startsWith("about:"))

        override fun getResourceRequestHandler(
            browser: CefBrowser, frame: CefFrame, request: CefRequest, isNavigation: Boolean, isDownload: Boolean,
            requestInitiator: String?, disableDefaultHandling: BoolRef,
        ): CefResourceRequestHandler = object : CefResourceRequestHandlerAdapter() {
            override fun onBeforeResourceLoad(browser: CefBrowser, frame: CefFrame, request: CefRequest): Boolean {
                val url = request.url
                return !(url == WIDGET_URL || url.startsWith("data:") || url.startsWith("blob:") || url.startsWith("about:"))
            }

            override fun getResourceHandler(browser: CefBrowser, frame: CefFrame, request: CefRequest): CefResourceHandler? =
                if (request.url == WIDGET_URL) TextResource(document()) else null
        }

        override fun onRenderProcessTerminated(browser: CefBrowser, status: CefRequestHandler.TerminationStatus, errorCode: Int, errorString: String?) {
            onFailure()
        }
    })
    addLoadHandler(object : CefLoadHandlerAdapter() {
        override fun onLoadError(browser: CefBrowser, frame: CefFrame, errorCode: CefLoadHandler.ErrorCode, errorText: String?, failedUrl: String?) {
            if (frame.isMain && failedUrl == WIDGET_URL && errorCode != CefLoadHandler.ErrorCode.ERR_ABORTED) onFailure()
        }
    })
    addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
        override fun onBeforePopup(browser: CefBrowser, frame: CefFrame, targetUrl: String?, targetFrameName: String?): Boolean = true
    })
    addPermissionHandler { _, _, _, _, callback ->
        callback.Continue(0)
        true
    }
    addJSDialogHandler(object : CefJSDialogHandlerAdapter() {
        override fun onJSDialog(
            browser: CefBrowser, originUrl: String?, dialogType: CefJSDialogHandler.JSDialogType, messageText: String?,
            defaultPromptText: String?, callback: CefJSDialogCallback, suppressMessage: BoolRef,
        ): Boolean {
            suppressMessage.set(true)
            return false
        }
    })
    addContextMenuHandler(NoContextMenu)
    addDialogHandler(NoFileDialogs)
}

/** Chromium's own right-click menu can't show off screen; there's none. */
private object NoContextMenu : CefContextMenuHandlerAdapter() {
    override fun onBeforeContextMenu(browser: CefBrowser, frame: CefFrame, params: CefContextMenuParams, model: CefMenuModel) {
        model.clear()
    }
}

/** No file pickers from a page. */
private object NoFileDialogs : CefDialogHandler {
    override fun onFileDialog(
        browser: CefBrowser, mode: CefDialogHandler.FileDialogMode, title: String?, defaultFilePath: String?,
        acceptFilters: Vector<String>?, acceptExtensions: Vector<String>?, acceptDescriptions: Vector<String>?,
        callback: CefFileDialogCallback,
    ): Boolean {
        callback.Cancel()
        return true
    }
}

/** Serves [text] as an HTML page, from memory. */
private class TextResource(text: String) : CefResourceHandlerAdapter() {
    private val bytes = text.encodeToByteArray()
    private var at = 0

    override fun open(request: CefRequest, handleRequest: BoolRef, callback: CefCallback): Boolean {
        handleRequest.set(true)
        return true
    }

    override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef) {
        response.status = 200
        response.mimeType = "text/html"
        response.setHeaderByName("Content-Type", "text/html; charset=utf-8", true)
        // As on the phone: the request checks and CSP don't cover WebRTC; an empty allowlist does, srcdoc included.
        response.setHeaderByName("Connection-Allowlist", "()", true)
        responseLength.set(bytes.size)
    }

    override fun readResponse(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefCallback): Boolean {
        if (at >= bytes.size) {
            bytesRead.set(0)
            return false
        }
        val count = minOf(bytesToRead, bytes.size - at)
        bytes.copyInto(dataOut, 0, at, at + count)
        at += count
        bytesRead.set(count)
        return true
    }
}

/** [block]'s result, or null when it failed; a cancellation still cancels (a closed page must stop). */
private suspend fun <T> unlessFailed(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** Sets the `Set-Cookie` [values] for [url], after dropping what the jar had for it when [fresh]. */
private suspend fun CefCookieManager.put(url: String, values: List<String>, fresh: Boolean) {
    if (fresh) deleteCookies(url, "")
    values.mapNotNull(::parseSetCookie).forEach { setCookie(url, it) }
    val done = CompletableDeferred<Unit>()
    if (!flushStore { done.complete(Unit) }) done.complete(Unit)
    done.await()
}

/** A `Set-Cookie` value as Chromium's cookie; one without a domain stays the host's alone. */
internal fun parseSetCookie(value: String): CefCookie? {
    val parts = value.split(';').map { it.trim() }
    val (name, cookieValue) = parts.firstOrNull()?.split('=', limit = 2)?.takeIf { it.size == 2 && it[0].isNotBlank() } ?: return null
    var domain = ""
    var path = "/"
    var secure = false
    var httpOnly = false
    var expires: Date? = null
    parts.drop(1).forEach { attribute ->
        val key = attribute.substringBefore('=').trim().lowercase()
        val argument = attribute.substringAfter('=', "").trim()
        when (key) {
            "domain" -> domain = argument.removePrefix(".").let { if (it.isEmpty()) "" else ".$it" }
            "path" -> path = argument.ifEmpty { "/" }
            "secure" -> secure = true
            "httponly" -> httpOnly = true
            "max-age" -> argument.toLongOrNull()?.let { expires = Date(System.currentTimeMillis() + it * 1000) }
        }
    }
    val now = Date()
    return CefCookie(name.trim(), cookieValue.trim(), domain, path, secure, httpOnly, now, now, expires != null, expires)
}

/**
 * Opens [target] in the system's browser or mail app; anything else (`file:`, `ms-settings:`, an app's own scheme)
 * a page doesn't get to open.
 */
private fun openOutside(target: URI) {
    if (target.scheme?.lowercase() !in OUTSIDE_SCHEMES) return
    runCatching { Desktop.getDesktop().browse(target) }
}

private val OUTSIDE_SCHEMES = setOf("http", "https", "mailto")

/** Same scheme, host and port: the gateway's origin, which the page may move around in. */
internal fun sameOrigin(a: URI, b: URI): Boolean =
    a.scheme.equals(b.scheme, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) && a.effectivePort == b.effectivePort

/** The port, with the scheme's default filled in: `https://host` and `https://host:443` are one origin. */
private val URI.effectivePort: Int
    get() = port.takeIf { it != -1 } ?: when (scheme?.lowercase()) {
        "https" -> 443
        "http" -> 80
        else -> -1
    }
