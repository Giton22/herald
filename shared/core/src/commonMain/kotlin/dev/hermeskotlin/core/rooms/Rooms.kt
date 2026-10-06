package dev.hermeskotlin.core.rooms

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Hosted rooms: group chats the gateway itself runs (`groups.*`), so the room keeps working with no
 * client attached — which is the only kind of group chat a phone can come back to. The shapes here
 * follow the gateway's `groups_bot_relay` contract; fields the app doesn't need are left out and
 * unknown ones are ignored.
 */

/** One roster row of a room (`RoomMember`); legacy rooms may carry looser rows, so everything is optional. */
@Serializable
data class RoomMember(
    @SerialName("member_id") val memberId: String? = null,
    val profile: String? = null,
    val handle: String? = null,
    @SerialName("display_name") val displayName: String? = null,
) {
    /** What to call them in the transcript. */
    val label: String get() = displayName ?: profile ?: handle ?: memberId ?: "someone"
}

/** A hosted room (`Room`). */
@Serializable
data class Room(
    @SerialName("room_id") val roomId: String,
    val name: String,
    val members: List<RoomMember> = emptyList(),
    @SerialName("latest_seq") val latestSeq: Int? = null,
    @SerialName("updated_at") val updatedAt: Double? = null,
    @SerialName("disbanded_at") val disbandedAt: Double? = null,
    val revision: Int = 0,
) {
    fun member(memberId: String?): RoomMember? = members.firstOrNull { it.memberId == memberId }
}

/** Who wrote a line: the user, a member, or the gateway itself. */
@Serializable
data class RoomActor(val kind: String, val id: String)

/**
 * One line of a room's log (`RoomEvent`). The log is the truth: the transcript is built from these,
 * and [seq] is the cursor everything is read at.
 */
@Serializable
data class RoomEvent(
    @SerialName("room_id") val roomId: String,
    val seq: Int,
    @SerialName("event_id") val eventId: String,
    val kind: String,
    val actor: RoomActor,
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: Double = 0.0,
)

/** A `groups.log` page: [cursor] goes back as the next `since_seq`. */
@Serializable
data class RoomLogPage(
    val events: List<RoomEvent> = emptyList(),
    val cursor: Int = 0,
    @SerialName("latest_seq") val latestSeq: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
)

/** One thing the room waits on the user for: an approval or a retry, with the driver's own coordinates. */
@Serializable
data class RoomPendingAction(
    val kind: String,
    @SerialName("member_id") val memberId: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("execution_generation") val executionGeneration: Int? = null,
    @SerialName("request_id") val requestId: String? = null,
    val approval: JsonObject? = null,
) {
    /** What the approval is about, as far as the gateway will say (the command is redacted server-side). */
    val approvalSummary: String? get() = approval?.payloadText("command") ?: approval?.payloadText("description")
}

/** `groups.state`'s driver status: how the room is doing, and what waits on the user. */
@Serializable
data class RoomDriverStatus(
    val running: Boolean = false,
    val working: Boolean = false,
    val blocked: Boolean = false,
    val counts: Map<String, Int> = emptyMap(),
    @SerialName("pending_actions") val pendingActions: List<RoomPendingAction> = emptyList(),
)

/** `groups.state`: the room plus its live driver status. */
@Serializable
data class RoomState(
    val room: Room,
    @SerialName("driver_status") val driverStatus: RoomDriverStatus? = null,
)

/** What `groups.capabilities` says this gateway can do; the app gates Rooms on `driver`. */
@Serializable
data class RoomCapabilities(
    @SerialName("protocol_version") val protocolVersion: Int = 0,
    val driver: Boolean = false,
    @SerialName("authority_gateway_id") val authorityGatewayId: String? = null,
    val features: List<String> = emptyList(),
    val methods: List<String> = emptyList(),
    @SerialName("max_log_limit") val maxLogLimit: Int = 50,
)

/** A roster row as the app proposes it at `groups.create` (`RoomMemberInput`). */
@Serializable
data class RoomMemberInput(
    @SerialName("member_id") val memberId: String,
    val profile: String,
    val handle: String,
    @SerialName("display_name") val displayName: String? = null,
)

