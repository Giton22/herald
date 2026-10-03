package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.GatewayEvent
import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolDetailsTest {

    private fun json(text: String) = HermesJson.parseToJsonElement(text)

    @Test
    fun aLoneTextArgumentShowsAsItself() {
        assertEquals("ls -la", ToolDetails.input(json("""{"command":"ls -la"}""")))
        assertEquals("{\n    \"path\": \"a.txt\",\n    \"limit\": 5\n}", ToolDetails.input(json("""{"path":"a.txt","limit":5}""")))
        // Stored calls keep arguments as JSON text.
        assertEquals("ls -la", ToolDetails.input(JsonPrimitive("""{"command":"ls -la"}""")))
    }

    @Test
    fun theTerminalsOutputShowsWithAFailingExitCode() {
        val result = json("""{"output":"No such file","exit_code":2}""")
        assertEquals("No such file\n\nExit code 2", ToolDetails.output(result))
        assertTrue(ToolDetails.failed(result))
        assertFalse(ToolDetails.failed(json("""{"output":"ok","exit_code":0,"error":null}""")))
        assertTrue(ToolDetails.failed(JsonPrimitive("""{"success":false,"error":"denied"}""")))
    }

    @Test
    fun liveEventsFillTheCard() {
        val state = ChatState(running = true)
            .reduce(GatewayEvent("tool.start", json("""{"tool_id":"t1","name":"terminal","context":"ls","args":{"command":"ls"}}"""), "rt", null))
            .reduce(GatewayEvent("tool.complete", json("""{"tool_id":"t1","name":"terminal","result":{"output":"a.txt","exit_code":0},"duration_s":0.2,"inline_diff":""}"""), "rt", null))
        val tool = assertIs<ChatMessage.Assistant>(state.messages.single()).tools.single()
        assertEquals("ls", tool.input)
        assertEquals("a.txt", tool.output)
        assertEquals(null, tool.diff)
        assertFalse(tool.failed)
    }

    @Test
    fun historyPairsEachCallWithItsResult() {
        val calls = buildJsonArray {
            add(buildJsonObject {
                put("id", "call-1")
                put("function", buildJsonObject { put("name", "terminal"); put("arguments", """{"command":"cat x"}""") })
            })
        }
        val messages = historyToMessages(
            listOf(
                SessionMessage(id = 1, role = "user", content = JsonPrimitive("read x")),
                SessionMessage(id = 2, role = "assistant", content = JsonPrimitive(""), toolCalls = calls),
                SessionMessage(id = 3, role = "tool", content = JsonPrimitive("""{"output":"","error":"missing"}"""), toolCallId = "call-1"),
                SessionMessage(id = 4, role = "assistant", content = JsonPrimitive("x is missing.")),
            ),
        )
        val tool = assertIs<ChatMessage.Assistant>(messages.last()).tools.single()
        assertEquals("cat x", tool.input)
        assertEquals("missing", tool.output)
        assertTrue(tool.failed)
    }
}
