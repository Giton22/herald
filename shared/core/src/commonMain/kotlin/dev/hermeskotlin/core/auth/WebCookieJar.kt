package dev.hermeskotlin.core.auth

import dev.hermeskotlin.core.gateway.AccessTokens
import dev.hermeskotlin.core.gateway.CF_ACCESS_CLIENT_ID
import dev.hermeskotlin.core.gateway.CF_ACCESS_CLIENT_SECRET
import dev.hermeskotlin.core.gateway.GatewayUrl
import io.ktor.http.Url
import io.ktor.http.isSecure
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a page in the app's WebView needs to be signed in to its gateway, in text form. */
data class WebSession(
    /** `Set-Cookie` values for the view's cookie store. */
    val cookies: List<String>,
    /** Headers the first load (and a retry) sends beyond cookies. */
    val headers: Map<String, String>,
    /** When the lent access token lapses, in epoch ms; the app renews and lends a fresh one then. Null when unknown. */
    val expiresAt: Long?,
)

/**
 * Lends the app's sign-in to the plugin WebView, which must not need — or be able to reach — the Ktor types
 * this module keeps to itself.
 *
 * The page gets the access token only, never the refresh token: the gateway rotates that on every renewal,
 * and a provider with reuse detection revokes the whole session when a rotated token comes back, so the
 * page renewing behind the app's back would sign the app out. Without it the page can't renew, and the
 * dashboard's own sign-out has nothing of the app's to revoke. The app renews instead ([renew]) and lends
 * the fresh token.
 *
 * A browser sign-in (bearer tokens, no cookies) goes in the same way: the gateway checks the access-token
 * cookie with the same providers it checks a bearer token with.
 */
class WebCookieJar(
    private val storage: PersistentCookiesStorage,
    private val access: AccessTokens,
    private val tokens: NativeTokens,
    private val auth: AuthApi,
    private val clock: () -> Long = { getTimeMillis() },
) {

    suspend fun sessionFor(url: String): WebSession {
        val parsed = runCatching { Url(url) }.getOrNull() ?: return WebSession(emptyList(), emptyMap(), null)
        val native = tokens.forRequest(parsed)
        if (native != null) return WebSession(nativeCookies(parsed, native), accessHeaders(parsed), native.expiresAt.takeIf { it > 0 }?.times(1000))
        val cookies = storage.webCookies(url)
        return WebSession(cookies.values, accessHeaders(parsed), cookies.accessExpiresAt)
    }

    /**
     * Renews the app's session with [gateway] once its access token has lapsed: any signed-in call does,
     * the app's client renewing on the way (the gateway rotates a cookie session itself, the bearer plugin
     * a browser sign-in). Afterwards [sessionFor] lends the new token for [page].
     *
     * One renewal at a time, and none while the token is still good: the page's timer, its trip to the
     * sign-in and Try again all land at the same moment, and two renewals carrying one refresh token would
     * replay it.
     */
    suspend fun renew(gateway: String, page: String) = renewing.withLock {
        val expiresAt = sessionFor(page).expiresAt
        if (expiresAt != null && expiresAt > clock()) return@withLock
        val url = runCatching { GatewayUrl.parse(gateway) }.getOrNull() ?: return@withLock
        auth.me(url)
    }

    private val renewing = Mutex()

    /** The access token and provider hint, as the gateway names them over plain http; it reads either form. */
    private fun nativeCookies(url: Url, session: NativeSession): List<String> {
        val attributes = "; Path=/" + (if (url.protocol.isSecure()) "; Secure" else "") + "; HttpOnly"
        return buildList {
            add("$SESSION_AT=${session.accessToken}$attributes")
            session.provider?.takeIf { it.isNotBlank() }?.let { add("$SESSION_PROVIDER=$it$attributes") }
        }
    }

    /**
     * The host's Cloudflare Access service token, over https only, as every other gateway request sends it.
     * Access answers with its own cookie, which carries the page's later requests through.
     */
    private suspend fun accessHeaders(url: Url): Map<String, String> {
        if (!url.protocol.isSecure()) return emptyMap()
        val token = access.get(url.host) ?: return emptyMap()
        return mapOf(CF_ACCESS_CLIENT_ID to token.clientId, CF_ACCESS_CLIENT_SECRET to token.clientSecret)
    }
}
