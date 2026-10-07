package dev.hermeskotlin.core.gateway

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.Url
import io.ktor.http.isSecure
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** A Cloudflare Access service token: lets the app through Access without a browser login. */
@Serializable
data class AccessToken(val clientId: String, val clientSecret: String)

/**
 * The service tokens for gateways behind Cloudflare Access, by host, kept in the encrypted [KeyValueStore].
 * Access protects a hostname, so a token goes with every request to that host and to no other.
 */
class AccessTokens(private val store: KeyValueStore) {

    private val mutex = Mutex()
    private var cache: Map<String, AccessToken>? = null

    suspend fun get(host: String): AccessToken? = mutex.withLock { load()[accessHostKey(host)] }

    /** A null [token] forgets the host's token. */
    suspend fun set(host: String, token: AccessToken?) = edit { tokens ->
        if (token == null) tokens - accessHostKey(host) else tokens + (accessHostKey(host) to token)
    }

    /** Forgets the tokens of every host not in [hosts], e.g. one tested but never signed in to. */
    suspend fun retainOnly(hosts: Collection<String>) {
        val keep = hosts.mapTo(HashSet(), ::accessHostKey)
        edit { tokens -> tokens.filterKeys { it in keep } }
    }

    private suspend fun edit(transform: (Map<String, AccessToken>) -> Map<String, AccessToken>) = mutex.withLock {
        val tokens = load()
        val next = transform(tokens)
        if (next == tokens) return@withLock
        if (next.isEmpty()) store.remove(KEY) else store.put(KEY, HermesJson.encodeToString(SERIALIZER, next))
        cache = next
    }

    private suspend fun load(): Map<String, AccessToken> = cache
        ?: (store.get(KEY)?.let { runCatching { HermesJson.decodeFromString(SERIALIZER, it) }.getOrNull() } ?: emptyMap())
            .also { cache = it }

    private companion object {
        const val KEY = "cf_access_tokens.v1"
        val SERIALIZER = MapSerializer(String.serializer(), AccessToken.serializer())
    }
}

/** The host as Access tokens are keyed: lower case, without IPv6 brackets or a trailing dot. */
fun accessHostKey(host: String): String = host.lowercase().trim('[', ']').trimEnd('.')

/**
 * Sends the host's Access service token on every gateway request, the `/api/ws` upgrade included. Only over
 * https/wss: Cloudflare Access is always TLS, and the secret must never cross a network in plain text.
 */
fun cloudflareAccess(tokens: AccessTokens) = createClientPlugin("CloudflareAccess") {
    onRequest { request, _ ->
        if (!request.url.protocol.isSecure()) return@onRequest
        val token = tokens.get(request.url.host) ?: return@onRequest
        request.headers[CF_ACCESS_CLIENT_ID] = token.clientId
        request.headers[CF_ACCESS_CLIENT_SECRET] = token.clientSecret
    }
}

const val CF_ACCESS_CLIENT_ID = "CF-Access-Client-Id"
const val CF_ACCESS_CLIENT_SECRET = "CF-Access-Client-Secret"

/** A redirect to the Cloudflare Access login page: Access stopped the request before it reached Hermes. */
fun isAccessLoginRedirect(status: Int, location: String?): Boolean {
    if (status !in 300..399 || location == null) return false
    val url = runCatching { Url(location) }.getOrNull()
    return url?.host?.lowercase()?.endsWith(".cloudflareaccess.com") == true || "/cdn-cgi/access/" in location
}

/** Cloudflare itself answering 403 with its own page (not JSON from Hermes): Access turned the request down. */
fun isCloudflareRefusal(status: Int, server: String?, isJson: Boolean): Boolean =
    status == 403 && !isJson && server.equals("cloudflare", ignoreCase = true)
