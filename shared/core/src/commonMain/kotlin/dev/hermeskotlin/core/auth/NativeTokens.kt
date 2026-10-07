package dev.hermeskotlin.core.auth

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
 * The browser sign-in tokens, by gateway address, kept in the encrypted [KeyValueStore]. Keyed by host and port,
 * like the session cookies, so the `/api/ws` upgrade (ws:// to the same address) finds them too.
 */
class NativeTokens(private val store: KeyValueStore) {

    private val mutex = Mutex()
    private var cache: Map<String, NativeSession>? = null

    suspend fun get(url: Url): NativeSession? = mutex.withLock { load()[nativeTokenKey(url)] }

    /** A null [session] forgets the address's tokens. */
    suspend fun set(url: Url, session: NativeSession?) = mutex.withLock {
        val tokens = load()
        val key = nativeTokenKey(url)
        val next = if (session == null) tokens - key else tokens + (key to session)
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

/** Host and port, so http:// and ws:// (and https:// and wss://) to one gateway share a key. */
internal fun nativeTokenKey(url: Url): String = "${url.host.lowercase().trim('[', ']').trimEnd('.')}:${url.port}"
