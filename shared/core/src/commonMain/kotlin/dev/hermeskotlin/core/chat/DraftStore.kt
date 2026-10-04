package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Unsent composer text, one draft per chat on each gateway and profile (null: the launch profile), kept
 * across chat switches and app restarts. A new chat that has no stored session yet uses [NEW_CHAT].
 * Each gateway and profile is one stored map, read once and then served from memory.
 */
class DraftStore(private val store: KeyValueStore) {

    /** Drafts by scope key, then by chat; a scope is absent until it's read. */
    private val loaded = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    private val mutex = Mutex()

    suspend fun get(gateway: GatewayUrl, chat: String?, profile: String? = null): String? =
        scope(key(gateway, profile))[chat ?: NEW_CHAT]

    /** Saves [text] as the draft of [chat] (null: a new chat); blank text removes it. */
    suspend fun set(gateway: GatewayUrl, chat: String?, text: String, profile: String? = null) {
        val key = key(gateway, profile)
        mutex.withLock {
            val current = scopeLocked(key)
            val id = chat ?: NEW_CHAT
            val next = if (text.isBlank()) current - id else current + (id to text)
            if (next == current) return
            if (next.isEmpty()) store.remove(key) else store.put(key, HermesJson.encodeToString(SERIALIZER, next))
            loaded.update { it + (key to next) }
        }
    }

    /** The chats on [gateway] and [profile] that have a draft, updated as drafts change. */
    fun chatsWithDrafts(gateway: GatewayUrl, profile: String? = null): Flow<Set<String>> {
        val key = key(gateway, profile)
        return loaded
            .onStart { scope(key) }
            .map { it[key]?.keys.orEmpty() - NEW_CHAT }
            .distinctUntilChanged()
    }

    private suspend fun scope(key: String): Map<String, String> = loaded.value[key] ?: mutex.withLock { scopeLocked(key) }

    private suspend fun scopeLocked(key: String): Map<String, String> {
        loaded.value[key]?.let { return it }
        val stored = store.get(key)?.let { runCatching { HermesJson.decodeFromString(SERIALIZER, it) }.getOrNull() }.orEmpty()
        loaded.update { it + (key to stored) }
        return stored
    }

    private fun key(gateway: GatewayUrl, profile: String?) =
        "chat.drafts.v1.$gateway" + (profile?.let { "#$it" } ?: "")

    companion object {
        /** The draft of a chat that has no stored session yet. */
        const val NEW_CHAT = ""

        private val SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
