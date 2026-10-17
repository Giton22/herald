package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.rpc.isCall
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.rpc.param
import dev.hermeskotlin.core.sessions.SessionMessage
import dev.hermeskotlin.core.sessions.SessionsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Picking the event stream up after a drop (`session.events.since`) and reading the transcript a page at a time. */
class ChatSessionResumeTest {

    private val url = FakeGateway.URL

    /** The stored transcript: odd ids are prompts `p<id>`, even ids replies `a<id>`. */
    private var transcript: List<String> = rowsUpTo(2)

    /** Every transcript read, as `limit/offset`. */
    private val reads = mutableListOf<String>()

    private fun rowsUpTo(last: Int) = (1..last).map { id ->
        if (id % 2 == 1) """{"id":$id,"role":"user","content":"p$id"}""" else """{"id":$id,"role":"assistant","content":"a$id"}"""
    }

    /** The gateway of the latest [connect]; its client reads [transcript] a page at a time. */
    private lateinit var gateway: FakeGateway

    private fun CoroutineScope.client() = gateway.client

    /** How the fake gateway answers (see [FakeGateway.answer]): socket, method, params. */
    private var answer: (FakeTransport, String, JsonObject) -> String? = { _, _, _ -> "{}" }

    /** The replay epoch the next connection's `gateway.ready` names. */
    private var epoch = "e1"

    /** Every connect opens a fresh transport, so a dropped link comes back; the latest is last. */
    private suspend fun connect(scope: CoroutineScope): Pair<GatewayConnection, List<FakeTransport>> {
        gateway = FakeGateway(scope).apply {
            answer = { call -> this@ChatSessionResumeTest.answer(call.socket, call.method, call.params) }
            ready = { """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"$epoch"}}}""" }
            http = { request ->
                require(request.url.encodedPath == "/api/sessions/stored-1/messages") { "unexpected ${request.url}" }
                val limit = request.url.parameters["limit"]!!.toInt()
                val offset = request.url.parameters["offset"]?.toInt() ?: 0
                reads += "$limit/$offset"
                // order=latest: the offset and limit count back from the newest row.
                val page = transcript.reversed().drop(offset).take(limit).reversed()
                json("""{"session_id":"stored-1","messages":[${page.joinToString(",")}]}""")
            }
            // Not waited on: the chat started next sees the link come up.
            start(awaitConnected = false)
        }
        return gateway.connection to gateway.sockets
    }

