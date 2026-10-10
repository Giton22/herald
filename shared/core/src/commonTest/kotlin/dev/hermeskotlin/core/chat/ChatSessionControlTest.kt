package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.rpc.event
import dev.hermeskotlin.core.rpc.isCall
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.rpc.param
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading and driving a session's goal, loop and heartbeat (`session.control.read`, `session.control`). */
class ChatSessionControlTest {

    private val url = FakeGateway.URL

    private var history = """{"session_id":"stored-1","messages":[
        {"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"Hi! What next?"}]}"""

    /** The gateway of the latest [setup]; its client reads [history]. */
    private lateinit var gateway: FakeGateway

    private fun client() = gateway.client

    /** Canned results by method (`{}` otherwise); every connect opens a fresh socket when [reconnects]. */
    private suspend fun gateway(scope: CoroutineScope, results: Map<String, String>, reconnects: Boolean) =
        FakeGateway(scope, reconnects).also { gateway = it }.apply {
            answer = { call -> results[call.method] ?: "{}" }
            http = { request ->
                require(request.url.encodedPath == "/api/sessions/stored-1/messages") { "unexpected ${request.url}" }
                json(history)
            }
            start(awaitConnected = false)
        }

    private suspend fun setup(scope: CoroutineScope, results: Map<String, String>): ChatSession {
        val connection = gateway(scope, results, reconnects = false).connection
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), scope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        return chat
    }

    private val RESUME = """{"session_id":"rt1","running":false}"""

    @Test
    fun attachReadsTheControlSnapshotOnce() = runTest {
        val chat = setup(
            backgroundScope,
            mapOf(
                "session.resume" to RESUME,
                "session.control.read" to """{"control":${ControlFixtures.GOAL_ACTIVE}}""",
            ),
        )

        val state = chat.state.first { it.control != null }

        assertEquals("Get the test suite green on the feat/goals branch", state.control?.goal?.title)
        val read = gateway.sent("session.control.read").single()
        assertEquals("rt1", read.param("session_id"))
    }

    @Test
    fun aGatewayWithoutSessionControlIsNotAskedAgainAfterAReconnect() = runTest {
        val connection = gateway(
            backgroundScope,
            mapOf("session.resume" to RESUME, "session.control.read" to "error:-32601"),
            reconnects = true,
        ).connection
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" }
        gateway.socket.awaitSent { it.isCall("session.control.read") }
        assertNull(chat.state.value.control)

        gateway.socket.serverClose(1006)
        chat.state.first { it.runtimeSessionId == null }
        // The re-attach's read would land on the fresh socket; the latch holds it back.
        chat.state.first { it.runtimeSessionId == "rt1" }
        // Let the re-attach's launched refresh run before counting, so a missing latch is caught for sure.
        kotlinx.coroutines.delay(1_000)

        assertEquals(1, gateway.sent("session.control.read").size)
    }

    @Test
    fun anUpdateThatLandsWhileTheReadIsOutWinsOverTheReadsAnswer() = runTest {
        val connection = gateway(backgroundScope, mapOf("session.resume" to RESUME), reconnects = false).connection
        var read: FakeGateway.Call? = null
        gateway.answer = { call ->
            when (call.method) {
                "session.resume" -> RESUME
                // Held: answered by hand below, after the update.
                "session.control.read" -> null.also { read = call }
                else -> "{}"
            }
        }
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        gateway.socket.awaitSent { it.isCall("session.control.read") }

        // Paused on Desktop while the read is out.
        gateway.socket.push(event("session.control.update", "rt1", """{"control":${ControlFixtures.GOAL_PAUSED}}"""))
        chat.state.first { it.control != null }
        // Then the read's answer, from before the pause.
        gateway.socket.push(read!!.reply("""{"control":${ControlFixtures.GOAL_ACTIVE}}"""))
        kotlinx.coroutines.delay(1_000)

        assertEquals("paused", chat.state.value.control?.goal?.status)
    }

    @Test
    fun aResumedGoalWhoseKickoffFailsSaysSo() = runTest {
        val chat = setup(
            backgroundScope,
            mapOf(
                "session.resume" to RESUME,
                "session.control" to """{"control":${ControlFixtures.GOAL_ACTIVE},""" +
                    """"dispatch":{"type":"send","output":null,"notice":null,"message":"Kick off the goal work now.","display":"/goal resume"}}""",
                "prompt.submit" to "error:5000:agent unavailable",
            ),
        )

        val outcome = chat.runControl(ControlAction.GoalResume)

        assertEquals("The goal is resumed, but its next step didn't go out. Resend it from the chat.", outcome)
    }

    @Test
    fun anUpdateEventSetsAndAnEmptyOneClearsTheControl() = runTest {
        val chat = setup(backgroundScope, mapOf("session.resume" to RESUME))
        val transport = gateway.socket

        transport.push(event("session.control.update", "rt1", """{"control":${ControlFixtures.GOAL_PAUSED}}"""))
        assertEquals("paused", chat.state.first { it.control != null }.control?.goal?.status)

        transport.push(event("session.control.update", "rt1", """{"control":${ControlFixtures.EMPTY}}"""))
        assertNull(chat.state.first { it.control == null }.control)
    }

    @Test
    fun addingASubgoalSendsItsArgsAndAppliesTheReturnedSnapshot() = runTest {
        val chat = setup(
            backgroundScope,
            mapOf(
                "session.resume" to RESUME,
                "session.control" to """{"control":${ControlFixtures.GOAL_ACTIVE},""" +
                    """"dispatch":{"type":null,"output":null,"notice":null,"message":null,"display":null}}""",
            ),
        )

        assertNull(chat.runControl(ControlAction.SubgoalAdd, text = "Pin the wire contract"))

        val call = gateway.sent("session.control").single()
        assertEquals("subgoal.add", call.param("action"))
        assertEquals(
            "Pin the wire contract",
            call["params"]!!.jsonObject.getValue("args").jsonObject.getValue("text").jsonPrimitive.content,
        )
        assertEquals(2, chat.state.first { it.control != null }.control?.goal?.subgoals?.size)
    }

    @Test
    fun resumingAGoalSendsTheDispatchMessageShowingItsCommand() = runTest {
        val chat = setup(
            backgroundScope,
            mapOf(
                "session.resume" to RESUME,
                "session.control" to """{"control":${ControlFixtures.GOAL_ACTIVE},""" +
                    """"dispatch":{"type":"send","output":null,"notice":null,"message":"Kick off the goal work now.","display":"/goal resume"}}""",
                "prompt.submit" to """{"status":"streaming","user_row_id":7}""",
            ),
        )

        assertNull(chat.runControl(ControlAction.GoalResume))

        val submit = gateway.sent("prompt.submit").single()
        assertEquals("Kick off the goal work now.", submit.param("text"))
        assertNull(submit.param("queued"))
        val bubble = assertIs<ChatMessage.User>(chat.state.value.messages.last())
        assertEquals("/goal resume", bubble.text)
    }

    @Test
    fun resumingAGoalMidTurnQueuesTheKickoffNeverSteers() = runTest {
        val chat = setup(
            backgroundScope,
            mapOf(
                "session.resume" to """{"session_id":"rt1","running":true}""",
                "session.control" to """{"control":${ControlFixtures.GOAL_ACTIVE},""" +
                    """"dispatch":{"type":"send","output":null,"notice":null,"message":"Kick off the goal work now.","display":"/goal resume"}}""",
                "prompt.submit" to """{"status":"queued","user_row_id":8}""",
            ),
        )

        assertNull(chat.runControl(ControlAction.GoalResume))

        val submit = gateway.sent("prompt.submit").single()
        assertEquals("Kick off the goal work now.", submit.param("text"))
        assertEquals("true", submit.param("queued"))
        val bubble = assertIs<ChatMessage.User>(chat.state.value.messages.last { it is ChatMessage.User })
        assertEquals("/goal resume", bubble.text)
        assertTrue(bubble.queued)
    }

    @Test
    fun aBadActionComesBackAsTheErrorAndLeavesTheState() = runTest {
        val chat = setup(backgroundScope, mapOf("session.resume" to RESUME))
        gateway.answer = { call ->
            when (call.method) {
                "session.control" -> call.error(4004, "/subgoal: no goal is set")
                else -> "{}"
            }
        }

        val error = chat.runControl(ControlAction.SubgoalClear)

        assertEquals("/subgoal: no goal is set", error)
        assertNull(chat.state.value.control)
        assertNull(chat.state.value.error)
    }

    @Test
    fun controlActionsNeedTheGateway() = runTest {
        val chat = setup(backgroundScope, mapOf("session.resume" to RESUME))
        gateway.socket.serverClose(1006)
        chat.state.first { it.runtimeSessionId == null }

        assertEquals("Not connected to the gateway.", chat.runControl(ControlAction.GoalPause))
    }
}
