package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.GatewayEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoticesTest {

    private fun event(type: String, payload: String = "{}") =
        GatewayEvent(type, HermesJson.parseToJsonElement(payload), sessionId = "rt", seq = null)

    private val withTool = ChatState(running = true)
        .reduce(event("tool.start", """{"tool_id":"t1","name":"web_extract"}"""))
        .reduce(event("tool.complete", """{"tool_id":"t1","name":"web_extract","result_text":"page"}"""))

    private fun ChatState.tool() = messages.filterIsInstance<ChatMessage.Assistant>().single().tools.single()

    @Test
    fun aTurnWarningStaysOnItsReply() {
        val state = ChatState(running = true)
            .reduce(event("message.delta", """{"text":"Hi"}"""))
            .reduce(event("message.complete", """{"text":"Hi","status":"complete","warning":"Not saved."}"""))
        val reply = state.messages.single() as ChatMessage.Assistant
        assertEquals("Not saved.", reply.warning)
        assertEquals(TurnOutcome.Complete, reply.outcome)
    }

    @Test
    fun aWarningAloneStillShows() {
        val state = ChatState(running = true).reduce(event("message.complete", """{"text":"","status":"complete","warning":"Not saved."}"""))
        assertEquals("Not saved.", (state.messages.single() as ChatMessage.Assistant).warning)
    }

    @Test
    fun aRiskyOutputFlagsItsTool() {
        val state = withTool.reduce(
            event("tool.output_risk", """{"tool_id":"t1","name":"web_extract","risk":"high","findings":["prompt_injection","exfil_curl"],"redacted":false}"""),
        )
        val risk = state.tool().risk!!
        assertEquals(listOf("Prompt injection", "Exfil curl"), risk.labels)
        assertFalse(risk.redacted)
    }

    @Test
    fun aCleanScanLeavesTheToolAlone() {
        val state = withTool.reduce(event("tool.output_risk", """{"tool_id":"t1","name":"web_extract","risk":"low","findings":[]}"""))
        assertNull(state.tool().risk)
    }

    @Test
    fun aNoticeIsALineOfItsOwn() {
        val state = ChatState().reduce(event("notice", """{"message":"Tools refreshed."}"""))
        assertEquals("Tools refreshed.", (state.messages.single() as ChatMessage.Notice).text)
        assertFalse(state.hasConversation)
        assertTrue(ChatState().reduce(event("notice", """{"message":"  "}""")).messages.isEmpty())
    }
}
