package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.GatewayEvent
import dev.hermeskotlin.core.sessions.SessionTotals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageTest {

    private fun event(type: String, payload: String = "{}") =
        GatewayEvent(type, HermesJson.parseToJsonElement(payload), sessionId = "rt", seq = null)

    private fun usage(input: Int, output: Int, calls: Int) =
        """{"input":$input,"output":$output,"reasoning":0,"calls":$calls,"context_used":9000,"context_max":200000,"context_percent":5}"""

    @Test
    fun aReplyCarriesWhatItsTurnTookOnTopOfEarlierTurns() {
        val start = ChatState().reduce(event("session.info", """{"usage":${usage(1_000, 200, 2)}}"""))
        val state = start.reduce(event("message.start"))
            .reduce(event("message.delta", """{"text":"Hi"}"""))
            .reduce(event("session.usage", """{"usage":${usage(3_000, 250, 3)}}"""))
            .reduce(event("message.complete", """{"text":"Hi","status":"complete","usage":${usage(4_500, 400, 4)}}"""))
        val reply = assertIs<ChatMessage.Assistant>(state.messages.single())
        assertEquals(TurnUsage(input = 3_500, output = 200, reasoning = 0, calls = 2), reply.usage)
        assertEquals(5, state.usage?.contextPercent)
        assertNull(state.turnStartUsage)
    }

    @Test
    fun aRebuiltAgentCountingFromZeroLeavesTheTurnUncounted() {
        val state = ChatState(usage = SessionUsage(input = 50_000, output = 900, calls = 9))
            .reduce(event("message.start"))
            .reduce(event("message.complete", """{"text":"Hi","status":"complete","usage":${usage(800, 40, 1)}}"""))
        assertNull(assertIs<ChatMessage.Assistant>(state.messages.single()).usage)
    }

    @Test
    fun anAgentNotYetBuiltCountsTheFirstTurnFromZero() {
        val state = ChatState()
            .reduce(event("message.start"))
            .reduce(event("message.complete", """{"text":"Hi","status":"complete","usage":${usage(800, 40, 1)}}"""))
        assertEquals(800, assertIs<ChatMessage.Assistant>(state.messages.single()).usage?.input)
    }

    @Test
    fun countsReadShort() {
        assertEquals("950", compactCount(950))
        assertEquals("12.3k", compactCount(12_345))
        assertEquals("40k", compactCount(40_020))
        assertEquals("1.2M", compactCount(1_234_567))
    }

    @Test
    fun aStoredRowGivesTheBilledCostOverTheEstimate() {
        val row = HermesJson.decodeFromString(
            SessionTotals.serializer(),
            """{"id":"s","input_tokens":1200,"output_tokens":null,"estimated_cost_usd":0.42,"actual_cost_usd":0.39,"cost_status":"actual"}""",
        )
        assertEquals(0.39, row.costUsd)
        assertTrue(!row.costIsEstimate)
        assertNull(row.outputTokens)
    }
}
