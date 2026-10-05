package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventReplayTest {

    private fun delta(seq: Long, text: String, session: String = "rt1") =
        """{"type":"message.delta","session_id":"$session","seq":$seq,"payload":{"text":"$text"}}"""

    private fun reply(vararg events: String, latest: Long, truncated: Boolean = false, epoch: String = "e1"): JsonObject =
        HermesJson.parseToJsonElement(
            """{"events":[${events.joinToString(",")}],"latest_seq":$latest,"truncated":$truncated,"count":${events.size},"epoch":"$epoch","open_requests":[]}""",
        ).jsonObject

    private fun texts(events: List<dev.hermeskotlin.core.rpc.GatewayEvent>?) =
        events?.map { (it.payload as JsonObject)["text"]!!.jsonPrimitive.content }

    @Test
    fun eventsRunningOnFromTheLastSeenComeBackInOrder() {
        val events = EventReplay.parse(reply(delta(4, "a"), delta(5, "b"), latest = 5), "rt1", from = 3, epoch = "e1")

        assertEquals(listOf("a", "b"), texts(events))
        assertEquals(listOf(4L, 5L), events?.map { it.seq })
        assertEquals("rt1", events?.first()?.sessionId)
    }

    @Test
    fun eventsAlreadyAppliedAreSkipped() {
        val events = EventReplay.parse(reply(delta(3, "seen"), delta(4, "new"), latest = 4), "rt1", from = 3, epoch = "e1")

        assertEquals(listOf("new"), texts(events))
    }

    @Test
    fun nothingMissedIsAnEmptyReplayNotAFailure() {
        assertEquals(emptyList(), EventReplay.parse(reply(latest = 7), "rt1", from = 7, epoch = "e1"))
    }

    @Test
    fun aLatestSeqAheadOfTheEventsIsLeftToTheLiveStream() {
        // Stamped between the ring read and latest_seq: it arrives live.
        assertEquals(listOf("a"), texts(EventReplay.parse(reply(delta(4, "a"), latest = 6), "rt1", from = 3, epoch = "e1")))
    }

    @Test
    fun aHoleInTheSeqsCantBeTrusted() {
        assertNull(EventReplay.parse(reply(delta(4, "a"), delta(6, "c"), latest = 6), "rt1", from = 3, epoch = "e1"))
        assertNull(EventReplay.parse(reply(delta(5, "b"), latest = 5), "rt1", from = 3, epoch = "e1"))
    }

    @Test
    fun aTruncatedRingCantBeTrusted() {
        assertNull(EventReplay.parse(reply(delta(4, "a"), latest = 4, truncated = true), "rt1", from = 3, epoch = "e1"))
    }

    @Test
    fun aRestartedGatewayCountsAnew() {
        assertNull(EventReplay.parse(reply(delta(4, "a"), latest = 4, epoch = "e2"), "rt1", from = 3, epoch = "e1"))
        // Without an epoch to compare, seqs behind the last seen give the restart away.
        assertNull(EventReplay.parse(reply(delta(1, "a"), latest = 1), "rt1", from = 3, epoch = null))
    }

    @Test
    fun anotherSessionsEventsOrAMalformedReplyAreRefused() {
        assertNull(EventReplay.parse(reply(delta(4, "a", session = "rt2"), latest = 4), "rt1", from = 3, epoch = "e1"))
        assertNull(EventReplay.parse(HermesJson.parseToJsonElement("""{"events":[]}""").jsonObject, "rt1", from = 3, epoch = "e1"))
        assertNull(EventReplay.parse(null, "rt1", from = 3, epoch = "e1"))
    }
}
