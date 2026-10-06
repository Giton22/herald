package dev.hermeskotlin.core.rooms

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomsTest {

    private val members = listOf(
        RoomMember(memberId = "ops", profile = "ops", handle = "ops", displayName = "Ops Bot"),
        RoomMember(memberId = "scribe", profile = "scribe", handle = "scribe"),
    )

    private fun event(seq: Int, kind: String, payload: JsonObject = JsonObject(emptyMap()), actor: RoomActor = RoomActor("gateway", "gw-1")) =
        RoomEvent(roomId = "r1", seq = seq, eventId = "e:$seq", kind = kind, actor = actor, payload = payload, createdAt = 0.0)

    @Test
    fun theTranscriptDrawsMessagesFailuresAndRenamesAndHidesTheMachinery() {
        val user = event(
            1, "message.user",
            buildJsonObject { put("text", "hello everyone"); put("thread_id", "thread-1") },
            RoomActor("user", "desktop"),
        )
        val member = event(2, "message.member", buildJsonObject { put("text", "on it"); put("member_id", "ops") })
        val started = event(3, "turn.started", buildJsonObject { put("member_id", "ops") })
        val failed = event(4, "turn.failed", buildJsonObject { put("member_id", "scribe"); put("error", "the model said no") })
        val renamed = event(5, "room.renamed", buildJsonObject { put("name", "Release room") })

        val lines = roomLines(listOf(user, member, started, failed, renamed), members)

        assertEquals(4, lines.size)
        assertEquals(RoomLine.Message(1, fromUser = true, speaker = null, text = "hello everyone", eventId = "e:1"), lines[0])
        // The member's profile comes along, for its face and color.
        assertEquals(RoomLine.Message(2, fromUser = false, speaker = "Ops Bot", text = "on it", eventId = "e:2", profile = "ops"), lines[1])
        val failure = lines[2] as RoomLine.System
        assertTrue(failure.text.contains("scribe"))
        assertTrue(failure.text.contains("the model said no"))
        assertTrue((lines[3] as RoomLine.System).text.contains("Release room"))
    }

    @Test
    fun aMemberWithNoDisplayNameFallsBackToItsProfile() {
        val member = event(1, "message.member", buildJsonObject { put("text", "hi"); put("member_id", "scribe") })
        val line = roomLines(listOf(member), members).single() as RoomLine.Message
        assertEquals("scribe", line.speaker)
    }

    @Test
    fun aMessageKeepsItsTimeAndAMemberOffTheRosterStillHasAFace() {
        val late = RoomEvent(
            roomId = "r1", seq = 1, eventId = "e:1", kind = "message.member", actor = RoomActor("member", "ghost"),
            payload = buildJsonObject { put("text", "still here"); put("member_id", "ghost") }, createdAt = 1_700_000_000.0,
        )
        val line = roomLines(listOf(late), members).single() as RoomLine.Message
        assertEquals(1_700_000_000.0, line.createdAt)
        // Not on the roster any more: its id is the best guess at its profile.
        assertEquals("ghost", line.profile)
    }

    @Test
    fun unknownAndBlankLinesDrawNothing() {
        assertNull(roomLine(event(1, "room.activity", buildJsonObject { put("status", "idle") }), members))
        assertNull(roomLine(event(2, "message.user", buildJsonObject { put("text", "  ") }), members))
    }

    @Test
    fun mergingPagesKeepsOneCopyOfEverythingInOrder() {
        val a = event(1, "message.user")
        val b = event(2, "message.member")
        val c = event(3, "turn.failed")
        assertEquals(listOf(a, b, c), mergeRoomEvents(listOf(a, b), listOf(b, c)))
        assertEquals(listOf(a, b), mergeRoomEvents(listOf(a, b), listOf(a, b)))
        assertEquals(listOf(a, b), mergeRoomEvents(listOf(a, b), emptyList()))
    }
}
