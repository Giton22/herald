package dev.hermeskotlin.core.rooms

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Desktop rooms: the group chats Desktop's Bot Mode runs, as the `hermes-bots-groups` projection in the
 * default profile's `ui_meta` carries them (a bounded copy — the last 16 messages per room, 48 KB in
 * all). They are Desktop's: it runs the rounds, holds the watermarks and owns the log. The app reads the
 * mirror read-only, labels the rooms "Continue on Desktop", and never writes to it.
 */

/** One line of a Desktop room, as the projection carries it. */
data class DesktopLine(
    /** The message id; a copy union keys by it (missing on legacy entries). */
    val id: String? = null,
    /** The user's own line (`from.kind == "user"`). */
    val fromUser: Boolean,
    /** The member's name (its profile), or the name the copy gives the user. */
    val speaker: String,
    /** Where that member spoke from ("This device"), when the copy says. */
    val source: String? = null,
    val text: String,
    /** When it was said, epoch millis; null when the copy doesn't say. */
    val at: Double? = null,
    /** The projection cut the message short ("… [truncated]"). */
    val truncated: Boolean = false,
)

/** One member of a Desktop room as the projection lists it. */
data class DesktopMember(val name: String, val handle: String? = null)

/** One Desktop room. Read-only here: Desktop is where it runs. */
data class DesktopRoom(
    /** `id:<roomId>` on current builds, `name:<name>` on legacy rooms; tombstones are keyed by it. */
    val key: String,
    val name: String,
    val roomId: String? = null,
    val members: List<DesktopMember> = emptyList(),
    val lines: List<DesktopLine> = emptyList(),
    /** How many earlier lines the projection left out of its window. */
    val omitted: Int = 0,
    /** The copy's revision; a `name:` tombstone deletes only at or above it. */
    val revision: Int = 0,
    /** When the room last moved, epoch millis: its newest line's time. */
    val updatedAt: Double? = null,
) {
    /**
     * Whether the room is asking for the user: its newest member line mentions `@user` — Desktop's own
     * needs-you rule.
     */
    val needsYou: Boolean get() = lines.lastOrNull { !it.fromUser }?.text?.let { NEEDS_YOU.containsMatchIn(it) } == true

    private companion object {
        val NEEDS_YOU = Regex("@user\\b", RegexOption.IGNORE_CASE)
    }
}

/** Where Desktop keeps the mirror in the default profile's `ui_meta`. */
const val DESKTOP_ROOMS_KEY = "hermes-bots-groups"

/**
 * The Desktop rooms in a profile row's `ui_meta`, or none when it has no mirror. Read leniently, the way
 * the look fields are: a field of an unexpected shape is simply missing, and a room that doesn't parse
 * is skipped rather than failing everything.
 *
 * The tombstones decide what's gone: an `id:` tombstone deletes its room always; a `name:` one only when
 * its revision is at least the room's (a same-name recreate starts over at revision 0 and must survive).
 * A room or message the copy doesn't carry is not a deletion.
 */
fun parseDesktopRooms(uiMeta: JsonObject?): List<DesktopRoom> {
    val snapshot = uiMeta?.get(DESKTOP_ROOMS_KEY) as? JsonObject ?: return emptyList()
    val rooms = snapshot["rooms"] as? JsonObject ?: return emptyList()
    val deleted = snapshot["deleted"] as? JsonObject ?: JsonObject(emptyMap())
    val parsed = rooms.mapNotNull { (key, body) -> parseDesktopRoom(key, body as? JsonObject ?: return@mapNotNull null, deleted) }
    // Newest first, as rooms are read everywhere here; ties by name so the order doesn't wobble.
    return parsed.sortedWith(compareByDescending<DesktopRoom> { it.updatedAt ?: 0.0 }.thenBy { it.name.lowercase() })
}

private fun parseDesktopRoom(key: String, body: JsonObject, deleted: JsonObject): DesktopRoom? {
    val revision = body.int("revision") ?: 0
    // A tombstone keyed by the room id always deletes; a name-keyed one only from the revision it names on.
    val tombstoneAt = deleted.double(key)
    if (tombstoneAt != null && (key.startsWith("id:") || revision <= tombstoneAt)) return null
    val lines = (body["log"] as? JsonArray)
        ?.mapNotNull { element -> (element as? JsonObject)?.let(::parseDesktopLine) }
        .orEmpty()
    val members = (body["members"] as? JsonArray).orEmpty().mapNotNull { element ->
        val member = element as? JsonObject ?: return@mapNotNull null
        member.text("name")?.takeIf { it.isNotBlank() }?.let { DesktopMember(it, member.text("handle")) }
    }
    return DesktopRoom(
        key = key,
        name = body.text("name")?.takeIf { it.isNotBlank() } ?: key.substringAfter(':'),
        roomId = body.text("roomId"),
        members = members,
        lines = lines,
        omitted = body.int("omitted") ?: 0,
        revision = revision,
        updatedAt = lines.mapNotNull { it.at }.maxOrNull(),
    )
}

private fun parseDesktopLine(line: JsonObject): DesktopLine? {
    val text = line.text("text") ?: return null
    // Every projection entry names its sender; one that doesn't has nothing to draw it with.
    val from = line["from"] as? JsonObject ?: return null
    val fromUser = from.text("kind") == "user"
    return DesktopLine(
        id = line.text("id"),
        fromUser = fromUser,
        speaker = from.text("name")?.takeIf { it.isNotBlank() } ?: if (fromUser) "You" else "A member",
        source = from.text("source"),
        text = text,
        at = line.double("at"),
        truncated = (line["truncated"] as? JsonPrimitive)?.booleanOrNull == true,
    )
}

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

private fun JsonObject.double(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
