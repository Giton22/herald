package dev.hermeskotlin.core.auth

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Bearer tokens from a browser sign-in (`/auth/native/token` and `/auth/native/refresh`, RFC 8252). [baseUrl] is
 * the gateway they came from, where the refresh goes. [expiresAt] is the access token's expiry in Unix seconds.
 */
@Serializable
data class NativeSession(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at") val expiresAt: Long = 0,
    val provider: String? = null,
    @SerialName("user_id") val userId: String? = null,
    val baseUrl: String = "",
)

/**
 * The browser sign-in tokens, by gateway, kept in the encrypted [KeyValueStore]. A request belongs to the gateway
 * with its host and port whose path prefix it falls under, so two gateways behind one proxy (`/a`, `/b`) keep
 * their own tokens.
 */
class NativeTokens(private val store: KeyValueStore) {

    private val mutex = Mutex()
    private var cache: Map<String, NativeSession>? = null

    suspend fun get(gateway: GatewayUrl): NativeSession? = mutex.withLock { load()[gateway.value] }

    /** The tokens for the gateway [request] goes to, if any: the longest matching prefix wins. */
    suspend fun forRequest(request: Url): NativeSession? = mutex.withLock {
        val authority = authorityOf(request)
        val path = request.encodedPath
        load().entries
            .filter { (base, _) ->
                val url = Url(base)
                val prefix = url.encodedPath.trimEnd('/')
                authorityOf(url) == authority && (prefix.isEmpty() || path == prefix || path.startsWith("$prefix/"))
            }
            .maxByOrNull { it.key.length }
            ?.value
    }

    /** A null [session] forgets the gateway's tokens. */
    suspend fun set(gateway: GatewayUrl, session: NativeSession?) = mutex.withLock {
        val tokens = load()
        val next = if (session == null) tokens - gateway.value else tokens + (gateway.value to session.copy(baseUrl = gateway.value))
        if (next == tokens) return@withLock
        if (next.isEmpty()) store.remove(KEY) else store.put(KEY, HermesJson.encodeToString(SERIALIZER, next))
        cache = next
    }

    private suspend fun load(): Map<String, NativeSession> = cache
        ?: (store.get(KEY)?.let { runCatching { HermesJson.decodeFromString(SERIALIZER, it) }.getOrNull() } ?: emptyMap())
            .also { cache = it }

    private companion object {
        const val KEY = "native_tokens.v1"
        val SERIALIZER = MapSerializer(String.serializer(), NativeSession.serializer())
    }
}

/** Host and port: http:// and https:// default ports differ, so a gateway is one or the other. */
private fun authorityOf(url: Url): String = "${url.host.lowercase().trim('[', ']').trimEnd('.')}:${url.port}"
