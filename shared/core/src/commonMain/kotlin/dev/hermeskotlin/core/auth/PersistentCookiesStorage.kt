package dev.hermeskotlin.core.auth

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
internal data class StoredCookie(
    val name: String,
    val value: String,
    /** Request host for host-only cookies, otherwise the Domain attribute (no leading dot). */
    val domain: String,
    val hostOnly: Boolean,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    /** Epoch millis, or null for a session cookie. */
    val expiresAt: Long?,
)

/**
 * Ktor cookie jar persisted in an encrypted [KeyValueStore], so the dashboard session
 * (`hermes_session_at` / `hermes_session_rt`, possibly `__Host-` / `__Secure-` prefixed) survives restarts.
 */
class PersistentCookiesStorage(
    private val store: KeyValueStore,
    private val clock: () -> Long = { getTimeMillis() },
) : CookiesStorage {

    private val mutex = Mutex()
    private var cache: MutableList<StoredCookie>? = null

    override suspend fun get(requestUrl: Url): List<Cookie> = mutex.withLock {
        val now = clock()
        val cookies = load()
        if (cookies.removeAll { it.isExpired(now) }) save(cookies)
        cookies.filter { it.matches(requestUrl) }.map { it.toCookie() }
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie): Unit = mutex.withLock {
        // Cloudflare Access hands one out when the service token passes. Every request carries the token,
        // so keeping it would only make the host look signed in to Hermes, and let a changed token go untested.
        if (cookie.name.equals(CF_AUTHORIZATION, ignoreCase = true)) return@withLock
        val stored = cookie.toStored(requestUrl, clock())
        val cookies = load()
        cookies.removeAll { it.name == stored.name && it.domain == stored.domain && it.path == stored.path }
        if (!stored.isExpired(clock())) cookies += stored
        save(cookies)
    }

    /** Drop every cookie for [host] (sign out). */
    suspend fun clear(host: String) = mutex.withLock {
        val cookies = load()
        if (cookies.removeAll { it.domain.equals(host, ignoreCase = true) || host.endsWith(".${it.domain}") }) save(cookies)
    }

    suspend fun hasCookies(url: Url): Boolean = get(url).isNotEmpty()

    /**
     * The stored cookies for [url] as `Set-Cookie` values, for a cookie store that has to be told each
     * cookie by text (the in-app WebView's jar, which has no access to this module's Ktor types):
     * session cookies go in as the gateway set them — host-only ones without a Domain, wider scopes
     * with one. Every path on the host goes in, each with its own Path: the page goes on to call routes
     * (`/api/…`) other than the one it was opened at.
     *
     * The refresh token stays here. The gateway rotates it whenever a request renews the session, and a
     * provider with reuse detection revokes the whole session when a rotated token comes back. Only one
     * holder may renew, and that is the app: the page gets the access token, which the app hands over
     * again once it has renewed ([WebCookies.accessExpiresAt] says when). Nothing when the URL can't be read.
     */
    internal suspend fun webCookies(url: String): WebCookies {
        val parsed = runCatching { Url(url) }.getOrNull() ?: return WebCookies(emptyList(), null)
        return mutex.withLock {
            val now = clock()
            val cookies = load()
            if (cookies.removeAll { it.isExpired(now) }) save(cookies)
            val lent = cookies.filter { it.matches(parsed, anyPath = true) && it.bareName != SESSION_RT }
            WebCookies(
                values = lent.map { cookie ->
                    buildString {
                        append(cookie.name).append('=').append(cookie.value)
                        append("; Path=").append(cookie.path)
                        if (!cookie.hostOnly) append("; Domain=").append(cookie.domain)
                        if (cookie.secure) append("; Secure")
                        if (cookie.httpOnly) append("; HttpOnly")
                    }
                },
                accessExpiresAt = lent.firstOrNull { it.bareName == SESSION_AT }?.expiresAt,
            )
        }
    }

    override fun close() = Unit

    private suspend fun load(): MutableList<StoredCookie> = cache ?: run {
        val raw = store.get(KEY)
        val decoded = raw?.let {
            runCatching { HermesJson.decodeFromString(ListSerializer(StoredCookie.serializer()), it) }.getOrNull()
        }
        (decoded ?: emptyList()).toMutableList().also { cache = it }
    }

    /** Session cookies (no expiry) are persisted too: the app is the "browser" and must survive restarts. */
    private suspend fun save(cookies: List<StoredCookie>) {
        store.put(KEY, HermesJson.encodeToString(ListSerializer(StoredCookie.serializer()), cookies))
    }

    private companion object {
        const val KEY = "cookies.v1"
        const val CF_AUTHORIZATION = "CF_Authorization"
    }
}

/** Cookies lent to the page as `Set-Cookie` values, and when the access token among them lapses (epoch ms). */
internal data class WebCookies(val values: List<String>, val accessExpiresAt: Long?)

/** The gateway's session cookie names, without the `__Host-` / `__Secure-` prefix it adds over https. */
internal const val SESSION_AT = "hermes_session_at"
internal const val SESSION_RT = "hermes_session_rt"
internal const val SESSION_PROVIDER = "hermes_session_provider"

private val StoredCookie.bareName get() = name.removePrefix("__Host-").removePrefix("__Secure-")

private fun StoredCookie.isExpired(now: Long) = expiresAt != null && expiresAt <= now

private fun StoredCookie.matches(url: Url, anyPath: Boolean = false): Boolean {
    val host = url.host.lowercase()
    val domainOk = if (hostOnly) host == domain else host == domain || host.endsWith(".$domain")
    val requestPath = url.encodedPath.ifEmpty { "/" }
    val pathOk = anyPath || requestPath == path ||
        (requestPath.startsWith(path) && (path.endsWith("/") || requestPath.getOrNull(path.length) == '/'))
    val secureOk = !secure || url.protocol == URLProtocol.HTTPS || url.protocol == URLProtocol.WSS
    return domainOk && pathOk && secureOk
}

private fun StoredCookie.toCookie() = Cookie(
    name = name,
    value = value,
    domain = domain,
    path = path,
    secure = secure,
    httpOnly = httpOnly,
)

private fun Cookie.toStored(requestUrl: Url, now: Long): StoredCookie {
    val explicitDomain = domain?.trimStart('.')?.lowercase()?.takeIf { it.isNotEmpty() }
    val expiresAt = when {
        maxAge != null -> now + maxAge!!.toLong() * 1000
        expires != null -> expires!!.timestamp
        else -> null
    }
    return StoredCookie(
        name = name,
        value = value,
        domain = explicitDomain ?: requestUrl.host.lowercase(),
        hostOnly = explicitDomain == null,
        path = path?.takeIf { it.startsWith("/") } ?: "/",
        secure = secure,
        httpOnly = httpOnly,
        expiresAt = expiresAt,
    )
}
