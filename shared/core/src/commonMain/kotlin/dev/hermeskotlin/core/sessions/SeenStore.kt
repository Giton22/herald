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
data class SeenChats(
    val since: Double,
    val chats: Map<String, Double> = emptyMap(),
    /** Chats the user left on this phone while a turn still ran in them, until they look at them again. */
    val leftRunning: Set<String> = emptySet(),
) {

    /**
     * A chat with activity after the user last looked at it. `is_active` can't rule a reply out: the
     * gateway keeps it set while the session stays loaded, well after the turn ends.
     *
     * A chat from outside the gateway's clients ([isExternalSource]: Telegram, Discord, a plugin's bridge) is read
     * where it came in, so as in Hermes Desktop its activity alone doesn't count: only the gateway's read state
     * ([SessionSummary.unread]) or a turn the user left running here does.
     */
    fun isUnread(session: SessionSummary): Boolean {
        val activity = session.activityAt ?: return false
        if (activity <= lastLook(session.id) + SEEN_SLACK_SECONDS) return false
        return !isExternalSource(session.source) || session.unread || session.id in leftRunning
    }

    /**
     * The activity time [session] had when the user last looked at it, for marking where the new messages
     * start; null when nothing has happened in it since.
     */
    fun readUpTo(session: SessionSummary): Double? = if (isUnread(session)) lastLook(session.id) else null

    /** When the user last looked at the chat [sessionId] on this phone, or when tracking began if never. */
    fun lastLook(sessionId: String): Double = chats[sessionId] ?: since
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

    /**
     * When the user last looked at [sessionId], for a chat opened from outside the chat list (a notification),
     * where its summary isn't to hand. Messages after it are new to them.
     */
    suspend fun lastLook(gateway: GatewayUrl, sessionId: String, profile: String? = null): Double =
        mutex.withLock { scopeLocked(key(gateway, profile)) }.lastLook(sessionId)

    /** Records that the user has seen [session] as it is now. */
    suspend fun markSeen(gateway: GatewayUrl, session: SessionSummary, profile: String? = null) {
        val activity = session.activityAt ?: return
        markLooked(gateway, session.id, activity, profile)
    }

    /**
     * Records that the user had the chat [sessionId] open with everything up to [at] (epoch seconds) in it,
     * e.g. when they leave the app on it, and whether a turn was [running] in it then. Never moves a last look back.
     */
    suspend fun markLooked(gateway: GatewayUrl, sessionId: String, at: Double, profile: String? = null, running: Boolean = false) {
        val key = key(gateway, profile)
        mutex.withLock {
            val current = scopeLocked(key)
            val leftRunning = if (running) current.leftRunning + sessionId else current.leftRunning - sessionId
            val newer = (current.chats[sessionId] ?: Double.MIN_VALUE) < at
            if (!newer && leftRunning == current.leftRunning) return
            // The oldest entries go first; anything dropped counts as read up to `since`, which is fine.
            val chats = if (!newer) current.chats else (current.chats + (sessionId to at)).entries.sortedByDescending { it.value }
                .take(MAX_REMEMBERED).associate { it.key to it.value }
            val next = current.copy(chats = chats, leftRunning = leftRunning.filterTo(mutableSetOf()) { it in chats })
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
