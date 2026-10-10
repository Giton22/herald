package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.cache.InMemoryOfflineDao
import dev.hermeskotlin.core.cache.OfflineCache
import dev.hermeskotlin.core.cache.PlainSealer
import dev.hermeskotlin.core.cache.SavedTranscript
import kotlin.coroutines.EmptyCoroutineContext
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.rpc.event
import dev.hermeskotlin.core.rpc.isCall
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.rpc.param
import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.slash.SlashCommand
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatSessionTest {

    private val url = FakeGateway.URL

    private var history = """{"session_id":"stored-1","messages":[
        {"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"Hi! What next?"}]}"""

    /** The transcript can't be read while this is set. */
    private var historyFails = false

    /** The stored row doesn't exist yet while this is set, as for a new chat before its first prompt. */
    private var historyMissing = false

    /** The gateway of the latest [setup]; its client reads [history]. */
    private lateinit var gateway: FakeGateway

    private fun client() = gateway.client

    /**
     * A gateway answering each call with the canned result for its method (`{}` otherwise, [SILENT] never answers;
     * see [FakeGateway.answer]). Started but not waited on: a chat started next sees the link come up.
     */
    private suspend fun gateway(scope: CoroutineScope, results: Map<String, String>, reconnects: Boolean) =
        FakeGateway(scope, reconnects).also { gateway = it }.apply {
            answer = { call -> (results[call.method] ?: "{}").takeIf { it != SILENT } }
            http = { request ->
                when {
                    request.url.encodedPath != "/api/sessions/stored-1/messages" -> error("unexpected ${request.url}")
                    historyFails -> json("{}", HttpStatusCode.InternalServerError)
                    historyMissing -> json("{}", HttpStatusCode.NotFound)
                    else -> json(history)
                }
            }
            start(awaitConnected = false)
        }

    private suspend fun setup(scope: CoroutineScope, results: Map<String, String>): Pair<GatewayConnection, FakeTransport> =
        gateway(scope, results, reconnects = false).let { it.connection to it.socket }

    /** Like [setup], but every connect opens a fresh transport, so a dropped link comes back; the latest is last. */
    private suspend fun reconnectingSetup(scope: CoroutineScope, results: Map<String, String>): Pair<GatewayConnection, List<FakeTransport>> =
        gateway(scope, results, reconnects = true).let { it.connection to it.sockets }

    private fun ChatMessage.textOf() = when (this) {
        is ChatMessage.User -> text
        is ChatMessage.Assistant -> text
        is ChatMessage.Command -> output
        is ChatMessage.Notice -> text
        is ChatMessage.Event -> event.toString()
    }

    @Test
    fun resumesStoredSessionAndStreamsItsTurn() = runTest {
        val (connection, transport) = setup(backgroundScope, mapOf("session.resume" to """{"session_id":"rt1","running":false,"info":{"model":"m1"}}"""))
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()

        val resume = transport.awaitSent { it.isCall("session.resume") }
        assertEquals("stored-1", resume.param("session_id"))
        assertEquals("true", resume.param("omit_messages"))
        assertEquals("desktop", resume.param("source"))
        val attached = chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        assertEquals(2, attached.messages.size)
        assertEquals("m1", attached.model)

        transport.push(event("message.delta", "someone-else", """{"text":"not ours"}"""))
        transport.push(event("message.start", "rt1"))
        transport.push(event("message.delta", "rt1", """{"text":"Streaming"}"""))
        // No prompt of ours started this turn, so the stored rows replace the live copy when it ends.
        history = """{"session_id":"stored-1","messages":[
            {"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"Hi! What next?"},
            {"id":3,"role":"user","content":"go on"},{"id":4,"role":"assistant","content":"Streaming"}]}"""
        transport.push(event("message.complete", "rt1", """{"text":"Streaming","status":"complete"}"""))

        val done = chat.state.first { s -> !s.running && (s.messages.last() as? ChatMessage.Assistant)?.key == "row-4" }
        assertEquals("Streaming", (done.messages.last() as ChatMessage.Assistant).text)
    }

    @Test
    fun aTimedNoticeGoesByItselfAndAStickyOneStays() = runTest {
        val (connection, transport) = setup(backgroundScope, mapOf("session.resume" to """{"session_id":"rt1","running":false}"""))
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }

        transport.push(event("notification.show", "rt1", """{"text":"✕ Credit access paused","level":"error","kind":"sticky","key":"credits.depleted"}"""))
        transport.push(event("notification.show", "rt1", """{"text":"✓ Credit access restored","level":"success","kind":"ttl","ttl_ms":8000,"key":"credits.restored"}"""))
        assertEquals(2, chat.state.first { it.notices.size == 2 }.notices.size)

        // Nothing on screen runs the timer: the session does.
        val after = chat.state.first { it.notices.none { n -> n.key == "credits.restored" } }
        assertEquals(listOf("credits.depleted"), after.notices.map { it.key })
        assertTrue(testScheduler.currentTime >= 8_000)
    }

    @Test
    fun aTurnStartedByAnotherClientPullsItsPromptFromTheTranscript() = runTest {
        val (connection, transport) = setup(backgroundScope, mapOf("session.resume" to """{"session_id":"rt1","running":false}"""))
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }

        // Desktop sends "nice": the gateway stores it, but only the reply streams to us.
        history = """{"session_id":"stored-1","messages":[
            {"id":1,"role":"user","content":"hello"},{"id":2,"role":"assistant","content":"Hi! What next?"},
            {"id":3,"role":"user","content":"nice"}]}"""
        transport.push(event("message.start", "rt1"))
        transport.push(event("message.delta", "rt1", """{"text":"Glad"}"""))

        val live = chat.state.first { s -> s.messages.any { it is ChatMessage.User && it.text == "nice" } }
        val reply = assertIs<ChatMessage.Assistant>(live.messages.last())
        assertTrue(reply.streaming)

        history = history.replace("""{"id":3,"role":"user","content":"nice"}]""", """{"id":3,"role":"user","content":"nice"},{"id":4,"role":"assistant","content":"Glad you like it"}]""")
        transport.push(event("message.complete", "rt1", """{"text":"Glad you like it","status":"complete"}"""))

        val done = chat.state.first { s -> !s.running && (s.messages.last() as? ChatMessage.Assistant)?.key == "row-4" }
        assertEquals(listOf("hello", "Hi! What next?", "nice", "Glad you like it"), done.messages.map { it.textOf() })
    }

    @Test
    fun firstSendOnANewChatCreatesTheSessionThenSubmits() = runTest {
        val (connection, transport) = setup(
            backgroundScope,
            mapOf(
                "session.create" to """{"session_id":"rt9","stored_session_id":"stored-9","message_count":0,"messages":[],"info":{}}""",
                "prompt.submit" to """{"status":"streaming"}""",
            ),
        )
        connection.state.first { it is ConnectionState.Connected }
        val sentAt = Instant.fromEpochSeconds(1_700_000_000)
        val clock = object : Clock { override fun now() = sentAt }
        val chat = ChatSession(url, null, null, connection, SessionsApi(client()), backgroundScope, clock = clock)
        chat.start()

        assertTrue(chat.send("  write a haiku "))

        val submit = transport.awaitSent { it.isCall("prompt.submit") }
        assertEquals("rt9", submit.param("session_id"))
        assertEquals("write a haiku", submit.param("text"))
        val state = chat.state.value
        assertEquals("stored-9", state.storedSessionId)
        assertTrue(state.running)
        val user = assertIs<ChatMessage.User>(state.messages.single())
        assertFalse(user.pending)
        // Dated when this device sent it.
        assertEquals(1_700_000_000.0, user.timestamp)
    }

    @Test
    fun aVoiceLiveDelegationTellsTheGatewayItWasSpoken() = runTest {
        val (connection, transport) = setup(
            backgroundScope,
            mapOf(
                "session.create" to """{"session_id":"rt9","stored_session_id":"stored-9","message_count":0,"messages":[],"info":{}}""",
                "prompt.submit" to """{"status":"streaming"}""",
            ),
        )
        connection.state.first { it is ConnectionState.Connected }
        val chat = ChatSession(url, null, null, connection, SessionsApi(client()), backgroundScope)
        chat.start()

        assertEquals(SendOutcome.Sent, chat.submit("Thursday, not Friday.", voiceLive = VoiceLiveTurn("User: Book it for Friday.\nUser: Thursday, not Friday.")))

        val submit = transport.awaitSent { it.isCall("prompt.submit") }
        assertEquals("Thursday, not Friday.", submit.param("text"))
        assertEquals("voice-live", submit.param("surface"))
        assertEquals("User: Book it for Friday.\nUser: Thursday, not Friday.", submit.param("voice_context"))
        // A typed prompt carries neither.
        chat.send("typed")
        val typed = transport.sent.first { sent -> sent.count { it.isCall("prompt.submit") } == 2 }.last { it.isCall("prompt.submit") }
        assertNull(typed.param("surface"))
        assertNull(typed.param("voice_context"))
    }

    @Test
    fun aCorrectionMidTurnSplitsTheReplyAroundIt() = runTest {
        val (connection, transport) = setup(
            backgroundScope,
            mapOf(
                "session.resume" to """{"session_id":"rt1","running":false}""",
                "prompt.submit" to """{"status":"redirected","text":"in French"}""",
            ),
        )
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        transport.push(event("message.start", "rt1"))
        transport.push(event("message.delta", "rt1", """{"text":"Hello there"}"""))
        chat.state.first { s -> (s.messages.last() as? ChatMessage.Assistant)?.text == "Hello there" }

        assertTrue(chat.send("in French"))
        transport.push(event("message.delta", "rt1", """{"text":"Bonjour"}"""))

        val live = chat.state.first { s -> (s.messages.last() as? ChatMessage.Assistant)?.text == "Bonjour" }
        assertEquals(listOf("hello", "Hi! What next?", "Hello there", "in French", "Bonjour"), live.messages.map { it.textOf() })
        assertFalse(assertIs<ChatMessage.Assistant>(live.messages[2]).streaming)
        assertFalse(assertIs<ChatMessage.User>(live.messages[3]).queued)
    }

    @Test
    fun queueingMidTurnAsksForTheNextTurn() = runTest {
        val (connection, transport) = setup(
            backgroundScope,
            mapOf(
                "session.resume" to """{"session_id":"rt1","running":false}""",
                "prompt.submit" to """{"status":"queued"}""",
            ),
        )
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        transport.push(event("message.start", "rt1"))
        transport.push(event("message.delta", "rt1", """{"text":"Working"}"""))
        chat.state.first { s -> (s.messages.last() as? ChatMessage.Assistant)?.text == "Working" }

        assertTrue(chat.send("then the tests", queue = true))

        val submit = transport.sent.value.first { it.isCall("prompt.submit") }
        assertEquals("true", submit.param("queued"))
        val state = chat.state.value
        assertTrue(assertIs<ChatMessage.User>(state.messages.last()).queued)
        // The reply keeps streaming above the queued prompt.
        assertTrue(assertIs<ChatMessage.Assistant>(state.messages[state.messages.lastIndex - 1]).streaming)
    }

    @Test
    fun stoppingDropsTheQueuedPromptsAndHandsThemBack() = runTest {
        val (connection, transport) = setup(
            backgroundScope,
            mapOf(
                "session.resume" to """{"session_id":"rt1","running":false}""",
                "prompt.submit" to """{"status":"queued"}""",
                "session.interrupt" to """{"status":"interrupted"}""",
            ),
        )
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        transport.push(event("message.start", "rt1"))
        transport.push(event("message.delta", "rt1", """{"text":"Working"}"""))
        chat.state.first { s -> (s.messages.last() as? ChatMessage.Assistant)?.text == "Working" }
        assertTrue(chat.send("then the tests", queue = true))
        assertTrue(chat.send("and the docs", queue = true))

        // The gateway clears its queue on interrupt, so nothing would ever send these.
        assertEquals(listOf("then the tests", "and the docs"), chat.interrupt())
        assertTrue(transport.sent.value.any { it.isCall("session.interrupt") })
        assertEquals(listOf("hello", "Hi! What next?", "Working"), chat.state.value.messages.map { it.textOf() })
    }

    /** A chat attached as rt1 whose turn is streaming "Working". */
    private suspend fun runningChat(scope: CoroutineScope, results: Map<String, String>): Pair<ChatSession, FakeTransport> {
        val (connection, transport) = setup(scope, mapOf("session.resume" to """{"session_id":"rt1","running":false}""") + results)
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), scope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        transport.push(event("message.start", "rt1"))
        transport.push(event("message.delta", "rt1", """{"text":"Working"}"""))
        chat.state.first { s -> (s.messages.last() as? ChatMessage.Assistant)?.text == "Working" }
        return chat to transport
    }

    @Test
    fun steeringGoesThroughSessionSteerIntoTheRunningTurn() = runTest {
        val (chat, transport) = runningChat(backgroundScope, mapOf("session.steer" to """{"status":"queued","text":"use tabs"}"""))

        assertEquals(SendOutcome.Sent, chat.steer(" use tabs "))

        val steer = transport.sent.value.single { it.isCall("session.steer") }
        assertEquals("rt1", steer.param("session_id"))
        assertEquals("use tabs", steer.param("text"))
        assertTrue(transport.sent.value.none { it.isCall("prompt.submit") || it.isCall("slash.exec") })
        transport.push(event("message.delta", "rt1", """{"text":"Switching to tabs"}"""))
        val live = chat.state.first { s -> (s.messages.last() as? ChatMessage.Assistant)?.text == "Switching to tabs" }
        // What streamed before it stays above the steer; the turn goes on below it.
        assertEquals(listOf("hello", "Hi! What next?", "Working", "use tabs", "Switching to tabs"), live.messages.map { it.textOf() })
        val bubble = assertIs<ChatMessage.User>(live.messages[3])
        assertFalse(bubble.pending)
        assertFalse(bubble.queued)
        assertTrue(live.running)
    }

    @Test
    fun aRejectedSteerRunsAsTheNextPrompt() = runTest {
        val (chat, transport) = runningChat(
            backgroundScope,
            mapOf("session.steer" to """{"status":"rejected","text":"use tabs"}""", "prompt.submit" to """{"status":"queued"}"""),
        )

        assertEquals(SendOutcome.Sent, chat.steer("use tabs"))

        val submit = transport.sent.value.single { it.isCall("prompt.submit") }
        assertEquals("use tabs", submit.param("text"))
        assertEquals("true", submit.param("queued"))
        val prompts = chat.state.value.messages.filterIsInstance<ChatMessage.User>().filter { it.text == "use tabs" }
        assertTrue(prompts.single().queued)
    }

    @Test
    fun aGatewayThatCannotSteerGetsAPlainPrompt() = runTest {
        val (chat, transport) = runningChat(
            backgroundScope,
            mapOf("session.steer" to "error:-32601", "prompt.submit" to """{"status":"steered"}"""),
        )

        assertEquals(SendOutcome.Sent, chat.steer("use tabs"))

        // No queued flag: the gateway's own busy mode folds it into the turn, as before session.steer.
        val submit = transport.sent.value.single { it.isCall("prompt.submit") }
        assertNull(submit.param("queued"))
        assertEquals(1, chat.state.value.messages.count { it is ChatMessage.User && it.text == "use tabs" })
    }

    @Test
    fun aSteerTheGatewayRefusesComesBack() = runTest {
        val (chat, transport) = runningChat(backgroundScope, mapOf("session.steer" to "error:5000"))

        assertEquals(SendOutcome.NotSent, chat.steer("use tabs"))

        assertTrue(transport.sent.value.none { it.isCall("prompt.submit") })
        assertTrue(chat.state.value.messages.none { it is ChatMessage.User && it.text == "use tabs" })
        assertTrue(chat.state.value.error != null)
    }

    @Test
    fun aSteerThatWentOutWithoutAReplyStaysAsUnknown() = runTest {
        val (chat, transport) = runningChat(backgroundScope, mapOf("session.steer" to SILENT))

        val steering = backgroundScope.async { chat.steer("use tabs") }
        transport.awaitSent { it.isCall("session.steer") }
        transport.serverClose(1006)

        // The agent may have read it: not handed back to send twice, but kept, marked, to check or resend.
        assertEquals(SendOutcome.Unsettled, steering.await())
        val bubble = assertIs<ChatMessage.User>(chat.state.value.messages.single { it is ChatMessage.User && it.text == "use tabs" })
        assertEquals(SendCheck.Unknown, bubble.check)
        assertFalse(bubble.pending)
    }

    @Test
    fun steeringWithNothingRunningJustSends() = runTest {
        val (connection, transport) = setup(
            backgroundScope,
            mapOf("session.resume" to """{"session_id":"rt1","running":false}""", "prompt.submit" to """{"status":"streaming"}"""),
        )
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }

        assertEquals(SendOutcome.Sent, chat.steer("use tabs"))

        assertTrue(transport.sent.value.none { it.isCall("session.steer") })
        assertEquals("use tabs", transport.sent.value.single { it.isCall("prompt.submit") }.param("text"))
    }

    @Test
    fun stopAndSendStopsTheTurnWaitsForItThenSends() = runTest {
        val (chat, transport) = runningChat(
            backgroundScope,
            mapOf("session.interrupt" to """{"status":"interrupted"}""", "prompt.submit" to """{"status":"streaming"}"""),
        )

        val result = async { chat.stopAndSubmit("do the docs instead") }
        transport.awaitSent { it.isCall("session.interrupt") }
        // Nothing goes out until the stopped turn has ended.
        assertTrue(transport.sent.value.none { it.isCall("prompt.submit") })
        transport.push(event("message.complete", "rt1", """{"text":"Working","status":"interrupted"}"""))

        assertEquals(SendOutcome.Sent, result.await().outcome)
        val methods = transport.sent.value.mapNotNull { it["method"]?.jsonPrimitive?.contentOrNull }
        assertTrue(methods.indexOf("session.interrupt") < methods.indexOf("prompt.submit"))
        val submit = transport.sent.value.single { it.isCall("prompt.submit") }
        assertEquals("do the docs instead", submit.param("text"))
        // A fresh turn, not a follow-up held behind the stopped one.
        assertNull(submit.param("queued"))
        assertTrue(chat.state.value.running)
    }

    @Test
    fun stopAndSendHandsBackWhatTheStopDropped() = runTest {
        val (chat, transport) = runningChat(
            backgroundScope,
            mapOf("session.interrupt" to """{"status":"interrupted"}""", "prompt.submit" to """{"status":"queued"}"""),
        )
        assertTrue(chat.send("then the tests", queue = true))

        val result = async { chat.stopAndSubmit("do the docs instead") }
        transport.awaitSent { it.isCall("session.interrupt") }
        transport.push(event("message.complete", "rt1", """{"text":"Working","status":"interrupted"}"""))

        assertEquals(listOf("then the tests"), result.await().dropped)
    }

    @Test
    fun stopAndSendQueuesBehindATurnThatWontEnd() = runTest {
        val (chat, transport) = runningChat(
            backgroundScope,
            mapOf("session.interrupt" to """{"status":"interrupted"}""", "prompt.submit" to """{"status":"queued"}"""),
        )

        // No message.complete ever comes: it still goes, held for after the turn rather than folded into it.
        assertEquals(SendOutcome.Sent, chat.stopAndSubmit("do the docs instead").outcome)

        assertEquals("true", transport.sent.value.single { it.isCall("prompt.submit") }.param("queued"))
    }

    @Test
    fun stopAndSendThatCannotStopSendsNothing() = runTest {
        val (chat, transport) = runningChat(backgroundScope, mapOf("session.interrupt" to "error:5019"))

        assertEquals(SendOutcome.NotSent, chat.stopAndSubmit("do the docs instead").outcome)

        assertTrue(transport.sent.value.none { it.isCall("prompt.submit") })
        assertTrue(chat.state.value.error != null)
    }

    @Test
    fun sendWhileOfflineReportsAndLeavesNoBubble() = runTest {
        // Never started, so never connected.
        gateway = FakeGateway(backgroundScope)
        val chat = ChatSession(url, null, null, gateway.connection, SessionsApi(client()), backgroundScope)

        assertFalse(chat.send("hello?"))

        assertTrue(chat.state.value.messages.isEmpty())
        assertTrue(chat.state.value.error != null)
    }

    @Test
    fun aChatOpenElsewhereIsSaidAsSuchNotAsAnError() = runTest {
        history = threeTurns
        val (chat, _) = resumedChat(backgroundScope, mapOf("prompt.submit" to "error:4090:SESSION_NOT_OWNED"))
        val before = chat.state.value.messages

        assertFalse(chat.send("are you there?"))

        assertEquals(SessionRefusal.OpenElsewhere, chat.state.value.refused)
        assertNull(chat.state.value.error)
        // Nothing went: no bubble left behind; the caller hands the text back to the composer.
        assertEquals(before, chat.state.value.messages)
    }

    @Test
    fun aRefusalWithoutAKnownReasonStaysAnOrdinaryError() = runTest {
        history = threeTurns
        val (chat, _) = resumedChat(backgroundScope, mapOf("prompt.submit" to "error:4090"))

        assertFalse(chat.send("are you there?"))

        assertNull(chat.state.value.refused)
        assertEquals("nope", chat.state.value.error)
    }

    @Test
    fun theNextSendClearsARefusal() = runTest {
        history = threeTurns
        // Passed to setup as is, so the answer can change mid-test.
        val results = mutableMapOf(
            "session.resume" to """{"session_id":"rt1","running":false}""",
            "prompt.submit" to "error:4090:MAX_CONCURRENT_SESSIONS",
        )
        val (connection, _) = setup(backgroundScope, results)
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        assertFalse(chat.send("first try"))
        assertEquals(SessionRefusal.TooManyChats, chat.state.value.refused)

        results["prompt.submit"] = """{"status":"started"}"""
        assertTrue(chat.send("second try"))

        assertNull(chat.state.value.refused)
    }

    @Test
    fun approvalRequestIsShownAnsweredAndWithdrawn() = runTest {
        val (connection, transport) = setup(backgroundScope, mapOf("session.resume" to """{"session_id":"rt1","running":true}"""))
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" }

        transport.push("""{"jsonrpc":"2.0","id":"srq-other","method":"approval","params":{"session_id":"rt9","command":"ls"}}""")
        transport.push("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt1","command":"rm -rf build","choices":["once","session","deny"]}}""")
        transport.push("""{"jsonrpc":"2.0","id":"srq-2","method":"sudo","params":{"session_id":"rt1","command":"apt install jq"}}""")

        val asked = chat.state.first { it.inputRequests.size == 2 }
        val approval = assertIs<InputRequest.Approval>(asked.inputRequests.first())
        assertEquals("srq-1", approval.id)

        assertTrue(chat.answer(approval, InputAnswers.approval(ApprovalChoice.Session)))
        val reply = transport.awaitSent { it["id"]?.jsonPrimitive?.contentOrNull == "srq-1" }
        assertEquals("session", reply["result"]!!.jsonObject["choice"]!!.jsonPrimitive.contentOrNull)

        // Desktop typed the password first: the gateway withdraws the request everywhere.
        transport.push(event("request.cancel", "rt1", """{"id":"srq-2","method":"sudo","reason":"resolved"}"""))
        chat.state.first { it.inputRequests.isEmpty() }
    }

    @Test
    fun vaultPromptsAreShownSkippedAndSavedAndWithdrawnOnTimeout() = runTest {
        val (connection, transport) = setup(backgroundScope, mapOf("session.resume" to """{"session_id":"rt1","running":true}"""))
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" }

        transport.push("""{"jsonrpc":"2.0","id":"srq-1","method":"vault.code","params":{"session_id":"rt1","site":"github.com","hint":""}}""")
        transport.push("""{"jsonrpc":"2.0","id":"srq-2","method":"vault.save_login","params":{"session_id":"rt1","origin":"https://github.com","site":"github.com"}}""")
        transport.push("""{"jsonrpc":"2.0","id":"srq-3","method":"vault.unlock_prompt","params":{"session_id":"rt1","backend":"bitwarden","display_name":"Bitwarden"}}""")

        val asked = chat.state.first { it.inputRequests.size == 3 }
        val code = assertIs<InputRequest.Secret>(asked.inputRequests[0])
        assertEquals(InputRequest.Secret.Kind.VaultCode, code.kind)

        // Skip: an empty value, and the turn goes on without the code.
        assertTrue(chat.answer(code, InputAnswers.value("")))
        val skipped = transport.awaitSent { it["id"]?.jsonPrimitive?.contentOrNull == "srq-1" }
        assertEquals("", skipped["result"]!!.jsonObject["value"]!!.jsonPrimitive.contentOrNull)

        val save = assertIs<InputRequest.VaultSaveLogin>(chat.state.value.inputRequests.first())
        assertTrue(chat.answer(save, InputAnswers.saveLogin("ada@example.com", "hunter2")))
        val saved = transport.awaitSent { it["id"]?.jsonPrimitive?.contentOrNull == "srq-2" }
        val login = HermesJson.parseToJsonElement(saved["result"]!!.jsonObject["value"]!!.jsonPrimitive.content).jsonObject
        assertEquals("hunter2", login["password"]!!.jsonPrimitive.contentOrNull)

        // The gateway's 120 s wait ran out: it withdraws the request, and the panel goes.
        transport.push(event("request.cancel", "rt1", """{"id":"srq-3","method":"vault.unlock_prompt","reason":"timeout"}"""))
        chat.state.first { it.inputRequests.isEmpty() }
    }

    @Test
    fun resumeRestoresQuestionsAskedWhileAway() = runTest {
        val (connection, _) = setup(
            backgroundScope,
            mapOf(
                "session.resume" to """{"session_id":"rt1","running":true,"open_requests":[{"id":"srq-7","method":"clarify","params":{"session_id":"rt1","question":"Which branch?","choices":["main","dev"]}},{"id":"srq-8","method":"terminal.read","params":{"session_id":"rt1"}}]}""",
            ),
        )
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()

        val attached = chat.state.first { it.runtimeSessionId == "rt1" }
        val clarify = assertIs<InputRequest.Clarify>(attached.inputRequests.single())
        assertEquals("Which branch?", clarify.questions.single().question)
    }

    @Test
    fun resumeRestoresAVaultPromptAskedWhileAway() = runTest {
        val (connection, _) = setup(
            backgroundScope,
            mapOf(
                "session.resume" to """{"session_id":"rt1","running":true,"open_requests":[{"id":"srq-9","method":"vault.unlock_prompt","params":{"session_id":"rt1","backend":"bitwarden","display_name":"Bitwarden"}}]}""",
            ),
        )
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()

        val attached = chat.state.first { it.runtimeSessionId == "rt1" }
        val unlock = assertIs<InputRequest.Secret>(attached.inputRequests.single())
        assertEquals(InputRequest.Secret.Kind.VaultUnlock, unlock.kind)
    }

    @Test
    fun hostReusesTheOpenStoredChatAndReplacesEverythingElse() = runTest {
        val (connection, _) = setup(backgroundScope, mapOf("session.resume" to """{"session_id":"rt1","running":false}"""))
        val host = ChatHost(connection, SessionsApi(client()), backgroundScope)

        val first = host.open(url, "stored-1", "Greeting")
        assertTrue(first === host.open(url, "stored-1", null))
        assertTrue(first === host.session.value)

        val fresh = host.open(url, null, null)
        assertFalse(fresh === first)
        assertFalse(fresh === host.open(url, null, null))

        host.close()
        assertEquals(null, host.session.value)
    }

    @Test
    fun picksOnANewChatGoIntoSessionCreate() = runTest {
        val (connection, transport) = setup(
            backgroundScope,
            mapOf(
                "session.create" to """{"session_id":"rt9","stored_session_id":"stored-9","info":{"model":"m2","provider":"p2","reasoning_effort":"high","fast":true}}""",
                "prompt.submit" to """{"status":"streaming"}""",
            ),
        )
        connection.state.first { it is ConnectionState.Connected }
        val chat = ChatSession(url, null, null, connection, SessionsApi(client()), backgroundScope)
        chat.start()

        assertEquals(ModelSwitch.Done, chat.setModel("m2", "p2"))
        chat.setReasoningEffort("high")
        chat.setFast(true)
        assertTrue(chat.send("hi"))

        val create = transport.awaitSent { it.isCall("session.create") }
        assertEquals("desktop", create.param("source"))
        assertEquals("m2", create.param("model"))
        assertEquals("p2", create.param("provider"))
        assertEquals("high", create.param("reasoning_effort"))
        assertEquals("true", create.param("fast"))
        assertTrue(transport.sent.value.none { it.isCall("config.set") })
        val state = chat.state.value
        assertEquals("high", state.reasoningEffort)
        assertEquals(true, state.fast)
    }

    @Test
    fun aLiveChatSwitchesOverConfigSetAndRollsBackWhenAskedToConfirm() = runTest {
        val (connection, transport) = setup(
            backgroundScope,
            mapOf(
                "session.resume" to """{"session_id":"rt1","running":false,"info":{"model":"m1","provider":"p1","reasoning_effort":""}}""",
                "config.set" to """{"key":"model","confirm_required":true,"confirm_message":"Costs a lot"}""",
            ),
        )
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        val attached = chat.state.first { it.runtimeSessionId == "rt1" }
        assertEquals("p1", attached.provider)
        assertEquals(null, attached.reasoningEffort)

        assertEquals(ModelSwitch.NeedsConfirmation("Costs a lot"), chat.setModel("big", "p1"))
        val set = transport.awaitSent { it.isCall("config.set") }
        assertEquals("rt1", set.param("session_id"))
        assertEquals("big --provider p1 --session", set.param("value"))
        assertEquals("m1", chat.state.value.model)

        chat.setReasoningEffort("low")
        transport.awaitSent { it.isCall("config.set") && it.param("key") == "reasoning" && it.param("value") == "low" }
        transport.push(event("session.info", "rt1", """{"model":"m1","reasoning_effort":"low","fast":false}"""))
        chat.state.first { it.reasoningEffort == "low" && it.fast == false }
    }

    @Test
    fun attachmentsAreUploadedBeforeThePromptAndFilesBecomeReferences() = runTest {
        val (connection, transport) = setup(
            backgroundScope,
            mapOf(
                "session.create" to """{"session_id":"rt9","stored_session_id":"stored-9","info":{}}""",
                "image.attach_bytes" to """{"attached":true,"path":"/tmp/upload_1.jpg"}""",
                "pdf.attach" to "error:5028",
                "file.attach" to """{"attached":true,"name":"x","path":"p","ref_path":"r","ref_text":"@file:attachments/notes.txt","uploaded":true}""",
                "prompt.submit" to """{"status":"streaming"}""",
            ),
        )
        connection.state.first { it is ConnectionState.Connected }
        val chat = ChatSession(url, null, null, connection, SessionsApi(client()), backgroundScope)
        chat.start()

        val image = OutgoingAttachment("a1", "cat.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        val pdf = OutgoingAttachment("a2", "spec.pdf", "application/pdf", byteArrayOf(4))
        val file = OutgoingAttachment("a3", "notes.txt", "text/plain", "hi".encodeToByteArray())
        assertTrue(chat.send("  summarise  ", listOf(image, pdf, file)))

        val sent = transport.sent.value.mapNotNull { it["method"]?.jsonPrimitive?.contentOrNull }.filter { it != "client.capabilities" && it != "ping" }
        assertEquals(listOf("session.create", "image.attach_bytes", "pdf.attach", "file.attach", "file.attach", "prompt.submit"), sent)
        val upload = transport.sent.value.first { it.isCall("image.attach_bytes") }
        assertEquals("AQID", upload.param("content_base64"))
        assertEquals("rt9", upload.param("session_id"))
        // The PDF fell back to a plain file once the gateway said it can't render pages.
        assertEquals("data:application/pdf;base64,BA==", transport.sent.value.first { it.isCall("file.attach") }.param("data_url"))
        val submit = transport.sent.value.first { it.isCall("prompt.submit") }
        assertEquals("@file:attachments/notes.txt\n\n@file:attachments/notes.txt\n\nsummarise", submit.param("text"))
        val user = assertIs<ChatMessage.User>(chat.state.value.messages.single())
        assertEquals(listOf("cat.jpg", "spec.pdf", "notes.txt"), user.attachments.map { it.name })
    }

    @Test
    fun aFailedSendTakesBackTheImagesItQueued() = runTest {
        val (connection, transport) = setup(
            backgroundScope,
            mapOf(
                "session.create" to """{"session_id":"rt9","stored_session_id":"stored-9","info":{}}""",
                "image.attach_bytes" to """{"attached":true,"path":"/tmp/upload_1.jpg"}""",
                "prompt.submit" to "error:5000",
            ),
        )
        connection.state.first { it is ConnectionState.Connected }
        val chat = ChatSession(url, null, null, connection, SessionsApi(client()), backgroundScope)
        chat.start()

        assertFalse(chat.send("", listOf(OutgoingAttachment("a1", "cat.jpg", "image/jpeg", byteArrayOf(1)))))

        val detach = transport.awaitSent { it.isCall("image.detach") }
        assertEquals("/tmp/upload_1.jpg", detach.param("path"))
        assertTrue(chat.state.value.messages.isEmpty())
    }

    private suspend fun resumedChat(scope: CoroutineScope, results: Map<String, String>): Pair<ChatSession, FakeTransport> {
        val (connection, transport) = setup(scope, mapOf("session.resume" to """{"session_id":"rt1","running":false}""") + results)
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), scope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        return chat to transport
    }

    @Test
    fun aSubmitNeverAnsweredAndMissingFromTheTranscriptIsHandedBack() = runTest {
        val (chat, _) = resumedChat(backgroundScope, mapOf("prompt.submit" to SILENT))

        val outcome = runCatching { chat.send("hello again") }

        assertEquals(false, outcome.getOrNull(), "send threw ${outcome.exceptionOrNull()} instead of returning false")
        assertEquals(listOf("hello", "Hi! What next?"), chat.state.value.messages.map { it.textOf() })
        assertTrue(chat.state.value.error!!.contains("composer"))
    }

    @Test
    fun aDropAfterTheSubmitWentOutIsSentWhenTheTranscriptHasIt() = runTest {
        val (chat, transport) = resumedChat(backgroundScope, mapOf("prompt.submit" to SILENT))

        val sending = backgroundScope.async { chat.send("deploy it") }
        transport.awaitSent { it.isCall("prompt.submit") }
        // The gateway has the prompt and is running it; only its reply is lost.
        history = history.replace("]}", """,{"id":3,"role":"user","content":"deploy it"}]}""")
        transport.serverClose(1006)

        assertTrue(sending.await(), "the prompt reached the gateway, but send reported it unsent and handed the text back to resend")
        val messages = chat.state.value.messages
        assertEquals(listOf("hello", "Hi! What next?", "deploy it"), messages.map { it.textOf() })
        assertEquals("row-3", messages.last().key)
    }

    @Test
    fun aLostResendOfTheLastPromptIsNotTakenForTheEarlierOne() = runTest {
        // Read on every call, so the gateway can stop answering partway through.
        val results = mutableMapOf(
            "session.resume" to """{"session_id":"rt1","running":false}""",
            "prompt.submit" to """{"status":"streaming"}""",
        )
        val (connection, transport) = setup(backgroundScope, results)
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }

        // An own turn: its bubble keeps its local key, as the transcript isn't reloaded after it.
        assertTrue(chat.send("continue"))
        transport.push(event("message.start", "rt1"))
        transport.push(event("message.complete", "rt1", """{"text":"Done.","status":"complete"}"""))
        chat.state.first { !it.running }
        history = history.replace("]}", """,{"id":3,"role":"user","content":"continue"},{"id":4,"role":"assistant","content":"Done."}]}""")

        // The same text again; it never reaches the gateway, so the transcript is unchanged.
        results["prompt.submit"] = SILENT
        val sending = backgroundScope.async { chat.send("continue") }
        transport.sent.first { sent -> sent.count { it.isCall("prompt.submit") } == 2 }
        transport.serverClose(1006)

        assertFalse(sending.await(), "the resend never arrived, but the earlier \"continue\" was taken for it")
        assertTrue(chat.state.value.error!!.contains("composer"))
    }

    @Test
    fun aDropTheTranscriptCantSettleKeepsAMarkedBubble() = runTest {
        // A new chat without a stored id yet: there's no transcript to look in.
        val (chat, transport) = newChat(backgroundScope, mapOf("prompt.submit" to SILENT))

        val sending = backgroundScope.async { chat.send("deploy it") }
        transport.awaitSent { it.isCall("prompt.submit") }
        transport.serverClose(1006)

        assertFalse(sending.await())
        val bubble = assertIs<ChatMessage.User>(chat.state.value.messages.single())
        assertEquals(SendCheck.Unknown, bubble.check)
        assertFalse(bubble.pending)
    }

    /** A resumed chat whose prompt went out and lost its reply while the transcript couldn't be read. */
    private suspend fun unsettledSend(scope: CoroutineScope, text: String): ChatSession {
        val (chat, transport) = resumedChat(scope, mapOf("prompt.submit" to SILENT))
        historyFails = true
        val sending = scope.async { chat.send(text) }
        transport.awaitSent { it.isCall("prompt.submit") }
        transport.serverClose(1006)
        assertFalse(sending.await())
        assertEquals(SendCheck.Unknown, (chat.state.value.messages.last() as ChatMessage.User).check)
        historyFails = false
        return chat
    }

    @Test
    fun aTranscriptReadIsSavedAndShownWhenTheGatewayCantBeRead() = runTest {
        val cache = OfflineCache(InMemoryOfflineDao(), PlainSealer, EmptyCoroutineContext) { 5_000L }
        val (connection, _) = setup(backgroundScope, mapOf("session.resume" to """{"session_id":"rt1","running":false}"""))
        val first = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope, cache = cache)
        first.start()
        first.state.first { it.historyLoaded && it.messages.size == 2 }
        testScheduler.runCurrent() // the copy is written after the rows show
        assertNotNull(cache.savedTranscript(url, null, "stored-1"))
        first.stop()

        historyFails = true
        val again = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope, cache = cache)
        again.start()

        val saved = again.state.first { it.historyError != null }
        assertEquals(listOf("hello", "Hi! What next?"), saved.messages.map { it.textOf() })
        assertEquals(5_000L, saved.savedCopyAt)
        assertNotNull(saved.historyError)
        assertFalse(saved.olderMessages)
    }

    @Test
    fun theGatewaysTranscriptReplacesTheSavedCopyOnceItCanBeRead() = runTest {
        val cache = OfflineCache(InMemoryOfflineDao(), PlainSealer, EmptyCoroutineContext) { 5_000L }
        cache.saveTranscript(url, null, "stored-1", listOf(SessionMessage(id = 1, role = "user", content = JsonPrimitive("old"))), "stored-1")
        historyFails = true
        val (connection, _) = setup(backgroundScope, mapOf("session.resume" to """{"session_id":"rt1","running":false}"""))
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope, cache = cache)
        chat.start()
        assertEquals(listOf("old"), chat.state.first { it.savedCopyAt != null }.messages.map { it.textOf() })

        historyFails = false
        chat.retry()

        val fresh = chat.state.first { it.savedCopyAt == null && it.historyError == null }
        assertEquals(listOf("hello", "Hi! What next?"), fresh.messages.map { it.textOf() })
        testScheduler.runCurrent() // the copy is written after the rows show
        assertEquals(listOf(1L, 2L), cache.savedTranscript(url, null, "stored-1")?.rows?.map { it.id })
    }

    @Test
    fun aTurnOfOursEndingBringsTheSavedCopyUpToDate() = runTest {
        val cache = OfflineCache(InMemoryOfflineDao(), PlainSealer, EmptyCoroutineContext) { 5_000L }
        val (connection, transport) = setup(
            backgroundScope,
            mapOf("session.resume" to """{"session_id":"rt1","running":false}""", "prompt.submit" to """{"status":"streaming"}"""),
        )
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope, cache = cache)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        assertTrue(chat.send("go on"))
        transport.push(event("message.start", "rt1"))
        history = history.replace("]}", """,{"id":3,"role":"user","content":"go on"},{"id":4,"role":"assistant","content":"Done."}]}""")

        transport.push(event("message.complete", "rt1", """{"text":"Done.","status":"complete"}"""))

        var saved: SavedTranscript? = null
        repeat(100) {
            if (saved == null) {
                testScheduler.advanceTimeBy(10)
                testScheduler.runCurrent()
                saved = cache.savedTranscript(url, null, "stored-1")?.takeIf { it.rows.size == 4 }
            }
        }
        assertEquals(listOf(1L, 2L, 3L, 4L), saved?.rows?.map { it.id })
    }

    @Test
    fun attachingWhileTheSavedCopyShowsReadsTheTranscriptOnce() = runTest {
        val cache = OfflineCache(InMemoryOfflineDao(), PlainSealer, EmptyCoroutineContext) { 5_000L }
        cache.saveTranscript(url, null, "stored-1", listOf(SessionMessage(id = 1, role = "user", content = JsonPrimitive("old"))), "stored-1")
        var reads = 0
        val (connection, _) = setup(backgroundScope, mapOf("session.resume" to """{"session_id":"rt1","running":false}"""))
        val answer = gateway.http
        gateway.http = { request -> reads++; answer(request) }
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope, cache = cache)
        chat.start()

        chat.state.first { it.runtimeSessionId == "rt1" && it.historyRead && it.messages.size == 2 }
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(1, reads)
    }

    @Test
    fun aChatTheGatewayNoLongerHasIsDroppedFromTheDevice() = runTest {
        val cache = OfflineCache(InMemoryOfflineDao(), PlainSealer, EmptyCoroutineContext) { 5_000L }
        cache.saveTranscript(url, null, "stored-1", listOf(SessionMessage(id = 1, role = "user", content = JsonPrimitive("old"))), "stored-1")
        historyMissing = true
        val (connection, _) = setup(backgroundScope, mapOf("session.resume" to """{"session_id":"rt1","running":false}"""))
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope, cache = cache)
        chat.start()

        val gone = chat.state.first { it.historyError != null }
        assertTrue(gone.messages.isEmpty())
        assertNull(gone.savedCopyAt)
        assertNull(cache.savedTranscript(url, null, "stored-1"))
    }

    @Test
    fun checkingDeliveryLaterSettlesAPromptTheTranscriptHas() = runTest {
        val chat = unsettledSend(backgroundScope, "deploy it")
        history = history.replace("]}", """,{"id":3,"role":"user","content":"deploy it"}]}""")

        chat.checkDelivery(chat.state.value.messages.last().key)

        val messages = chat.state.value.messages
        assertEquals(listOf("hello", "Hi! What next?", "deploy it"), messages.map { it.textOf() })
        assertEquals("row-3", messages.last().key)
    }

    @Test
    fun checkingDeliveryLaterMarksAMissingPromptSafeToResend() = runTest {
        val chat = unsettledSend(backgroundScope, "deploy it")
        val key = chat.state.value.messages.last().key

        chat.checkDelivery(key)

        assertEquals(SendCheck.NotReceived, (chat.state.value.messages.last() as ChatMessage.User).check)
        assertEquals("deploy it", chat.takeBack(key))
        assertEquals(listOf("hello", "Hi! What next?"), chat.state.value.messages.map { it.textOf() })
    }

    @Test
    fun checkingDeliveryWithoutATranscriptKeepsItUnknown() = runTest {
        val chat = unsettledSend(backgroundScope, "deploy it")
        historyFails = true

        chat.checkDelivery(chat.state.value.messages.last().key)

        assertEquals(SendCheck.Unknown, (chat.state.value.messages.last() as ChatMessage.User).check)
        assertTrue(chat.state.value.error!!.contains("check"))
    }

    @Test
    fun aCheckDoesNotTakeAnEarlierPromptWithTheSameTextForAMissingOne() = runTest {
        // "hello" is already the transcript's first prompt; this one never arrived.
        val chat = unsettledSend(backgroundScope, "hello")
        val key = chat.state.value.messages.last().key

        chat.checkDelivery(key)

        val bubble = assertIs<ChatMessage.User>(chat.state.value.messages.last(), "the missing prompt's bubble was settled away by the earlier one")
        assertEquals(key, bubble.key)
        assertEquals(SendCheck.NotReceived, bubble.check)
    }

    @Test
    fun aMissingCorrectionStaysUnknownWhileItsTurnRuns() = runTest {
        val (chat, transport) = resumedChat(
            backgroundScope,
            mapOf("session.resume" to """{"session_id":"rt1","running":true}""", "prompt.submit" to SILENT),
        )
        val sending = backgroundScope.async { chat.submit("also check the logs") }
        transport.awaitSent { it.isCall("prompt.submit") }
        transport.serverClose(1006)
        assertEquals(SendOutcome.Unsettled, sending.await())

        chat.checkDelivery(chat.state.value.messages.last().key)

        // Folded into the running turn, it's only written once the turn takes it in.
        assertEquals(SendCheck.Unknown, (chat.state.value.messages.last() as ChatMessage.User).check)
        assertTrue(chat.state.value.error!!.contains("turn ends"))
    }

    @Test
    fun aNewChatsLostFirstPromptIsFoundMissing() = runTest {
        val (chat, transport) = newChat(
            backgroundScope,
            mapOf("session.create" to """{"session_id":"rt9","stored_session_id":"stored-1","info":{}}""", "prompt.submit" to SILENT),
        )
        historyFails = true
        val sending = backgroundScope.async { chat.submit("deploy it") }
        transport.awaitSent { it.isCall("prompt.submit") }
        transport.serverClose(1006)
        assertEquals(SendOutcome.Unsettled, sending.await())
        // The row only appears with a first prompt that arrived.
        historyFails = false
        historyMissing = true

        chat.checkDelivery(chat.state.value.messages.single().key)

        assertEquals(SendCheck.NotReceived, (chat.state.value.messages.single() as ChatMessage.User).check)
    }

    @Test
    fun aNewChatsFirstPromptFoundLaterResumesItsRowOnReconnect() = runTest {
        val (connection, transports) = reconnectingSetup(
            backgroundScope,
            mapOf(
                "session.create" to """{"session_id":"rt9","stored_session_id":"stored-1","info":{}}""",
                "session.resume" to """{"session_id":"rt9","running":false}""",
                "prompt.submit" to SILENT,
            ),
        )
        connection.state.first { it is ConnectionState.Connected }
        val chat = ChatSession(url, null, null, connection, SessionsApi(client()), backgroundScope)
        chat.start()
        historyFails = true
        val sending = backgroundScope.async { chat.submit("deploy it") }
        transports.last().awaitSent { it.isCall("prompt.submit") }
        transports.last().serverClose(1006)
        assertEquals(SendOutcome.Unsettled, sending.await())
        historyFails = false
        history = """{"session_id":"stored-1","messages":[{"id":1,"role":"user","content":"deploy it"}]}"""

        chat.checkDelivery(chat.state.value.messages.single().key)
        assertEquals("row-1", chat.state.value.messages.single().key)
        val dropped = transports.last()
        dropped.serverClose(1006)

        // The row exists now, so the next link resumes it rather than waiting to create one.
        chat.state.first { it.runtimeSessionId != null && transports.last() !== dropped }
        assertTrue(transports.last().sent.value.any { it.isCall("session.resume") })
    }

    @Test
    fun resendingASkillSendsItsBodyAgainShowingTheCommand() = runTest {
        val results = mutableMapOf(
            "session.resume" to """{"session_id":"rt1","running":false}""",
            "prompt.submit" to SILENT,
        )
        val (connection, transports) = reconnectingSetup(backgroundScope, results)
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        historyFails = true
        val sending = backgroundScope.async { chat.submit("[IMPORTANT: skill body]", display = "/spike go") }
        transports.last().awaitSent { it.isCall("prompt.submit") }
        val first = transports.last()
        first.serverClose(1006)
        assertEquals(SendOutcome.Unsettled, sending.await())
        historyFails = false
        results["prompt.submit"] = """{"status":"streaming"}"""
        chat.state.first { it.runtimeSessionId == "rt1" && transports.last() !== first }

        assertNull(chat.resend(chat.state.value.messages.last().key))

        val resent = transports.last { it !== first }.awaitSent { it.isCall("prompt.submit") }
        assertEquals("[IMPORTANT: skill body]", resent.param("text"))
        assertEquals("/spike go", chat.state.value.messages.last().textOf())
    }

    @Test
    fun aResendThatNeverArrivesHandsItsTextBack() = runTest {
        val chat = unsettledSend(backgroundScope, "deploy it")
        val key = chat.state.value.messages.last().key

        // The link is gone, so it doesn't go out at all.
        assertEquals("deploy it", chat.resend(key))
        assertEquals(listOf("hello", "Hi! What next?"), chat.state.value.messages.map { it.textOf() })
    }

    @Test
    fun onlyAnUnsettledPromptCanBeTakenBack() = runTest {
        val (chat, _) = resumedChat(backgroundScope, emptyMap())

        assertNull(chat.takeBack(chat.state.value.messages.first().key))
        assertEquals(2, chat.state.value.messages.size)
    }

    private suspend fun newChat(scope: CoroutineScope, results: Map<String, String>): Pair<ChatSession, FakeTransport> {
        val (connection, transport) = setup(scope, mapOf("session.create" to """{"session_id":"rt9","info":{}}""") + results)
        connection.state.first { it is ConnectionState.Connected }
        val chat = ChatSession(url, null, null, connection, SessionsApi(client()), scope)
        chat.start()
        return chat to transport
    }

    @Test
    fun aCommandShowsWhatTheSlashWorkerPrinted() = runTest {
        val (chat, transport) = newChat(backgroundScope, mapOf("slash.exec" to """{"output":"Context: 12% used\n"}"""))

        assertEquals(null, chat.runCommand(SlashCommand("context", "")))

        assertEquals("context", transport.sent.value.first { it.isCall("slash.exec") }.param("command"))
        val shown = assertIs<ChatMessage.Command>(chat.state.value.messages.single())
        assertEquals("/context", shown.command)
        assertEquals("Context: 12% used", shown.output)
        assertFalse(shown.running)
        // Output alone doesn't make a conversation worth reopening.
        assertFalse(chat.state.value.hasConversation)
    }

    @Test
    fun aSkillTheWorkerRefusesIsDispatchedAndSentShowingTheInvocation() = runTest {
        val (chat, transport) = newChat(
            backgroundScope,
            mapOf(
                "slash.exec" to "error:4018",
                "command.dispatch" to """{"type":"skill","name":"work","message":"[IMPORTANT: skill body]","display":"/work fix the leak"}""",
                "prompt.submit" to """{"status":"streaming"}""",
            ),
        )

        chat.runCommand(SlashCommand("work", "fix the leak"))

        val dispatch = transport.sent.value.first { it.isCall("command.dispatch") }
        assertEquals("work", dispatch.param("name"))
        assertEquals("fix the leak", dispatch.param("arg"))
        assertEquals("[IMPORTANT: skill body]", transport.sent.value.first { it.isCall("prompt.submit") }.param("text"))
        val user = assertIs<ChatMessage.User>(chat.state.value.messages.single())
        assertEquals("/work fix the leak", user.text)
    }

    @Test
    fun anUndoShowsTheTranscriptWithoutTheUndoneTurn() = runTest {
        val (chat, _) = resumedChat(backgroundScope, mapOf("slash.exec" to """{"type":"prefill","message":"hello"}"""))
        // The gateway took the last turn off the stored transcript.
        history = """{"session_id":"stored-1","messages":[]}"""

        assertEquals("hello", chat.runCommand(SlashCommand("undo", "")))

        assertTrue(chat.state.value.messages.isEmpty(), "still showing ${chat.state.value.messages}")
    }

    @Test
    fun aPrefillHandsTheTextBackAndAFailureExplainsItself() = runTest {
        val (chat, _) = newChat(backgroundScope, mapOf("slash.exec" to """{"type":"prefill","message":"my last prompt"}"""))
        assertEquals("my last prompt", chat.runCommand(SlashCommand("undo", "")))
        assertTrue(chat.state.value.messages.isEmpty())

        val (failing, _) = newChat(backgroundScope, mapOf("slash.exec" to "error:5030", "command.dispatch" to "error:4018"))
        failing.runCommand(SlashCommand("usage", ""))
        val shown = assertIs<ChatMessage.Command>(failing.state.value.messages.single())
        assertTrue(shown.failed)
    }

    @Test
    fun aSideQuestionIsAnsweredIntoItsOwnCard() = runTest {
        val (chat, transport) = newChat(backgroundScope, mapOf("prompt.btw" to """{"task_id":"t1"}"""))

        chat.askAside("what was the file?")

        assertEquals("what was the file?", transport.sent.value.first { it.isCall("prompt.btw") }.param("text"))
        chat.state.first { (it.messages.singleOrNull() as? ChatMessage.Command)?.taskId == "t1" }
        transport.push(event("btw.complete", "rt9", """{"task_id":"t1","question":"what was the file?","text":"notes.txt"}"""))
        val card = chat.state.first { (it.messages.single() as ChatMessage.Command).output.isNotEmpty() }.messages.single() as ChatMessage.Command
        assertEquals("/btw what was the file?", card.command)
        assertEquals("notes.txt", card.output)
        assertFalse(card.running)
    }

    @Test
    fun yoloTogglesForThisChatOnly() = runTest {
        val (chat, transport) = newChat(backgroundScope, mapOf("config.set" to """{"key":"yolo","value":"1"}"""))

        chat.toggleYolo()

        val set = transport.sent.value.first { it.isCall("config.set") }
        assertEquals("yolo", set.param("key"))
        assertEquals("1", set.param("value"))
        assertEquals(null, set.param("scope"))
        assertEquals(true, chat.state.value.yolo)
    }

    @Test
    fun branchingReturnsTheNewChatToOpen() = runTest {
        val (connection, transport) = setup(
            backgroundScope,
            mapOf(
                "session.resume" to """{"session_id":"rt1","running":false}""",
                "session.branch_whole" to """{"session_id":"rt2","stored_session_id":"stored-2","title":"Greeting #2","message_count":2}""",
            ),
        )
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" }

        assertEquals("stored-2" to "Greeting #2", chat.branch(null))
        assertEquals("rt1", transport.sent.value.first { it.isCall("session.branch_whole") }.param("session_id"))
    }

    private val threeTurns = """{"session_id":"stored-1","messages":[
        {"id":1,"role":"user","content":"one"},{"id":2,"role":"assistant","content":"First"},
        {"id":3,"role":"user","content":"two"},{"id":4,"role":"assistant","content":"Second"},
        {"id":5,"role":"user","content":"three"},{"id":6,"role":"assistant","content":"Third"}]}"""

    @Test
    fun regeneratingCutsAtThePromptsRowAndEndsOnTheStoredTranscript() = runTest {
        history = threeTurns
        val (chat, transport) = resumedChat(backgroundScope, mapOf("prompt.submit" to """{"status":"streaming","user_row_id":7}"""))

        assertEquals(SendOutcome.Sent, chat.rewind("row-3", "two"))

        val submit = transport.sent.value.first { it.isCall("prompt.submit") }
        assertEquals("rt1", submit.param("session_id"))
        assertEquals("two", submit.param("text"))
        assertEquals("3", submit.param("truncate_before_row_id"))
        assertEquals("true", submit.param("confirm_truncate"))
        assertEquals("true", submit.param("confirm_empty_truncate"))
        val cut = chat.state.value
        assertTrue(cut.running)
        assertEquals(listOf("one", "First", "two"), cut.messages.map { it.textOf() })
        val prompt = assertIs<ChatMessage.User>(cut.messages.last())
        assertEquals(7L, prompt.rowId)
        assertFalse(prompt.pending)

        // The gateway's transcript after the turn: the cut, then the new exchange.
        history = """{"session_id":"stored-1","messages":[
            {"id":1,"role":"user","content":"one"},{"id":2,"role":"assistant","content":"First"},
            {"id":7,"role":"user","content":"two"},{"id":8,"role":"assistant","content":"Second, again"}]}"""
        transport.push(event("message.start", "rt1"))
        transport.push(event("message.delta", "rt1", """{"text":"Second, again"}"""))
        transport.push(event("message.complete", "rt1", """{"text":"Second, again","status":"complete"}"""))

        val done = chat.state.first { s -> !s.running && s.messages.lastOrNull()?.key == "row-8" }
        assertEquals(listOf("row-1", "row-2", "row-7", "row-8"), done.messages.map { it.key })
    }

    @Test
    fun editingTheFirstPromptEmptiesTheChatBeforeTheNewOne() = runTest {
        history = threeTurns
        val (chat, transport) = resumedChat(backgroundScope, mapOf("prompt.submit" to """{"status":"streaming","user_row_id":7}"""))

        assertEquals(SendOutcome.Sent, chat.rewind("row-1", "  uno "))

        val submit = transport.sent.value.first { it.isCall("prompt.submit") }
        assertEquals("uno", submit.param("text"))
        assertEquals("1", submit.param("truncate_before_row_id"))
        val prompt = assertIs<ChatMessage.User>(chat.state.value.messages.single())
        assertEquals("uno", prompt.text)
        assertEquals("uno", prompt.sentText)
    }

    @Test
    fun aBusyGatewayLeavesTheChatAsItWas() = runTest {
        history = threeTurns
        val (chat, _) = resumedChat(backgroundScope, mapOf("prompt.submit" to "error:4009"))
        val before = chat.state.value.messages

        assertEquals(SendOutcome.NotSent, chat.rewind("row-3", "two"))

        assertEquals(before, chat.state.value.messages)
        assertTrue(chat.state.value.error!!.contains("Wait"))
        assertFalse(chat.state.value.running)
    }

    @Test
    fun aRowTheGatewayCantPlaceShowsItsTranscriptAgain() = runTest {
        history = threeTurns
        val (chat, _) = resumedChat(backgroundScope, mapOf("prompt.submit" to "error:4018"))
        // Another client cut the chat meanwhile.
        history = """{"session_id":"stored-1","messages":[{"id":1,"role":"user","content":"one"},{"id":2,"role":"assistant","content":"First"}]}"""

        assertEquals(SendOutcome.NotSent, chat.rewind("row-3", "two"))

        assertEquals(listOf("row-1", "row-2"), chat.state.value.messages.map { it.key })
        assertTrue(chat.state.value.error!!.contains("can't be changed"))
    }

    @Test
    fun aLostAnswerAsksTheTranscriptWhetherTheRewindWent() = runTest {
        history = threeTurns
        val (chat, transport) = resumedChat(backgroundScope, mapOf("prompt.submit" to SILENT))

        val rewinding = backgroundScope.async { chat.rewind("row-5", "three") }
        transport.awaitSent { it.isCall("prompt.submit") }
        history = history.replace("""{"id":5,"role":"user","content":"three"},{"id":6,"role":"assistant","content":"Third"}""", """{"id":7,"role":"user","content":"three"}""")
        transport.serverClose(1006)

        assertEquals(SendOutcome.Sent, rewinding.await())
        assertEquals(listOf("row-1", "row-2", "row-3", "row-4", "row-7"), chat.state.value.messages.map { it.key })
    }

    @Test
    fun nothingRewindsWithoutAStoredRowOrMidTurn() = runTest {
        val (chat, transport) = newChat(backgroundScope, mapOf("prompt.submit" to """{"status":"streaming"}"""))
        assertTrue(chat.send("no row yet"))
        val key = chat.state.value.messages.single().key

        // No row id came back, and a turn is running besides.
        assertEquals(SendOutcome.NotSent, chat.rewind(key, "again"))

        assertEquals(1, transport.sent.value.count { it.isCall("prompt.submit") })
    }

    @Test
    fun aRewindMidTurnSaysWhyItDidntGo() = runTest {
        history = threeTurns
        val (chat, transport) = resumedChat(backgroundScope, mapOf("prompt.submit" to """{"status":"streaming","user_row_id":7}"""))
        // Another client starts a turn while an edit waits in the composer.
        transport.push(event("message.start", "rt1"))
        chat.state.first { it.running }

        assertEquals(SendOutcome.NotSent, chat.rewind("row-3", "two, edited"))

        assertTrue(chat.state.value.error!!.contains("Wait"))
        assertEquals(0, transport.sent.value.count { it.isCall("prompt.submit") })
    }

    @Test
    fun aSentPromptKeepsTheRowItWasWrittenTo() = runTest {
        val (chat, _) = resumedChat(backgroundScope, mapOf("prompt.submit" to """{"status":"streaming","user_row_id":9}"""))

        assertTrue(chat.send(" plain words "))

        val prompt = assertIs<ChatMessage.User>(chat.state.value.messages.last())
        assertEquals(9L, prompt.rowId)
        assertEquals("plain words", prompt.sentText)
    }

    private companion object {
        const val SILENT = "silent"
    }

    @Test
    fun checkpointsAttachAStoredChatAndRestoreReadsTheTranscriptAgain() = runTest {
        val (connection, transport) = setup(
            backgroundScope,
            mapOf(
                "session.resume" to """{"session_id":"rt1","running":false}""",
                "rollback.list" to """{"enabled":true,"checkpoints":[{"hash":"9f2c1ab47e0d","timestamp":"2026-10-08T20:41:03+03:00","message":"auto"}]}""",
                "rollback.diff" to """{"stat":" app.py | 2 +-","diff":"-old\n+new"}""",
                "rollback.restore" to """{"success":true,"restored_to":"9f2c1ab47e0d","restored_files":["app.py"],"history_removed":2}""",
            ),
        )
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)

        // Not started yet: the call attaches the stored chat first, as rollback.* needs a live session.
        connection.state.first { it is dev.hermeskotlin.core.connection.ConnectionState.Connected }
        val list = assertNotNull(chat.checkpoints())
        assertEquals("rt1", transport.awaitSent { it.isCall("rollback.list") }.param("session_id"))
        val checkpoint = list.checkpoints.single()

        val diff = chat.checkpointDiff(checkpoint)
        assertEquals(" app.py | 2 +-", diff.stat)
        assertEquals("9f2c1ab47e0d", transport.awaitSent { it.isCall("rollback.diff") }.param("hash"))

        history = """{"session_id":"stored-1","messages":[{"id":1,"role":"user","content":"hello"}]}"""
        assertEquals("Restored 1 file to 9f2c1ab4. The chat's last turn was taken back too.", chat.restoreCheckpoint(checkpoint))
        // The gateway rewound the transcript, so it's read again.
        assertEquals(1, chat.state.first { it.messages.size == 1 }.messages.size)
    }

    @Test
    fun aFailedCheckpointListSaysWhyAndAFailedRestoreStillReadsTheTranscript() = runTest {
        val (connection, _) = setup(
            backgroundScope,
            mapOf(
                "session.resume" to """{"session_id":"rt1","running":false}""",
                "rollback.list" to "error:5020",
                "rollback.restore" to "error:5021",
            ),
        )
        val chat = ChatSession(url, "stored-1", "Greeting", connection, SessionsApi(client()), backgroundScope)
        connection.state.first { it is dev.hermeskotlin.core.connection.ConnectionState.Connected }

        // Not "unavailable": the sheet shows the gateway's error message (the fake's is "nope").
        assertEquals("nope", assertFailsWith<Exception> { chat.checkpoints() }.message)

        // The restore may still have happened on the gateway, so the transcript is read again. Attaching for the
        // rollback.* calls reads none, so the transcript below can only come from the restore's reload.
        history = """{"session_id":"stored-1","messages":[{"id":1,"role":"user","content":"hello"}]}"""
        assertEquals("nope", chat.restoreCheckpoint(Checkpoint("9f2c1ab47e0d", "", "")))
        assertEquals(1, chat.state.first { it.messages.size == 1 }.messages.size)
    }

    @Test
    fun aNewChatHasNoCheckpointsAndIsntCreatedForThem() = runTest {
        val (connection, transport) = setup(backgroundScope, emptyMap())
        val chat = ChatSession(url, null, null, connection, SessionsApi(client()), backgroundScope)
        connection.state.first { it is dev.hermeskotlin.core.connection.ConnectionState.Connected }
        assertNull(chat.checkpoints())
        assertTrue(transport.sent.value.none { it.isCall("session.create") || it.isCall("rollback.list") })
    }
}
