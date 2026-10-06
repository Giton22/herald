package dev.hermeskotlin.core.rooms

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * How far the user has read each room on a gateway: the newest log seq it had when last on screen, kept
 * across restarts. A room this phone has never seen counts as read up to where it first saw it, so a first
 * launch doesn't mark every room unread.
 */
class RoomSeenStore(private val store: KeyValueStore) {

    private val loaded = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())
    private val mutex = Mutex()

    /** Each room's seen seq on [gateway], by room id. */
    fun seen(gateway: GatewayUrl): Flow<Map<String, Int>> {
        val key = key(gateway)
        return loaded.onStart { if (key !in loaded.value) mutex.withLock { scopeLocked(key) } }.map { it[key].orEmpty() }
    }

    /** Records that the user has seen [roomId] through [seq]; an older seq changes nothing. */
    suspend fun markSeen(gateway: GatewayUrl, roomId: String, seq: Int) = mutex.withLock {
        val key = key(gateway)
        val current = scopeLocked(key)
        if ((current[roomId] ?: -1) >= seq) return@withLock
        save(key, current + (roomId to seq))
    }

    /** Takes [rooms] this phone has no record of as read up to now, so only what comes later is new. */
    suspend fun takeStock(gateway: GatewayUrl, rooms: List<Room>) = mutex.withLock {
        val key = key(gateway)
        val current = scopeLocked(key)
        val fresh = rooms.filter { it.roomId !in current }.associate { it.roomId to (it.latestSeq ?: 0) }
        if (fresh.isNotEmpty()) save(key, current + fresh)
    }

    private suspend fun save(key: String, rooms: Map<String, Int>) {
        // A deleted room's entry would stay forever: keep the newest ones only.
        val kept = rooms.entries.sortedByDescending { it.value }.take(MAX_REMEMBERED).associate { it.key to it.value }
        store.put(key, HermesJson.encodeToString(SERIALIZER, kept))
        loaded.update { it + (key to kept) }
    }

    private suspend fun scopeLocked(key: String): Map<String, Int> {
        loaded.value[key]?.let { return it }
        val stored = store.get(key)?.let { runCatching { HermesJson.decodeFromString(SERIALIZER, it) }.getOrNull() }.orEmpty()
        loaded.update { it + (key to stored) }
        return stored
    }

    private fun key(gateway: GatewayUrl) = "room.seen.v1.$gateway"

    private companion object {
        const val MAX_REMEMBERED = 300
        val SERIALIZER = MapSerializer(String.serializer(), Int.serializer())
    }
}

/** A room with lines after the ones the user saw, by [seen] from [RoomSeenStore.seen]. */
fun Room.isUnread(seen: Map<String, Int>): Boolean {
    val read = seen[roomId] ?: return false
    return (latestSeq ?: 0) > read
}

/** A bot's line to tell the user about: who said it (their roster row, if the room still has it), what, and when. */
data class RoomNewLine(val memberId: String, val member: RoomMember?, val text: String, val createdAt: Double)

/**
 * The lines in [events] worth a notification: what members said after [afterSeq]. Not the user's own
 * messages (written on another device), and none of the machinery.
 */
fun roomNewLines(events: List<RoomEvent>, members: List<RoomMember>, afterSeq: Int): List<RoomNewLine> =
    events.filter { it.seq > afterSeq && it.kind == "message.member" }.mapNotNull { event ->
        val memberId = event.payload.payloadText("member_id") ?: return@mapNotNull null
        val text = event.messageText?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        RoomNewLine(memberId, members.firstOrNull { it.memberId == memberId }, text, event.createdAt)
    }

/**
 * The room a session runs a member's turn for, when [title] names one of [roomIds]: the gateway runs
 * each turn as a session titled "Group: <room id>" (seen on hermes-agent v2026.9.24). Such a session's
 * reply is already the room's line, so it isn't told about twice.
 */
fun roomOfTurnSession(title: String?, roomIds: Set<String>): String? =
    title?.takeIf { it.startsWith(ROOM_TURN_PREFIX) }?.removePrefix(ROOM_TURN_PREFIX)?.trim()?.takeIf { it in roomIds }

private const val ROOM_TURN_PREFIX = "Group: "

/** A room to open, asked for from outside the app's screens (a tapped notification). */
data class RoomLink(val roomId: String, val name: String?)

/** Hands a room to open from outside the UI to the screens; only the latest is kept, until taken. */
class RoomLinks {
    private val _pending = MutableStateFlow<RoomLink?>(null)
    val pending: StateFlow<RoomLink?> = _pending.asStateFlow()

    fun open(roomId: String, name: String?) {
        _pending.value = RoomLink(roomId, name)
    }

    /** Takes [link] once it's opened, unless a newer one came meanwhile. */
    fun consume(link: RoomLink) = _pending.update { if (it == link) null else it }
}
