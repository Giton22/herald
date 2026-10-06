package dev.hermeskotlin.core.rooms

/**
 * The members still to answer the user's newest message, in the order they take their turns. The gateway
 * gives no "who's working now" (`driver_status` doesn't name the member, and the hosted driver writes no
 * `turn.started`), so this is read off the log: the message's first-round recipients ([roomRecipients]),
 * answering one at a time in roster order, less each one whose first-round message or turn result
 * (`discussion_event_id` = the message's `event_id`) is in. Empty once the discussion has settled; later
 * rounds, where bots that were named by another bot speak again, aren't counted.
 */
fun waitingFor(events: List<RoomEvent>, members: List<RoomMember>): List<RoomMember> {
    val asked = events.lastOrNull { it.kind == "message.user" } ?: return emptyList()
    val about = events.filter { it.payload.payloadText("discussion_event_id") == asked.eventId }
    if (about.any { it.kind == "room.activity" && it.payload.payloadText("status") in SETTLED }) return emptyList()
    val firstRound = about.filter { it.payload.payloadInt("round_index") == 0 }
    val answered = firstRound.filter { it.kind in ANSWERS }.mapNotNull { it.payload.payloadText("member_id") }.toSet()
    // A turn put off for later runs again, but after the others: the next one is working meanwhile.
    val putOff = firstRound.groupBy { it.payload.payloadText("member_id") }
        .filterValues { turns -> turns.last().kind == "turn.deferred" }.keys
    return roomRecipients(asked.messageText.orEmpty(), members)
        // A roster row with no member id (a legacy room) can't be told apart in the log: leave it out.
        .filter { it.memberId != null && it.memberId !in answered }
        .sortedBy { it.memberId in putOff }
}

/** What the transcript's status line says while the room works, from [waitingFor]: who's replying, and who's next. */
fun waitingLine(waiting: List<RoomMember>): String? {
    val now = waiting.firstOrNull() ?: return null
    val next = waiting.drop(1)
    return when {
        next.isEmpty() -> "${now.label} is replying…"
        else -> "${now.label} is replying · then ${next.joinToString(", ") { it.label }}"
    }
}

/** A member's first-round message, or how its turn ended. */
private val ANSWERS = setOf("message.member", "turn.settled", "turn.failed", "turn.cancelled")

/** `room.activity` statuses that close a discussion. */
private val SETTLED = setOf("settled", "bounded")
