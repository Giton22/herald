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
import kotlin.test.assertNull

class AgentScreenTest {

    private val create =
        """{"version":"v0.9","createSurface":{"surfaceId":"pick","catalogId":"https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json"}}"""
    private val components = """{"version":"v0.9.1","updateComponents":{"surfaceId":"pick","components":[{"id":"root","component":"Text","text":"Hi"}]}}"""

    private fun args(json: String) = HermesJson.parseToJsonElement(json)

    private fun layout(vararg components: String) = AgentScreen(
        listOf(create, """{"version":"v0.9","updateComponents":{"surfaceId":"pick","components":[${components.joinToString(",")}]}}"""),
        setOf("pick"),
        wait = true,
    ).layoutProblem

    /** A column [id] holding [children]. */
    private fun column(id: String, vararg children: String) =
        """{"id":"$id","component":"Column","children":[${children.joinToString(",") { "\"$it\"" }}]}"""

    @Test
    fun aLayoutThatHoldsItselfIsTurnedDown() {
        assertNull(layout(column("root", "a", "b"), """{"id":"a","component":"Text","text":"root"}""", column("b")))
        // Straight, through a card's child, through a list's template and away from root.
        listOf(
            arrayOf(column("root", "root")),
            arrayOf(column("root", "card"), """{"id":"card","component":"Card","child":"root"}"""),
            arrayOf(column("root", "list"), """{"id":"list","component":"List","children":{"componentId":"list","path":"/items"}}"""),
            arrayOf(column("root"), column("x", "y"), column("y", "x")),
        ).forEach { components ->
            assertEquals(true, layout(*components)?.contains("contains itself"), components.joinToString())
        }
    }

    @Test
    fun aLayoutTooDeepOrTooBigIsTurnedDown() {
        fun chain(depth: Int) = (0 until depth).map { i -> column(if (i == 0) "root" else "c$i", "c${i + 1}") }
        assertNull(layout(*chain(AgentScreen.MAX_DEPTH - 1).toTypedArray()))
        assertEquals(true, layout(*chain(AgentScreen.MAX_DEPTH + 5).toTypedArray())?.contains("deep"))
        // Listed leaf first, so the deepest part is looked at before root.
        assertEquals(true, layout(*chain(AgentScreen.MAX_DEPTH + 5).reversed().toTypedArray())?.contains("deep"))
        val many = (0..AgentScreen.MAX_COMPONENTS).map { """{"id":"t$it","component":"Text","text":"x"}""" }
        assertEquals(true, layout(column("root"), *many.toTypedArray())?.contains("more than"))
    }

    @Test
    fun otherToolsArePassedByUnread() {
        assertNull(AgentScreen.from("terminal", args("""{"messages":[$create,$components]}""")))
    }

    @Test
    fun theMessagesAreKeptInOrderWithTheirSurface() {
        val screen = AgentScreen.from(SHOW_UI_TOOL, args("""{"messages":[$create,$components]}"""))!!
        assertEquals(listOf(create, components).map { HermesJson.parseToJsonElement(it).toString() }, screen.messages)
        assertEquals(setOf("pick"), screen.surfaceIds)
        // Waiting for a tap is what the tool does unless told otherwise.
        assertEquals(true, screen.wait)
    }

    @Test
    fun messagesWrittenAsOneStringAreRead() {
        val list = AgentScreen.from(SHOW_UI_TOOL, args("""{"messages":${JsonPrimitive("[$create,$components]")},"wait":false}"""))!!
        assertEquals(2, list.messages.size)
        assertFalse(list.wait)
        val lines = AgentScreen.from(SHOW_UI_TOOL, args("""{"messages":${JsonPrimitive("$create\n$components\n")}}"""))!!
        assertEquals(2, lines.messages.size)
        // A stored call's arguments are JSON text.
        assertEquals(setOf("pick"), AgentScreen.from(SHOW_UI_TOOL, JsonPrimitive("""{"messages":[$create]}"""))?.surfaceIds)
    }

    @Test
    fun aCallThroughTheToolBridgeIsRead() {
        val bridged = args("""{"calls":[{"name":"$SHOW_UI_TOOL","arguments":{"messages":[$create]}}]}""")
        assertEquals(setOf("pick"), AgentScreen.from("tool_call", bridged)?.surfaceIds)
        assertNull(AgentScreen.from("tool_call", args("""{"calls":[{"name":"terminal","arguments":{"messages":[$create]}}]}""")))
    }

    @Test
    fun otherToolsAndOddMessagesAreNoScreen() {
        assertNull(AgentScreen.from("terminal", args("""{"messages":[$create]}""")))
        assertNull(AgentScreen.from(SHOW_UI_TOOL, args("""{"messages":[]}""")))
        assertNull(AgentScreen.from(SHOW_UI_TOOL, args("""{"messages":[$create, "text"]}""")))
        assertNull(AgentScreen.from(SHOW_UI_TOOL, args("""{"messages":"not json"}""")))
        // Without a surface there's nothing to draw.
        assertNull(AgentScreen.from(SHOW_UI_TOOL, args("""{"messages":[{"version":"v0.9.1"}]}""")))
        assertNull(AgentScreen.from(SHOW_UI_TOOL, null))
        val tooMany = (0..AgentScreen.MAX_MESSAGES).joinToString(",") { create }
        assertNull(AgentScreen.from(SHOW_UI_TOOL, args("""{"messages":[$tooMany]}""")))
    }

    @Test
    fun aStoredCallCarriesItsScreen() {
        // The transcript keeps a call's arguments as JSON text.
        val calls = buildJsonArray {
            add(
                buildJsonObject {
                    put("id", "c1")
                    put("function", buildJsonObject { put("name", SHOW_UI_TOOL); put("arguments", """{"messages":[$create]}""") })
                },
            )
        }
        val messages = historyToMessages(
            listOf(
                SessionMessage(id = 1, role = "user", content = JsonPrimitive("pick a time")),
                SessionMessage(id = 2, role = "assistant", content = JsonPrimitive(""), toolCalls = calls),
                SessionMessage(id = 3, role = "tool", content = JsonPrimitive("""{"event":"pick"}"""), toolCallId = "c1"),
                SessionMessage(id = 4, role = "assistant", content = JsonPrimitive("Booked.")),
            ),
        )
        val tool = assertIs<ChatMessage.Assistant>(messages.last()).tools.single()
        assertEquals(setOf("pick"), tool.agentScreen?.surfaceIds)
        assertFalse(tool.running)
    }

    @Test
    fun aLiveCallCarriesItsScreen() {
        val state = ChatState().reduce(
            GatewayEvent(
                "tool.start",
                HermesJson.parseToJsonElement("""{"tool_id":"t1","name":"$SHOW_UI_TOOL","args":{"messages":[$create]}}"""),
                sessionId = "rt",
                seq = null,
            ),
        )
        val tool = assertIs<ChatMessage.Assistant>(state.messages.last()).tools.single()
        assertEquals(setOf("pick"), tool.agentScreen?.surfaceIds)
    }
}
