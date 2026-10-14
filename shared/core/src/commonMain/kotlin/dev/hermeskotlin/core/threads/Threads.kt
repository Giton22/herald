package dev.hermeskotlin.core.threads

import dev.hermeskotlin.core.sessions.SessionSummary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.random.Random

/**
 * A thread: a permanent, named place for one ongoing conversation, like a forum topic in a messaging app. It
 * points at its current chat ([sessionId]); starting it fresh files that chat away and points it at a new one,
 * so the thread itself stays where it is. New chats in it run in [cwd] when it has one.
 */
data class ChatThread(
    val id: String,
    val name: String,
    val sessionId: String,
    val cwd: String? = null,
) {
    /** Whether [session] is this thread's chat, by its id or an earlier id of its compression lineage. */
    fun holds(session: SessionSummary): Boolean = session.isChat(sessionId)
}

/**
 * The threads as the profile's `ui_meta` keeps them, under [KEY], so every device signed in to the gateway sees
 * the same ones in the same order: `{"threads": [{"id", "name", "sessionId", "cwd"}]}`.
 */
object ThreadMeta {
    const val KEY = "herald-threads"

    /** The threads stored in [block]; entries missing a field, or repeating an id, are skipped. */
    fun parse(block: JsonElement?): List<ChatThread> {
        val entries = ((block as? JsonObject)?.get("threads") as? JsonArray).orEmpty()
        return entries.mapNotNull { entry ->
            val o = entry as? JsonObject ?: return@mapNotNull null
            val id = o.text("id") ?: return@mapNotNull null
            val name = o.text("name") ?: return@mapNotNull null
            val sessionId = o.text("sessionId") ?: return@mapNotNull null
            ChatThread(id, name, sessionId, o.text("cwd"))
        }.distinctBy { it.id }
    }

    /** [threads] written into [block], whose other keys are kept as they are. */
    fun write(threads: List<ChatThread>, block: JsonElement? = null): JsonObject {
        val list = JsonArray(
            threads.map { thread ->
                buildJsonObject {
                    put("id", thread.id)
                    put("name", thread.name)
                    put("sessionId", thread.sessionId)
                    thread.cwd?.let { put("cwd", it) }
                }
            },
        )
        return JsonObject(((block as? JsonObject).orEmpty()) + ("threads" to list))
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

/** A new thread id: random, short, and safe in JSON and logs. */
fun newThreadId(random: Random = Random.Default): String =
    (1..12).map { ID_CHARS[random.nextInt(ID_CHARS.length)] }.joinToString("")

private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

/** [thread] added at the end. A thread already pointing at the same chat is not added twice. */
fun List<ChatThread>.withThread(thread: ChatThread): List<ChatThread> =
    if (any { it.id == thread.id || it.sessionId == thread.sessionId }) this else this + thread

/** The thread [id] taken out; its chat is left alone. */
fun List<ChatThread>.withoutThread(id: String): List<ChatThread> = filterNot { it.id == id }

/** The thread [id] named [name]; a blank name changes nothing. */
fun List<ChatThread>.renamed(id: String, name: String): List<ChatThread> {
    val clean = name.trim().takeIf { it.isNotEmpty() } ?: return this
    return map { if (it.id == id) it.copy(name = clean) else it }
}

/** The thread [id] pointed at the chat [sessionId]. */
fun List<ChatThread>.pointedAt(id: String, sessionId: String): List<ChatThread> =
    map { if (it.id == id) it.copy(sessionId = sessionId) else it }

/** The thread [id] moved to [index], clamped to the list; an unknown id changes nothing. */
fun List<ChatThread>.moved(id: String, index: Int): List<ChatThread> {
    val from = indexOfFirst { it.id == id }
    if (from < 0) return this
    val rest = toMutableList()
    val thread = rest.removeAt(from)
    rest.add(index.coerceIn(0, rest.size), thread)
    return rest
}

/**
 * The thread [id] moved just past the thread [other], above it when [other] was above, below it when below: one
 * place up or down among the threads a narrowed list shows, whatever hidden ones sit between. An unknown id
 * changes nothing.
 */
fun List<ChatThread>.movedPast(id: String, other: String): List<ChatThread> {
    val at = indexOfFirst { it.id == other }
    return if (at < 0) this else moved(id, at)
}

/**
 * The threads [ids] names put in its order, in the places they had between them; threads it leaves out keep
 * their own places. So an order dragged in a narrowed list leaves the hidden threads where they were, and one made
 * on a list another device has since added to loses nothing.
 */
fun List<ChatThread>.ordered(ids: List<String>): List<ChatThread> {
    val byId = associateBy { it.id }
    val named = ids.distinct().mapNotNull { byId[it] }
    val namedIds = named.mapTo(HashSet()) { it.id }
    val next = named.iterator()
    return map { if (it.id in namedIds) next.next() else it }
}
