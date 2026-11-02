package dev.hermeskotlin.ui.plugins

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.view.MotionEvent
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat

import org.json.JSONObject
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive

internal const val HERALD_SCREEN_BRIDGE_NAME = "heraldScreenHost"
internal const val MAX_CLIPBOARD_BYTES = 64 * 1024
internal const val GESTURE_WINDOW_MS = 5_000L

/** Never run the Screen document without the native host: its browser fallback bypasses the toggle. */
internal fun mayLoadPluginDocument(screenPage: Boolean, nativeHostInstalled: Boolean): Boolean =
    !screenPage || nativeHostInstalled

/** Accept only the trusted screen document, with any gateway mount prefix intact. */
internal fun isHeraldScreenUrl(value: String, gateway: String): Boolean = try {
    val page = URI(value); val base = URI(gateway)
    val prefix = (base.rawPath ?: "").trimEnd('/')
    val trustedPath = "$prefix/api/plugins/herald-screen/index.html"
    isHeraldScreenOrigin(value, gateway) && page.rawPath == trustedPath &&
        page.rawQuery == null && page.rawFragment == null
} catch (_: Exception) { false }

internal fun isHeraldScreenOrigin(value: String, gateway: String): Boolean = try {
    val page = URI(value); val base = URI(gateway)
    base.scheme?.lowercase() in setOf("http", "https") && !page.host.isNullOrBlank() && !base.host.isNullOrBlank() &&
        page.scheme.equals(base.scheme, true) && page.host.equals(base.host, true) && effectivePort(page) == effectivePort(base) &&
        page.rawUserInfo == null && base.rawUserInfo == null
} catch (_: Exception) { false }

private fun effectivePort(uri: URI) = uri.port.takeIf { it >= 0 } ?: when (uri.scheme?.lowercase()) { "https" -> 443; "http" -> 80; else -> -1 }
internal data class ScreenRequest(val id: String, val operation: String, val text: String?)
internal fun parseScreenRequest(raw: String): ScreenRequest? {
    // JSON control-character escaping can expand otherwise valid plain text sixfold.
    if (raw.toByteArray(Charsets.UTF_8).size > MAX_CLIPBOARD_BYTES * 6 + 2048) return null
    return try {
        val json = Json.parseToJsonElement(raw).jsonObject
        if (json.keys.any { it !in setOf("id", "operation", "text") }) return null
        fun string(key: String) = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val id = string("id") ?: return null
        val operation = string("operation") ?: return null
        val text = string("text")
        if (id.isBlank() || id.length > 128 || operation !in setOf("clipboard.readText", "clipboard.writeText", "viewer.close") ||
            operation == "clipboard.writeText" && text == null || operation != "clipboard.writeText" && "text" in json ||
            text != null && text.toByteArray(Charsets.UTF_8).size > MAX_CLIPBOARD_BYTES) null
        else ScreenRequest(id, operation, text)
    } catch (_: Exception) { null }
}

internal class ScreenBridgeGate(private val gateway: String, private val clipboardEnabled: () -> Boolean = { true }) {
    private var active = true
    private var ready = false
    private var lastTouch: Long? = null
    fun navigationStarted() { ready = false; lastTouch = null }
    fun documentReady(url: String) { ready = active && isHeraldScreenUrl(url, gateway) }
    fun touch(url: String, now: Long) { if (active && ready && isHeraldScreenUrl(url, gateway)) lastTouch = now }
    fun admits(url: String, origin: String, mainFrame: Boolean) = active && ready && mainFrame &&
        isHeraldScreenUrl(url, gateway) && isHeraldScreenOrigin(origin, gateway)
    fun consumeClipboardGesture(now: Long): Boolean {
        val touch = lastTouch
        lastTouch = null
        return clipboardEnabled() && active && ready && touch != null && now >= touch && now - touch <= GESTURE_WINDOW_MS
    }
    fun dispose() { active = false; navigationStarted() }
}

internal class ScreenBridgeHandle(val navigationStarted: () -> Unit, val documentReady: (String) -> Unit, val dispose: () -> Unit)

/** Narrow bridge: explicit WebMessageListener, revalidating frame/origin/document and fresh tap for every call. */
internal object HeraldScreenBridge {
    fun install(webView: WebView, gateway: Uri, clipboard: ClipboardManager, clipboardEnabled: () -> Boolean, onClose: () -> Unit): ScreenBridgeHandle {
        val base = URI(gateway.toString())
        val origin = "${base.scheme}://${base.rawAuthority}"
        val gate = ScreenBridgeGate(gateway.toString(), clipboardEnabled)
        webView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) gate.touch(webView.url.orEmpty(), android.os.SystemClock.elapsedRealtime())
            false
        }
        WebViewCompat.addWebMessageListener(webView, HERALD_SCREEN_BRIDGE_NAME, setOf(origin)) { view, message, sourceOrigin, isMainFrame, reply ->
            if (!gate.admits(view.url.orEmpty(), sourceOrigin.toString(), isMainFrame)) {
                respond(reply, null, false); return@addWebMessageListener
            }
            val request = parseScreenRequest(message.data ?: "") ?: run { respond(reply, null, false); return@addWebMessageListener }
            if (request.operation == "viewer.close") {
                respond(reply, request.id, true)
                gate.dispose()
                onClose()
                return@addWebMessageListener
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (!gate.consumeClipboardGesture(now)) { respond(reply, request.id, false); return@addWebMessageListener }
            when (request.operation) {
                "clipboard.readText" -> {
                    // Never resolve URI/Intent clipboard items or read a content provider.
                    val value = try { clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() } catch (_: Exception) { null }
                    if (value == null || value.toByteArray(Charsets.UTF_8).size > MAX_CLIPBOARD_BYTES) respond(reply, request.id, false)
                    else respond(reply, request.id, true, value)
                }
                "clipboard.writeText" -> try {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", request.text!!))
                    respond(reply, request.id, true)
                } catch (_: Exception) { respond(reply, request.id, false) }
            }
        }
        return ScreenBridgeHandle(gate::navigationStarted, gate::documentReady) {
            gate.dispose()
            WebViewCompat.removeWebMessageListener(webView, HERALD_SCREEN_BRIDGE_NAME)
            webView.setOnTouchListener(null)
        }
    }

    private fun respond(reply: JavaScriptReplyProxy, id: String?, ok: Boolean, text: String? = null) {
        val result = JSONObject().put("id", id ?: "").put("ok", ok)
        if (ok && text != null) result.put("text", text)
        if (!ok) result.put("error", "request_denied")
        try { reply.postMessage(result.toString()) } catch (_: RuntimeException) { /* Document already closed. */ }
    }
}
