package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.serialization.Serializable

/** The stored session the user last had open, so the app can reopen it on launch. */
@Serializable
data class LastChat(val sessionId: String, val title: String? = null)

/**
 * Remembers where the user left off on each gateway: a stored session, or nothing when they were
 * on a new chat (which then greets them again on the next launch).
 */
class LastChatStore(private val store: KeyValueStore) {

    suspend fun get(gateway: GatewayUrl): LastChat? = store.get(key(gateway))?.let {
        runCatching { HermesJson.decodeFromString(LastChat.serializer(), it) }.getOrNull()
    }

    suspend fun set(gateway: GatewayUrl, chat: LastChat?) {
        if (chat == null) store.remove(key(gateway))
        else store.put(key(gateway), HermesJson.encodeToString(LastChat.serializer(), chat))
    }

    /** Forgets [sessionId] if it is the remembered one, e.g. after it was deleted. */
    suspend fun forget(gateway: GatewayUrl, sessionId: String) {
        if (get(gateway)?.sessionId == sessionId) set(gateway, null)
    }

    private fun key(gateway: GatewayUrl) = "chat.last.v1.$gateway"
}
