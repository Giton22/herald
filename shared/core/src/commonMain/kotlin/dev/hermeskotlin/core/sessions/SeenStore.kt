package dev.hermeskotlin.core.sessions

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * How far the user has read each chat on a gateway and profile: the activity time it had when last
 * looked at. [since] is when this phone started keeping track; activity before it counts as read, so a
 * first launch doesn't mark the whole history unread.
 */
@Serializable
data class SeenChats(val since: Double, val chats: Map<String, Double> = emptyMap()) {

    /**
     * A chat with activity after the user last looked at it. `is_active` can't rule a reply out: the
     * gateway keeps it set while the session stays loaded, well after the turn ends.
     */
    fun isUnread(session: SessionSummary): Boolean {
        val activity = session.activityAt ?: return false
        return activity > (chats[session.id] ?: since) + SEEN_SLACK_SECONDS
    }
}

/** Timestamps from the gateway and this phone's clock round differently. */
private const val SEEN_SLACK_SECONDS = 1.0

/** Remembers [SeenChats] per gateway and profile (null: the launch profile), kept across restarts. */
class SeenStore(private val store: KeyValueStore, private val now: () -> Double) {

    private val loaded = MutableStateFlow<Map<String, SeenChats>>(emptyMap())
    private val mutex = Mutex()

    fun seen(gateway: GatewayUrl, profile: String? = null): Flow<SeenChats> {
        val key = key(gateway, profile)
        return loaded.onStart { scope(key) }.map { it[key] }.filterNotNull()
    }

    /** Records that the user has seen [session] as it is now. */
    suspend fun markSeen(gateway: GatewayUrl, session: SessionSummary, profile: String? = null) {
        val activity = session.activityAt ?: return
        val key = key(gateway, profile)
        mutex.withLock {
            val current = scopeLocked(key)
            if ((current.chats[session.id] ?: Double.MIN_VALUE) >= activity) return
            // The oldest entries go first; anything dropped counts as read up to `since`, which is fine.
            val chats = (current.chats + (session.id to activity)).entries.sortedByDescending { it.value }
                .take(MAX_REMEMBERED).associate { it.key to it.value }
            val next = current.copy(chats = chats)
            store.put(key, HermesJson.encodeToString(SeenChats.serializer(), next))
            loaded.update { it + (key to next) }
        }
    }

    private suspend fun scope(key: String) {
        if (key !in loaded.value) mutex.withLock { scopeLocked(key) }
    }

    private suspend fun scopeLocked(key: String): SeenChats {
        loaded.value[key]?.let { return it }
        val stored = store.get(key)?.let { runCatching { HermesJson.decodeFromString(SeenChats.serializer(), it) }.getOrNull() }
        val seen = stored ?: SeenChats(since = now()).also { store.put(key, HermesJson.encodeToString(SeenChats.serializer(), it)) }
        loaded.update { it + (key to seen) }
        return seen
    }

    private fun key(gateway: GatewayUrl, profile: String?) = "chat.seen.v1.$gateway" + (profile?.let { "#$it" } ?: "")

    private companion object {
        const val MAX_REMEMBERED = 300
    }
}
