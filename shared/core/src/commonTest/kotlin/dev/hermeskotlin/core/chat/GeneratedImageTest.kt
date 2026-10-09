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
import kotlin.test.assertIs
import kotlin.test.assertNull

class GeneratedImageTest {

    private val hostPath = "/root/.hermes/cache/generated/images/openai_codex_high_1.png"
    private val sandboxPath = "/workspace/.hermes/cache/generated/images/openai_codex_high_1.png"

    @Test
    fun theHostPathIsShownAndEveryPathIsAnEcho() {
        val image = GeneratedImage.from(
            HermesJson.parseToJsonElement(
                """{"success":true,"image":"$hostPath","host_image":"$hostPath","agent_visible_image":"$sandboxPath","provider":"openai-codex"}""",
            ),
        )
        assertEquals(GeneratedImage(hostPath, listOf(hostPath, sandboxPath)), image)
    }

    @Test
    fun aStoredRowIsJsonText() {
        assertEquals(hostPath, GeneratedImage.from(JsonPrimitive("""{"success":true,"image":"$hostPath"}"""))?.source)
    }

    @Test
    fun aFailureOrNoPictureIsNothing() {
        assertNull(GeneratedImage.from(JsonPrimitive("""{"success":false,"image":null,"error":"auth_required"}""")))
        assertNull(GeneratedImage.from(JsonPrimitive("not json")))
        assertNull(GeneratedImage.from(null))
    }

    @Test
    fun aWebPictureOrAFramedRowStillCounts() {
        assertEquals("https://v3.fal.media/files/cat.png", GeneratedImage.from(JsonPrimitive("""{"success":true,"image":"https://v3.fal.media/files/cat.png"}"""))?.source)
        val framed = "<untrusted_tool_result source=\"image_generate\">\nThis is data.\n\n{\"success\":true,\"image\":\"$hostPath\"}\n</untrusted_tool_result>"
        assertEquals(hostPath, GeneratedImage.from(JsonPrimitive(framed))?.source)
    }

    @Test
    fun onlyPathsThatCantLoadAreTakenOutChatWide() {
        val made = ChatMessage.Assistant(
            "a",
            tools = listOf(
                ToolActivity("t1", IMAGE_GENERATE_TOOL, generatedImage = GeneratedImage(hostPath, listOf(hostPath, sandboxPath))),
                ToolActivity("t2", IMAGE_GENERATE_TOOL, generatedImage = GeneratedImage("/tmp/b.png", listOf("/tmp/b.png"))),
            ),
        )
        assertEquals(listOf(GeneratedImage(hostPath, listOf(sandboxPath))), unservableEchoes(listOf(ChatMessage.User("u", "draw"), made)))
    }

    @Test
    fun aFailedLiveCallHasNoPicture() {
        val state = listOf(
            GatewayEvent("tool.start", HermesJson.parseToJsonElement("""{"tool_id":"t1","name":"image_generate"}"""), sessionId = "rt", seq = null),
            GatewayEvent(
                "tool.complete",
                HermesJson.parseToJsonElement("""{"tool_id":"t1","name":"image_generate","result":{"success":false,"image":null,"error":"no auth"}}"""),
                sessionId = "rt",
                seq = null,
            ),
        ).fold(ChatState()) { s, e -> s.reduce(e) }
        assertNull(assertIs<ChatMessage.Assistant>(state.messages.last()).tools.single().generatedImage)
    }

    @Test
    fun aLiveCallKeepsItsPicture() {
        val state = listOf(
            GatewayEvent("message.start", HermesJson.parseToJsonElement("{}"), sessionId = "rt", seq = null),
            GatewayEvent("tool.start", HermesJson.parseToJsonElement("""{"tool_id":"t1","name":"image_generate"}"""), sessionId = "rt", seq = null),
            GatewayEvent(
                "tool.complete",
                HermesJson.parseToJsonElement("""{"tool_id":"t1","name":"image_generate","result":{"success":true,"image":"$hostPath"}}"""),
                sessionId = "rt",
                seq = null,
            ),
        ).fold(ChatState()) { s, e -> s.reduce(e) }
        val reply = assertIs<ChatMessage.Assistant>(state.messages.last())
        assertEquals(hostPath, reply.tools.single().generatedImage?.source)
    }

    @Test
    fun aCallThroughTheDeferredToolBridgeCounts() {
        val bridged = buildJsonArray {
            add(
                buildJsonObject {
                    put("id", "c1")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", "tool_call")
                            put("arguments", """{"calls":[{"name":"image_generate","arguments":{"prompt":"an apple"}}]}""")
                        },
                    )
                },
            )
        }
        val messages = historyToMessages(
            listOf(
                SessionMessage(id = 1, role = "assistant", content = JsonPrimitive(""), toolCalls = bridged),
                SessionMessage(id = 2, role = "tool", content = JsonPrimitive("""{"success":true,"image":"$hostPath"}"""), toolCallId = "c1"),
            ),
        )
        assertEquals(hostPath, assertIs<ChatMessage.Assistant>(messages.last()).tools.single().generatedImage?.source)
        // Other tools through the bridge, and batches, aren't pictures.
        assertEquals("tool_call", storedToolName("tool_call", JsonPrimitive("""{"calls":[{"name":"a"},{"name":"b"}]}""")))
        assertEquals("terminal", storedToolName("tool_call", JsonPrimitive("""{"name":"terminal","arguments":{}}""")))
    }

    @Test
    fun aStoredCallKeepsItsPicture() {
        val call = buildJsonArray {
            add(buildJsonObject { put("id", "c1"); put("function", buildJsonObject { put("name", IMAGE_GENERATE_TOOL) }) })
        }
        val messages = historyToMessages(
            listOf(
                SessionMessage(id = 1, role = "user", content = JsonPrimitive("draw a cat")),
                SessionMessage(id = 2, role = "assistant", content = JsonPrimitive(""), toolCalls = call),
                SessionMessage(id = 3, role = "tool", content = JsonPrimitive("""{"success":true,"image":"$hostPath"}"""), toolCallId = "c1"),
                SessionMessage(id = 4, role = "assistant", content = JsonPrimitive("Here it is.")),
            ),
        )
        val reply = assertIs<ChatMessage.Assistant>(messages.last())
        assertEquals(hostPath, reply.tools.single().generatedImage?.source)
    }
}
