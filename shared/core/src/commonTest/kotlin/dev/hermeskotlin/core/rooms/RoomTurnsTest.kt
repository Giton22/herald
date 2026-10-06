package dev.hermeskotlin.core.rooms

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Shapes as the gateway wrote them on the emulator (hermes-agent v2026.9.24), trimmed to what's read. */
class RoomTurnsTest {

    private val main = RoomMember(memberId = "default", profile = "default", handle = "default", displayName = "Main")
    private val side = RoomMember(memberId = "side", profile = "side", handle = "side", displayName = "Side")
    private val members = listOf(main, side)

    private fun event(seq: Int, kind: String, id: String = "e:$seq", payload: JsonObject) =
        RoomEvent(roomId = "r", seq = seq, eventId = id, kind = kind, actor = RoomActor("gateway", "gw"), payload = payload)

    private fun asked(seq: Int, text: String) = event(seq, "message.user", id = "user:$seq", payload = buildJsonObject {
        put("text", text); put("thread_id", "main")
    })

    private fun answer(seq: Int, kind: String, about: Int, member: String, round: Int = 0, passed: Boolean? = null) =
        event(seq, kind, payload = buildJsonObject {
            put("discussion_event_id", "user:$about"); put("member_id", member); put("round_index", round)
            if (passed != null) put("passed", passed)
            if (kind == "message.member") put("text", "ok")
        })

    private fun settled(seq: Int, about: Int) = event(seq, "room.activity", payload = buildJsonObject {
        put("discussion_event_id", "user:$about"); put("status", "settled"); put("reason_code", "silent_round")
    })

    private fun waiting(vararg events: RoomEvent) = waitingFor(events.toList(), members).map { it.memberId }

    @Test
    fun everyoneAskedWaitsInRosterOrderUntilEachAnswers() {
        assertEquals(listOf("default", "side"), waiting(asked(1, "each of you, one word")))
        assertEquals(listOf("side"), waiting(asked(1, "hi"), answer(2, "message.member", 1, "default"), answer(3, "turn.settled", 1, "default")))
        assertEquals(emptyList(), waiting(asked(1, "hi"), answer(2, "message.member", 1, "default"), answer(4, "message.member", 1, "side")))
    }

    @Test
    fun aMentionWaitsOnlyForTheOneNamed() {
        assertEquals(listOf("side"), waiting(asked(1, "@side yes or no?")))
    }

    @Test
    fun aFailureOrAPassIsAnAnswerToo() {
        assertEquals(listOf("side"), waiting(asked(1, "hi"), answer(2, "turn.failed", 1, "default")))
        assertEquals(emptyList(), waiting(asked(1, "hi"), answer(2, "turn.settled", 1, "default", passed = true), answer(3, "turn.cancelled", 1, "side")))
    }

    @Test
    fun onlyTheNewestMessageAndItsFirstRoundCount() {
        // An answer to an older message, or a later round, doesn't settle the new one.
        assertEquals(
            listOf("default", "side"),
            waiting(asked(1, "first"), answer(2, "message.member", 1, "default"), asked(3, "second"), answer(4, "message.member", 3, "side", round = 1)),
        )
    }

    @Test
    fun aSettledDiscussionWaitsForNoOne() {
        assertEquals(emptyList(), waiting(asked(1, "hi"), settled(2, 1)))
        assertEquals(emptyList(), waiting())
    }

    @Test
    fun theStatusLineNamesWhoIsReplyingAndWhoIsNext() {
        assertEquals("Main is replying · then Side", waitingLine(listOf(main, side)))
        assertEquals("Side is replying…", waitingLine(listOf(side)))
        assertNull(waitingLine(emptyList()))
    }

    @Test
    fun aPassShowsAsAQuietLine() {
        val line = roomLine(answer(5, "turn.settled", 1, "side", passed = true), members) as RoomLine.System
        assertEquals("Side had nothing to add.", line.text)
        assertNull(roomLine(answer(6, "turn.settled", 1, "side", passed = false), members))
    }
}