/** One thing the transcript shows; everything else in the log is machinery the user shouldn't read. */
sealed interface RoomLine {
    val seq: Int

    /** A message: [fromUser] when the user sent it, otherwise [speaker] said it. */
    data class Message(
        override val seq: Int,
        val fromUser: Boolean,
        val speaker: String?,
        val text: String,
        val eventId: String,
        /** The speaking member's bot profile, which gives the line its face and color; null for the user. */
        val profile: String? = null,
        /** When it was written, in epoch seconds; null when the log didn't say. */
        val createdAt: Double? = null,
    ) : RoomLine

    /** A line the gateway wrote: a failure, a stop, a rename. */
    data class System(override val seq: Int, val text: String) : RoomLine
}

/** A payload string, or null when the key is absent or not a string. */
internal fun JsonObject.payloadText(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** The words of a message event; null when it carries none. */
val RoomEvent.messageText: String? get() = payload.payloadText("text")

/** A payload integer, or null when the key is absent or not a number. */
internal fun JsonObject.payloadInt(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/**
 * The transcript line for [event], or null for the machinery: turn.started / turn.settled /
 * turn.deferred / room.activity / authority events are bookkeeping, not something to read.
 */
fun roomLine(event: RoomEvent, members: List<RoomMember>): RoomLine? {
    fun member(memberId: String?): String = members.firstOrNull { it.memberId == memberId }?.label ?: "A member"
    val createdAt = event.createdAt.takeIf { it > 0 }
    return when (event.kind) {
        "message.user" -> event.messageText?.takeIf { it.isNotBlank() }
            ?.let { RoomLine.Message(event.seq, fromUser = true, speaker = null, text = it, eventId = event.eventId, createdAt = createdAt) }
        "message.member" -> event.messageText?.takeIf { it.isNotBlank() }?.let { text ->
            val memberId = event.payload.payloadText("member_id")
            val row = members.firstOrNull { it.memberId == memberId }
            RoomLine.Message(
                event.seq,
                fromUser = false,
                speaker = member(memberId),
                text = text,
                eventId = event.eventId,
                profile = row?.profile ?: memberId,
                createdAt = createdAt,
            )
        }
        "turn.failed" -> {
            val error = event.payload.payloadText("error") ?: "something went wrong"
            RoomLine.System(event.seq, "${member(event.payload.payloadText("member_id"))} couldn't take their turn: $error")
        }
        "turn.cancelled" -> {
            val reason = event.payload.payloadText("reason")
            RoomLine.System(event.seq, "Turn stopped${if (reason.isNullOrBlank()) "." else ": $reason"}")
        }
        // A member that chose to say nothing (its turn settles with `passed`): quiet, but not silent.
        "turn.settled" -> RoomLine.System(event.seq, "${member(event.payload.payloadText("member_id"))} had nothing to add.")
            .takeIf { (event.payload["passed"] as? JsonPrimitive)?.booleanOrNull == true }
        "room.stop_requested" -> RoomLine.System(event.seq, "Stopping the room was asked for.")
        "room.renamed" -> event.payload.payloadText("name")?.let { RoomLine.System(event.seq, "Renamed to \u201c$it\u201d.") }
        else -> null
    }
}

/** Every line the transcript draws for [events], in order. */
fun roomLines(events: List<RoomEvent>, members: List<RoomMember>): List<RoomLine> =
    events.mapNotNull { roomLine(it, members) }

/**
 * [existing] plus [incoming], by seq: what a page brings is merged into what's already shown, so a
 * catch-up that overlaps (or a duplicate read) never draws a line twice.
 */
fun mergeRoomEvents(existing: List<RoomEvent>, incoming: List<RoomEvent>): List<RoomEvent> {
    if (incoming.isEmpty()) return existing
    val seen = existing.asSequence().map { it.seq }.toHashSet()
    val fresh = incoming.filter { it.seq !in seen && seen.add(it.seq) }
    if (fresh.isEmpty()) return existing
    return (existing + fresh).sortedBy { it.seq }
}
