package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Picking the event stream up after a drop (`session.events.since`) and reading the transcript a page at a time. */
class ChatSessionResumeTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** The stored transcript: odd ids are prompts `p<id>`, even ids replies `a<id>`. */
    private var transcript: List<String> = rowsUpTo(2)

    /** Every transcript read, as `limit/offset`. */
    private val reads = mutableListOf<String>()

    private fun rowsUpTo(last: Int) = (1..last).map { id ->
        if (id % 2 == 1) """{"id":$id,"role":"user","content":"p$id"}""" else """{"id":$id,"role":"assistant","content":"a$id"}"""
    }

    private fun client() = createHttpClient(
        MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                "/api/sessions/stored-1/messages" -> {
                    val limit = request.url.parameters["limit"]!!.toInt()
                    val offset = request.url.parameters["offset"]?.toInt() ?: 0
                    reads += "$limit/$offset"
                    // order=latest: the offset and limit count back from the newest row.
                    val page = transcript.reversed().drop(offset).take(limit).reversed()
                    respond("""{"session_id":"stored-1","messages":[${page.joinToString(",")}]}""", HttpStatusCode.OK, json)
                }
                else -> error("unexpected ${request.url}")
            }
        },
    )

    /** How the fake gateway answers: a JSON result, `error:<code>`, or null to stay silent. */
    private var answer: (FakeTransport, String, JsonObject) -> String? = { _, _, _ -> "{}" }

    private fun CoroutineScope.serve(transport: FakeTransport) = launch {
        var answered = 0
        transport.sent.collect { sent ->
            sent.drop(answered).forEach { message ->
                answered++
                val id = message["id"] ?: return@forEach
                val method = message["method"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val canned = answer(transport, method, message["params"]?.jsonObject ?: JsonObject(emptyMap())) ?: return@forEach
                transport.push(
                    if (canned.startsWith("error:")) {
                        """{"jsonrpc":"2.0","id":$id,"error":{"code":${canned.removePrefix("error:")},"message":"nope"}}"""
                    } else {
                        """{"jsonrpc":"2.0","id":$id,"result":$canned}"""
                    },
                )
            }
        }
    }

    /** The replay epoch the next connection's `gateway.ready` names. */
    private var epoch = "e1"

    /** Every connect opens a fresh transport, so a dropped link comes back; the latest is last. */
    private fun connect(scope: CoroutineScope): Pair<GatewayConnection, List<FakeTransport>> {
        val transports = mutableListOf<FakeTransport>()
        val connection = GatewayConnection(
            AuthApi(client(), PersistentCookiesStorage(InMemoryKeyValueStore())),
            { _, _ ->
                FakeTransport().also {
                    transports += it
                    scope.serve(it)
                    it.push("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"$epoch"}}}""")
                }
            },
            scope,
        )
        connection.start(url)
        return connection to transports
    }

    private fun event(type: String, seq: Long?, payload: String = "{}") =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"rt1",${seq?.let { "\"seq\":$it," } ?: ""}"payload":$payload}}"""

    private fun delta(seq: Long, text: String) = event("message.delta", seq, """{"text":"$text"}""")

    /** An event as `session.events.since` lists it. */
    private fun replayed(seq: Long, text: String) = """{"type":"message.delta","session_id":"rt1","seq":$seq,"payload":{"text":"$text"}}"""

    private fun since(vararg events: String, latest: Long, truncated: Boolean = false, epoch: String = "e1") =
        """{"events":[${events.joinToString(",")}],"latest_seq":$latest,"truncated":$truncated,"count":${events.size},"epoch":"$epoch","open_requests":[]}"""

    private fun JsonObject.isCall(method: String) = this["method"]?.jsonPrimitive?.contentOrNull == method

    private fun JsonObject.param(name: String) = this["params"]?.jsonObject?.get(name)?.jsonPrimitive?.contentOrNull

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
        val chat = ChatSession(url, "stored-1", "Chat", connection, SessionsApi(client()), scope)
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
        val chat = ChatSession(url, null, null, connection, SessionsApi(client()), backgroundScope)
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
        val chat = ChatSession(url, "stored-1", "Chat", connection, SessionsApi(client()), backgroundScope)
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
        val chat = ChatSession(url, "stored-1", "Chat", connection, SessionsApi(client()), backgroundScope)
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
    private suspend fun longChat(scope: CoroutineScope, rows: Int): Pair<ChatSession, List<FakeTransport>> {
        transcript = rowsUpTo(rows)
        answer = { _, method, _ -> if (method == "session.resume") """{"session_id":"rt1","running":false}""" else "{}" }
        val (connection, transports) = connect(scope)
        val chat = ChatSession(url, "stored-1", "Chat", connection, SessionsApi(client()), scope)
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