    private fun event(type: String, seq: Long?, payload: String = "{}") =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"rt1",${seq?.let { "\"seq\":$it," } ?: ""}"payload":$payload}}"""

    private fun delta(seq: Long, text: String) = event("message.delta", seq, """{"text":"$text"}""")

    /** An event as `session.events.since` lists it. */
    private fun replayed(seq: Long, text: String) = """{"type":"message.delta","session_id":"rt1","seq":$seq,"payload":{"text":"$text"}}"""

    private fun since(vararg events: String, latest: Long, truncated: Boolean = false, epoch: String = "e1") =
        """{"events":[${events.joinToString(",")}],"latest_seq":$latest,"truncated":$truncated,"count":${events.size},"epoch":"$epoch","open_requests":[]}"""

    private fun ChatState.reply() = messages.last() as? ChatMessage.Assistant

    /**
     * A chat attached to rt1 with a turn streaming "Hel" (seqs 1-3), whose link then drops; [resume] answers the
     * `session.resume` that attaches it again.
     */
    private suspend fun droppedMidTurn(scope: CoroutineScope, resume: String = """{"session_id":"rt1","running":true}"""): Pair<ChatSession, List<FakeTransport>> {
        val base = answer
        var resumes = 0
        answer = { transport, method, params ->
            if (method == "session.resume") {
                if (resumes++ == 0) """{"session_id":"rt1","running":true}""" else resume
            } else {
                base(transport, method, params)
            }
        }
        val (connection, transports) = connect(scope)
        val chat = ChatSession(url, "stored-1", "Chat", connection, SessionsApi(scope.client()), scope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        val first = transports.last()
        first.push(event("message.start", 1))
        first.push(delta(2, "He"))
        first.push(delta(3, "l"))
        chat.state.first { it.reply()?.text == "Hel" }
        first.serverClose(1006)
        chat.state.first { it.runtimeSessionId == null }
        return chat to transports
    }

    /** A turn that started at 100 (gateway clock) and saved its first step, text and a tool call, at [at]. */
    private fun savedFirstStep(at: Int = 105) = rowsUpTo(3) + listOf(
        """{"id":4,"role":"assistant","content":"Running it. First I need the address.","timestamp":$at,""" +
            """"tool_calls":[{"id":"t1","type":"function","function":{"name":"terminal","arguments":"{}"}}]}""",
        """{"id":5,"role":"tool","tool_call_id":"t1","content":"ok","timestamp":$at}""",
    )

    /** stored-1 opened while its turn runs, `session.resume` answering [resume]; the chat and its socket. */
    private suspend fun openMidTurn(scope: CoroutineScope, resume: String): Pair<ChatSession, FakeTransport> {
        answer = { _, method, _ -> if (method == "session.resume") resume else "{}" }
        val (connection, transports) = connect(scope)
        val chat = ChatSession(url, "stored-1", "Chat", connection, SessionsApi(scope.client()), scope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        return chat to transports.last()
    }

    private val lenient = Json { ignoreUnknownKeys = true }

    private fun ChatState.replies(text: String) = messages.filterIsInstance<ChatMessage.Assistant>().filter { text in it.text }

    private fun midTurn(assistant: String, startedAt: Int? = 100) =
        """{"session_id":"rt1","running":true,${startedAt?.let { "\"turn_started_at\":$it," } ?: ""}"inflight":{"user":"p3","assistant":"$assistant","streaming":true}}"""

    @Test
    fun aChatOpenedMidTurnShowsTheStepsAlreadySavedOnce() = runTest {
        transcript = savedFirstStep()
        val (chat, socket) = openMidTurn(backgroundScope, midTurn("Running it. First I need the address."))

        val reply = chat.state.value.replies("First I need the address").single()
        assertTrue(reply.streaming)
        assertEquals(listOf("t1"), reply.tools.map { it.id }, "the saved step's tool call was lost")
        // The rest of the turn still lands on that one reply.
        socket.push(delta(1, " Found it."))
        val more = chat.state.first { it.replies("Found it").isNotEmpty() }
        assertEquals("Running it. First I need the address. Found it.", more.replies("First I need").single().text)
    }

    @Test
    fun aLiveCopyAheadOfTheSavedStepsKeepsItsText() = runTest {
        transcript = savedFirstStep()
        val (chat, _) = openMidTurn(backgroundScope, midTurn("Running it. First I need the address.Got it: the address works."))

        val reply = chat.state.value.replies("First I need the address").single()
        assertEquals("Running it. First I need the address.Got it: the address works.", reply.text)
        assertEquals(listOf("t1"), reply.tools.map { it.id })
    }

    @Test
    fun aReplyFromBeforeTheTurnStaysApart() = runTest {
        // The same words answered the previous turn, saved before this one started.
        transcript = savedFirstStep(at = 90)
        val (chat, _) = openMidTurn(backgroundScope, midTurn("Running it. First I need the address."))

        assertEquals(2, chat.state.value.replies("First I need the address").size)
    }

    @Test
    fun aDifferentLiveReplyStaysApart() = runTest {
        transcript = savedFirstStep()
        val (chat, _) = openMidTurn(backgroundScope, midTurn("Something else entirely."))

        val state = chat.state.value
        assertEquals(1, state.replies("First I need the address").size)
        assertEquals(1, state.replies("Something else entirely").size)
    }

    @Test
    fun aTurnWhosePromptDoesntShowFoldsItsSavedStepsToo() = runTest {
        // A turn started by a hidden prompt (a goal going on, say): its saved step follows the last turn's answer
        // with no prompt between them.
        transcript = rowsUpTo(1) + listOf(
            """{"id":2,"role":"assistant","content":"The last answer.","timestamp":90}""",
            """{"id":3,"role":"user","content":"Keep going.","display_kind":"hidden","timestamp":100}""",
            """{"id":4,"role":"assistant","content":"Checking the goal.","timestamp":105,""" +
                """"tool_calls":[{"id":"t1","type":"function","function":{"name":"terminal","arguments":"{}"}}]}""",
        )
        val (chat, _) = openMidTurn(backgroundScope, midTurn("Checking the goal."))

        val state = chat.state.first { it.replies("Checking the goal").size == 1 }
        val reply = state.replies("Checking the goal").single()
        assertTrue(reply.streaming)
        assertEquals(listOf("t1"), reply.tools.map { it.id }, "the saved step's tool call was lost")
        assertFalse(state.replies("The last answer").single().streaming)
    }

    @Test
    fun aSavedCallWithNoResultYetStillRunsUntilItEnds() = runTest {
        // Opened while the step's call runs: its row is saved, its result isn't yet.
        transcript = savedFirstStep().dropLast(1)
        val (chat, socket) = openMidTurn(backgroundScope, midTurn("Running it. First I need the address."))

        // The rows are laid out again once session.resume names the turn's start; then the call shows running.
        chat.state.first { it.replies("First I need the address").singleOrNull()?.tools?.singleOrNull()?.running == true }

        socket.push(event("tool.complete", 1, """{"tool_id":"t1","result":"ok"}"""))
        val done = chat.state.first { s -> s.replies("First I need the address").single().tools.single().let { !it.running } }
        assertEquals("ok", done.replies("First I need the address").single().tools.single().output)
    }

    @Test
    fun theRunningTurnsSavedStepsStartAReplyOfTheirOwn() {
        val rows = listOf(
            """{"id":1,"role":"assistant","content":"The last answer.","timestamp":90}""",
            """{"id":2,"role":"user","content":"[System: the goal goes on]","timestamp":100}""",
            """{"id":3,"role":"assistant","content":"Checking the goal.","timestamp":105}""",
        ).map { lenient.decodeFromString<SessionMessage>(it) }

        assertEquals(1, historyToMessages(rows).size, "without a running turn the steps stay one reply")
        assertEquals(listOf("The last answer.", "Checking the goal."), historyToMessages(rows, turnStartedAt = 100.0).map { (it as ChatMessage.Assistant).text })
    }

    @Test
    fun aGatewayWithoutTheTurnsStartFoldsNothing() = runTest {
        // Nothing says the saved reply is this turn's, so both stay rather than risk hiding one.
        transcript = savedFirstStep()
        val (chat, _) = openMidTurn(backgroundScope, midTurn("Running it. First I need the address.", startedAt = null))

        assertEquals(2, chat.state.value.replies("First I need the address").size)
    }

    @Test
    fun scrollingUpMidTurnKeepsTheSavedStepsInTheLiveReply() = runTest {
        // A long chat: the newest page leaves older rows to load, and its turn is running with a step saved.
        transcript = rowsUpTo(HISTORY_PAGE + 21) + listOf(
            """{"id":${HISTORY_PAGE + 22},"role":"assistant","content":"Running it. First I need the address.","timestamp":105,""" +
                """"tool_calls":[{"id":"t1","type":"function","function":{"name":"terminal","arguments":"{}"}}]}""",
        )
        val (chat, _) = openMidTurn(backgroundScope, midTurn("Running it. First I need the address."))
        assertTrue(chat.state.value.olderMessages)

        chat.loadOlder()

        val reply = chat.state.value.replies("First I need the address").single()
        assertTrue(reply.streaming)
        assertEquals(listOf("t1"), reply.tools.map { it.id })
    }

    @Test
    fun aTurnThatEndedWhileAwayShowsItsAnswerOnce() = runTest {
        // Opened mid-turn; the link drops, the turn ends meanwhile, and the gateway can't replay what was missed.
        transcript = savedFirstStep()
        var resumes = 0
        answer = { _, method, _ ->
            when (method) {
                "session.resume" -> if (resumes++ == 0) midTurn("Running it. First I need the address.") else """{"session_id":"rt2","running":false}"""
                "session.events.since" -> "error:-32601"
                else -> "{}"
            }
        }
        val (connection, transports) = connect(backgroundScope)
        val chat = ChatSession(url, "stored-1", "Chat", connection, SessionsApi(backgroundScope.client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        transcript = savedFirstStep() + listOf(
            """{"id":6,"role":"assistant","content":"Found it: the address works.","timestamp":110}""",
        )
        transports.last().serverClose(1006)

        val back = chat.state.first { it.runtimeSessionId == "rt2" && !it.running && it.replies("Found it").isNotEmpty() }
        val answer = back.replies("First I need the address").single()
        assertFalse(answer.streaming)
        assertTrue("Found it" in answer.text)
        assertEquals(listOf("t1"), answer.tools.map { it.id })
    }

    @Test
    fun aLeftOverReplyOfAnEarlierTurnTakesInNothing() = runTest {
        // This turn's first step has only a tool call; the live reply open is an older turn's, not the gateway's copy.
        transcript = rowsUpTo(3) + listOf(
            """{"id":4,"role":"assistant","content":"","timestamp":105,"tool_calls":[{"id":"t9","type":"function","function":{"name":"terminal","arguments":"{}"}}]}""",
        )
        val merged = (historyToMessages(transcript.map { lenient.decodeFromString<SessionMessage>(it) }) +
            ChatMessage.Assistant("live-0", text = "An older answer, half sent", streaming = true))
            .withSavedStepsInLiveReply(turnStartedAt = 100.0, inflightText = "")

        assertEquals(2, merged.filterIsInstance<ChatMessage.Assistant>().count { it.tools.any { t -> t.id == "t9" } || it.streaming })
        assertTrue((merged.last() as ChatMessage.Assistant).tools.isEmpty())
    }

    @Test
    fun textStreamedOntoAToolOnlyStepStaysOneReplyWhenScrollingUp() = runTest {
        // Opened while the turn's first step, a tool call with reasoning and no text yet, runs: the gateway's copy is
        // empty. Text and more reasoning stream on after that, and then older rows load.
        transcript = rowsUpTo(HISTORY_PAGE + 21) + listOf(
            """{"id":${HISTORY_PAGE + 22},"role":"assistant","content":"","reasoning":"Look it up.","timestamp":105,""" +
                """"tool_calls":[{"id":"t1","type":"function","function":{"name":"terminal","arguments":"{}"}}]}""",
        )
        val (chat, socket) = openMidTurn(backgroundScope, midTurn(""))
        socket.push(delta(1, "Found it."))
        socket.push(event("reasoning.delta", 2, """{"text":" Then check it."}"""))
        chat.state.first { it.messages.any { m -> m is ChatMessage.Assistant && "Then check it" in m.reasoning } }

        chat.loadOlder()

        val replies = chat.state.value.messages.filterIsInstance<ChatMessage.Assistant>().filter { it.tools.any { t -> t.id == "t1" } }
        val reply = replies.single()
        assertTrue(reply.streaming)
        assertEquals("Found it.", reply.text)
        assertEquals(1, Regex("Look it up").findAll(reply.reasoning).count(), "the saved reasoning came again: ${reply.reasoning}")
        assertTrue("Then check it" in reply.reasoning)
    }

    @Test
    fun aNoteOfThisDeviceBetweenTheSavedStepAndTheLiveReplyDoesntPartThem() {
        val rows = savedFirstStep().map { lenient.decodeFromString<SessionMessage>(it) }
        val merged = (historyToMessages(rows) + ChatMessage.Notice("n1", "Reconnected") +
            ChatMessage.Assistant("live-0", text = "Running it. First I need the address.", streaming = true))
            .withSavedStepsInLiveReply(turnStartedAt = 100.0, inflightText = "Running it. First I need the address.")

        val reply = merged.filterIsInstance<ChatMessage.Assistant>().single { "First I need the address" in it.text }
        assertTrue(reply.streaming)
        assertEquals(listOf("t1"), reply.tools.map { it.id })
        assertTrue(merged.any { it is ChatMessage.Notice && it.key == "n1" })
    }

    @Test
    fun aFoldAgainKeepsTheLiveCopyOfARunningTool() {
        // Folded once; the saved row came again while its tool still ran, with no result row yet.
        val rows = savedFirstStep().dropLast(1).map { lenient.decodeFromString<SessionMessage>(it) }
        val saved = historyToMessages(rows, turnStartedAt = 100.0)
        val savedTool = (saved.last() as ChatMessage.Assistant).tools.single()
        val live = ChatMessage.Assistant(
            "live-0",
            text = "Running it. First I need the address.",
            tools = listOf(savedTool.copy(running = true)),
            streaming = true,
        )
        val merged = (saved + live).withSavedStepsInLiveReply(100.0, "Running it.", turnReplyKey = "live-0")

        assertTrue((merged.last() as ChatMessage.Assistant).tools.single().running)
    }

    @Test
    fun aSavedResultEndsALiveCopyStillRunning() {
        // The call's end went while the link was down and wasn't replayed; the saved row has its result.
        val saved = historyToMessages(savedFirstStep().map { lenient.decodeFromString<SessionMessage>(it) })
        val savedTool = (saved.last() as ChatMessage.Assistant).tools.single()
        val live = ChatMessage.Assistant(
            "live-0",
            text = "Running it. First I need the address.",
            tools = listOf(savedTool.copy(running = true, output = null)),
            streaming = true,
        )
        val tool = ((saved + live).withSavedStepsInLiveReply(100.0, "Running it.", turnReplyKey = "live-0").last() as ChatMessage.Assistant)
            .tools.single()

        assertFalse(tool.running)
        assertEquals(savedTool.output, tool.output)
    }

    @Test
    fun aSavedEmptyResultEndsALiveCopyStillRunning() {
        // As above, with a call that printed nothing: its result row is there, its output empty.
        val rows = savedFirstStep().dropLast(1) + """{"id":5,"role":"tool","tool_call_id":"t1","content":"","timestamp":105}"""
        val saved = historyToMessages(rows.map { lenient.decodeFromString<SessionMessage>(it) }, turnStartedAt = 100.0)
        val savedTool = (saved.last() as ChatMessage.Assistant).tools.single()
        val live = ChatMessage.Assistant(
            "live-0",
            text = "Running it. First I need the address.",
            tools = listOf(savedTool.copy(running = true)),
            streaming = true,
        )
        val tool = ((saved + live).withSavedStepsInLiveReply(100.0, "Running it.", turnReplyKey = "live-0").last() as ChatMessage.Assistant)
            .tools.single()

        assertFalse(tool.running, "a call that ended with nothing to say kept running")
    }

    @Test
    fun anEndTheLiveCopyGotStaysOverAnOlderSavedRow() {
        // The rows were read before the call's result was saved; the live copy got the end itself since.
        val saved = historyToMessages(savedFirstStep().dropLast(1).map { lenient.decodeFromString<SessionMessage>(it) }, turnStartedAt = 100.0)
        val savedTool = (saved.last() as ChatMessage.Assistant).tools.single()
        assertTrue(savedTool.running)
        val live = ChatMessage.Assistant(
            "live-0",
            text = "Running it. First I need the address.",
            tools = listOf(savedTool.copy(running = false, durationSeconds = 1.5)),
            streaming = true,
        )
        val tool = ((saved + live).withSavedStepsInLiveReply(100.0, "Running it.", turnReplyKey = "live-0").last() as ChatMessage.Assistant)
            .tools.single()

        assertFalse(tool.running, "a call that ended ran again")
        assertEquals(1.5, tool.durationSeconds)
    }

    @Test
    fun aStepSavedAfterTextStreamedOnStillFoldsIn() = runTest {
        // Opened while the turn's first step, a tool call with no text, ran and wasn't saved yet; text streams on,
        // then the step's row is saved and read.
        transcript = rowsUpTo(3)
        val (chat, socket) = openMidTurn(backgroundScope, midTurn(""))
        socket.push(delta(1, "Found it."))
        chat.state.first { it.replies("Found it").isNotEmpty() }
        transcript = rowsUpTo(3) + listOf(
            """{"id":4,"role":"assistant","content":"","timestamp":105,"tool_calls":[{"id":"t1","type":"function","function":{"name":"terminal","arguments":"{}"}}]}""",
        )
        // Another client's turn starting reads the transcript again.
        socket.push(event("message.start", 2))

        val state = chat.state.first { s -> s.messages.any { it is ChatMessage.Assistant && it.tools.any { t -> t.id == "t1" } } }
        val reply = state.messages.filterIsInstance<ChatMessage.Assistant>().single { it.tools.any { t -> t.id == "t1" } }
        assertTrue(reply.streaming)
        assertEquals("Found it.", reply.text)
    }

    @Test
    fun aDropMidTurnPicksUpFromTheLastEventWithoutDuplicates() = runTest {
        answer = { transport, method, _ ->
            when (method) {
                // Live frames already on their way as the replay is asked for: the overlap must not show twice.
                "session.events.since" -> since(replayed(3, "l"), replayed(4, "lo"), replayed(5, " wor"), latest = 5).also {
                    transport.push(delta(5, " wor"))
                    transport.push(delta(6, "ld"))
                }
                else -> "{}"
            }
        }
        val (chat, transports) = droppedMidTurn(backgroundScope)
        val readsBefore = reads.size

        val done = chat.state.first { it.reply()?.text?.endsWith("ld") == true }

        assertEquals("Hello world", done.reply()?.text)
        val ask = transports.last().sent.value.first { it.isCall("session.events.since") }
        assertEquals("rt1", ask.param("session_id"))
        assertEquals("3", ask.param("last_seen"))
        assertEquals(readsBefore, reads.size, "the transcript was read again although the replay filled the gap")

        // The rest of the turn streams on, and a frame sent twice is shown once.
        transports.last().push(delta(6, "ld"))
        transports.last().push(delta(7, "!"))
        transports.last().push(event("message.complete", 8, """{"text":"Hello world!","status":"complete"}"""))
        val finished = chat.state.first { !it.running && it.reply()?.streaming == false }
        assertEquals("Hello world!", finished.reply()?.text)
    }

    @Test
    fun aTurnThatEndedWhileAwayEndsFromTheReplay() = runTest {
        answer = { _, method, _ ->
            when (method) {
                "session.events.since" -> since(
                    replayed(4, "lo"),
                    """{"type":"message.complete","session_id":"rt1","seq":5,"payload":{"text":"Hello","status":"complete"}}""",
                    latest = 5,
                )
                else -> "{}"
            }
        }
        val (chat, _) = droppedMidTurn(backgroundScope, resume = """{"session_id":"rt1","running":false}""")

        val done = chat.state.first { !it.running && it.reply()?.streaming == false }

        assertEquals("Hello", done.reply()?.text)
        assertEquals(1, done.messages.count { it is ChatMessage.Assistant && it.text == "Hello" })
    }

    @Test
    fun aRingThatLostEventsFallsBackToTheTranscriptAndTheGatewaysCopy() = runTest {
        answer = { _, method, _ ->
            when (method) {
                "session.events.since" -> since(replayed(700, "x"), latest = 700, truncated = true)
                else -> "{}"
            }
        }
        val (chat, transports) = droppedMidTurn(
            backgroundScope,
            resume = """{"session_id":"rt1","running":true,"inflight":{"assistant":"Hello wor"}}""",
        )
        val readsBefore = reads.size

        val caught = chat.state.first { it.reply()?.text == "Hello wor" }

        assertTrue(reads.size > readsBefore, "the transcript wasn't reloaded after a replay with a hole")
        assertTrue(caught.reply()!!.streaming)
        // Live events go on from there, whatever their seq.
        transports.last().push(delta(701, "ld"))
        assertEquals("Hello world", chat.state.first { it.reply()?.text?.endsWith("ld") == true }.reply()?.text)
    }

    @Test
    fun aGatewayWithoutReplayReloadsAsBefore() = runTest {
        answer = { _, method, _ -> if (method == "session.events.since") "error:-32601" else "{}" }
        val (chat, _) = droppedMidTurn(
            backgroundScope,
            resume = """{"session_id":"rt1","running":true,"inflight":{"assistant":"Hello"}}""",
        )

        assertEquals("Hello", chat.state.first { it.reply()?.text == "Hello" }.reply()?.text)
    }

    @Test
    fun aStepSavedWhileTheReplayCantBeHadShowsOnce() = runTest {
        // The turn saved a step past what streamed here ("Hel") while the link was down, and the gap can't be replayed.
        transcript = rowsUpTo(3) + listOf(
            """{"id":4,"role":"assistant","content":"Hello","timestamp":105,""" +
                """"tool_calls":[{"id":"t1","type":"function","function":{"name":"terminal","arguments":"{}"}}]}""",
        )
        answer = { _, method, _ -> if (method == "session.events.since") "error:-32601" else "{}" }
        val (chat, _) = droppedMidTurn(
            backgroundScope,
            resume = """{"session_id":"rt1","running":true,"turn_started_at":100,"inflight":{"assistant":"Hello wor"}}""",
        )

        val caught = chat.state.first { it.reply()?.text == "Hello wor" }

        val reply = caught.messages.filterIsInstance<ChatMessage.Assistant>().single { it.text.startsWith("Hello") }
        assertTrue(reply.streaming)
        assertEquals(listOf("t1"), reply.tools.map { it.id }, "the saved step's tool call was lost")
    }

    @Test
    fun aRestartedGatewayIsNotAskedForEventsItNeverSent() = runTest {
        val (chat, transports) = droppedMidTurn(
            backgroundScope,
            resume = """{"session_id":"rt1","running":true,"inflight":{"assistant":"Hello"}}""",
        )
        // Set while the link is down: the next gateway.ready names a new run.
        epoch = "e2"

        chat.state.first { it.runtimeSessionId == "rt1" && it.reply()?.text == "Hello" }

        assertFalse(transports.last().sent.value.any { it.isCall("session.events.since") })
    }

    @Test
    fun aChatTheGatewayNoLongerHasStopsShowingATurn() = runTest {
        // Its bot was deleted mid-turn: the turn never completes, and resuming is refused.
        val (chat, _) = droppedMidTurn(backgroundScope, resume = "error:4007")

        val gone = chat.state.first { it.attachment is Attachment.Failed }

        assertFalse(gone.running)
    }

    @Test
    fun stoppingATurnWhoseChatIsGoneStopsShowingIt() = runTest {
        var resumes = 0
        answer = { _, method, _ ->
            when (method) {
                // Attached mid-turn; by the time Stop is pressed, the bot and its chat were deleted elsewhere.
                "session.resume" -> if (resumes++ == 0) """{"session_id":"rt1","running":true}""" else "error:4007"
                "session.interrupt" -> "error:4007"
                else -> "{}"
            }
        }
        val (connection, _) = connect(backgroundScope)
        val chat = ChatSession(url, "stored-1", "Chat", connection, SessionsApi(client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.running }

        chat.interrupt()

        assertFalse(chat.state.first { it.attachment is Attachment.Failed }.running)
    }

    @Test
    fun aTurnThatEndedOnAReapedRuntimeLeavesNoStaleLiveReply() = runTest {
        // The gateway let the runtime go while we were away: a new one, nothing to replay, the turn in the transcript.
        transcript = rowsUpTo(4)
        val (chat, transports) = droppedMidTurn(backgroundScope, resume = """{"session_id":"rt2","running":false}""")

        val back = chat.state.first { it.runtimeSessionId == "rt2" }

        assertEquals(listOf("row-1", "row-2", "row-3", "row-4"), back.messages.map { it.key })
        assertFalse(back.messages.any { it is ChatMessage.Assistant && it.streaming })
        assertFalse(transports.last().sent.value.any { it.isCall("session.events.since") })
    }

    @Test
    fun aChatCreatedHereCatchesUpAfterADropToo() = runTest {
        answer = { _, method, _ ->
            when (method) {
                "session.create" -> """{"session_id":"rt1","stored_session_id":"stored-1","info":{}}"""
                "prompt.submit" -> """{"status":"streaming"}"""
                "session.resume" -> """{"session_id":"rt1","running":true}"""
                "session.events.since" -> since(replayed(3, "lo"), latest = 3)
                else -> "{}"
            }
        }
        val (connection, transports) = connect(backgroundScope)
        connection.state.first { it is ConnectionState.Connected }
        val chat = ChatSession(url, null, null, connection, SessionsApi(backgroundScope.client()), backgroundScope)
        chat.start()
        assertTrue(chat.send("hi"))
        val first = transports.last()
        first.push(event("message.start", 1))
        first.push(delta(2, "Hel"))
        chat.state.first { it.reply()?.text == "Hel" }
        first.serverClose(1006)

        assertEquals("Hello", chat.state.first { it.reply()?.text == "Hello" }.reply()?.text)
        assertEquals("2", transports.last().sent.value.first { it.isCall("session.events.since") }.param("last_seen"))
    }

    @Test
    fun aGapInTheLiveStreamIsFilledBeforeWhatFollowsIt() = runTest {
        answer = { _, method, _ ->
            when (method) {
                "session.resume" -> """{"session_id":"rt1","running":false}"""
                "session.events.since" -> since(replayed(2, "b"), replayed(3, "c"), latest = 4)
                else -> "{}"
            }
        }
        val (connection, transports) = connect(backgroundScope)
        val chat = ChatSession(url, "stored-1", "Chat", connection, SessionsApi(backgroundScope.client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }

        transports.last().push(event("message.delta", 1, """{"text":"a"}"""))
        chat.state.first { it.reply()?.text == "a" }
        transports.last().push(delta(4, "d"))

        assertEquals("abcd", chat.state.first { it.reply()?.text == "abcd" }.reply()?.text)
        assertEquals("1", transports.last().sent.value.first { it.isCall("session.events.since") }.param("last_seen"))
    }

    @Test
    fun anIdleChatWithNothingToResumeFromReloadsTheTranscript() = runTest {
        answer = { _, method, _ -> if (method == "session.resume") """{"session_id":"rt1","running":false}""" else "{}" }
        val (connection, transports) = connect(backgroundScope)
        val chat = ChatSession(url, "stored-1", "Chat", connection, SessionsApi(backgroundScope.client()), backgroundScope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        transcript = rowsUpTo(4)
        val dropped = transports.last()
        dropped.serverClose(1006)

        val back = chat.state.first { it.messages.size == 4 }

        assertEquals(listOf("p1", "a2", "p3", "a4"), back.messages.map { (it as? ChatMessage.User)?.text ?: (it as ChatMessage.Assistant).text })
        assertFalse(transports.last().sent.value.any { it.isCall("session.events.since") })
    }

    /** A chat over a long transcript, attached and showing its newest page. */
    private suspend fun longChat(scope: CoroutineScope, rows: Int, stored: List<String> = rowsUpTo(rows)): Pair<ChatSession, List<FakeTransport>> {
        transcript = stored
        answer = { _, method, _ -> if (method == "session.resume") """{"session_id":"rt1","running":false}""" else "{}" }
        val (connection, transports) = connect(scope)
        val chat = ChatSession(url, "stored-1", "Chat", connection, SessionsApi(scope.client()), scope)
        chat.start()
        chat.state.first { it.runtimeSessionId == "rt1" && it.historyLoaded }
        return chat to transports
    }

    private fun ChatState.keys() = messages.map { it.key }

    @Test
    fun aLongChatOpensOnItsNewestPageFromATurnStart() = runTest {
        val (chat, _) = longChat(backgroundScope, 150)

        val state = chat.state.value
        assertEquals("${HISTORY_PAGE}/0", reads.first())
        assertTrue(state.olderMessages)
        // Rows 91-150, from the first prompt: 91 is one.
        assertEquals("row-91", state.messages.first().key)
        assertEquals("row-150", state.messages.last().key)
    }

    @Test
    fun scrollingUpLoadsOlderPagesAboveWithoutDisturbingTheShownOnes() = runTest {
        val (chat, _) = longChat(backgroundScope, 150)
        val shown = chat.state.value.keys()

        chat.loadOlder()

        val more = chat.state.value
        assertEquals("${HISTORY_PAGE}/60", reads.last())
        assertEquals((31..150).map { "row-$it" }, more.keys())
        assertEquals(shown, more.keys().takeLast(shown.size))
        assertTrue(more.olderMessages)
        assertFalse(more.loadingOlder)

        chat.loadOlder()

        val all = chat.state.value
        assertEquals((1..150).map { "row-$it" }, all.keys())
        assertFalse(all.olderMessages)
        // Nothing older to ask for.
        val readsSoFar = reads.size
        chat.loadOlder()
        assertEquals(readsSoFar, reads.size)
    }

    @Test
    fun anOlderPageStartsAtAPromptSoNoTurnIsCutInTwo() = runTest {
        // An even count: the newest page (rows 92-151) opens on a reply, so it starts at 93 instead.
        val (chat, _) = longChat(backgroundScope, 151)
        assertEquals("row-93", chat.state.value.messages.first().key)

        chat.loadOlder()

        // Read from the 59 rows loaded back: rows 33-92, from the prompt at 33.
        assertEquals("${HISTORY_PAGE}/59", reads.last())
        assertEquals((33..151).map { "row-$it" }, chat.state.value.keys())
    }

    @Test
    fun aLongTurnAboveRowsWrittenSinceIsLoadedWhole() = runTest {
        // A prompt at 1, one turn of 79 steps (2-80), then short turns from 81 on.
        fun turns(last: Int) = (1..last).map { id ->
            if (id == 1 || (id > 80 && id % 2 == 1)) """{"id":$id,"role":"user","content":"p$id"}""" else """{"id":$id,"role":"assistant","content":"a$id"}"""
        }
        val (chat, _) = longChat(backgroundScope, 90, turns(90))
        assertEquals("row-81", chat.state.value.messages.first().key)
        // A turn streamed live since: its rows are stored, but the loaded ones weren't read again.
        transcript = turns(92)

        repeat(4) { if (chat.state.value.olderMessages) chat.loadOlder() }

        val state = chat.state.value
        assertFalse(state.olderMessages)
        assertEquals(listOf("row-1", "row-2") + (81..90).map { "row-$it" }, state.keys())
        // Every step of the long turn, none skipped past.
        assertEquals((2..80).joinToString("\n\n") { "a$it" }, (state.messages[1] as ChatMessage.Assistant).text)
    }

    @Test
    fun reloadingTheNewestPageKeepsTheOlderPagesScrolledTo() = runTest {
        val (chat, _) = longChat(backgroundScope, 150)
        chat.loadOlder()
        transcript = rowsUpTo(152)

        chat.retry()

        val state = chat.state.first { it.messages.last().key == "row-152" }
        assertEquals("${HISTORY_PAGE}/0", reads.last())
        assertEquals((31..152).map { "row-$it" }, state.keys())
        assertTrue(state.olderMessages)
    }

    @Test
    fun aNewestPageThatNoLongerMeetsTheLoadedRowsStartsOver() = runTest {
        val (chat, _) = longChat(backgroundScope, 150)
        transcript = rowsUpTo(300)

        chat.retry()

        val state = chat.state.first { it.messages.last().key == "row-300" }
        assertEquals((241..300).map { "row-$it" }, state.keys())
        assertTrue(state.olderMessages)
        chat.loadOlder()
        assertEquals((181..300).map { "row-$it" }, chat.state.value.keys())
    }

    @Test
    fun loadingEverythingReadsTheWholeTranscript() = runTest {
        val (chat, _) = longChat(backgroundScope, 1_200)

        assertTrue(chat.loadAllHistory())

        val state = chat.state.value
        assertFalse(state.olderMessages)
        assertEquals("row-1", state.messages.first().key)
        assertEquals(1_200, state.messages.size)
        assertTrue(reads.drop(1).all { it.startsWith("$HISTORY_PAGE_MAX/") })
    }

    /** Sends [text] on a long chat whose gateway takes the prompt but drops the link before answering. */
    private suspend fun lostReply(scope: CoroutineScope, text: String, arrives: Boolean): SendOutcome {
        val (chat, transports) = longChat(scope, 150)
        val base = answer
        answer = { transport, method, params -> if (method == "prompt.submit") null else base(transport, method, params) }
        val transport = transports.last()
        val sending = scope.async { chat.submit(text) }
        transport.awaitSent { it.isCall("prompt.submit") }
        if (arrives) transcript = transcript + """{"id":151,"role":"user","content":"$text"}"""
        transport.serverClose(1006)
        return sending.await().also { outcome ->
            if (outcome == SendOutcome.Sent) assertEquals("row-151", chat.state.value.messages.last().key)
        }
    }

    @Test
    fun deliveryIsCheckedAgainstThePagedTranscript() = runTest {
        // 30 prompts are shown out of 75: the count lines up with the loaded stretch, not the whole transcript.
        assertEquals(SendOutcome.Sent, lostReply(backgroundScope, "deploy it", arrives = true))
    }

    @Test
    fun aPromptMissingFromThePagedTranscriptIsHandedBack() = runTest {
        // "p149" is already shown; the same text again never arrived.
        assertEquals(SendOutcome.NotSent, lostReply(backgroundScope, "p149", arrives = false))
    }
}
