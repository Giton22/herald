package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.sessions.ActiveSessions
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionWatcherTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** `gateway.ready` without the heartbeat, so moving the test clock never trips it. */
    private val ready = """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}"""

    /** A live row: stored id, status, title. Runtime ids are `rt-<stored id>` unless given. */
    private data class Row(val key: String, val status: String, val title: String = "Chat $key", val runtime: String = "rt-$key")

    /** What the fake gateway lists as live, and what `session.activate` hands back as open requests. */
    private val live = MutableStateFlow(listOf<Row>())
    private var openRequests = "[]"

    /** Every socket the connection opened, newest last. */
    private val sockets = mutableListOf<FakeTransport>()

    private val sent get() = sockets.flatMap { it.sent.value }
    private fun sentMethods(method: String) = sent.filter { it["method"]?.jsonPrimitive?.contentOrNull == method }

    private fun CoroutineScope.serve(transport: FakeTransport) = launch {
        var answered = 0
        transport.sent.collect { all ->
            all.drop(answered).forEach { message ->
                answered++
                val id = message["id"] ?: return@forEach
                val method = message["method"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val params = message["params"]?.jsonObject
                val sid = params?.get("session_id")?.jsonPrimitive?.contentOrNull.orEmpty()
                val result = when (method) {
                    "session.active_list" -> live.value.joinToString(",", """{"sessions":[""", "]}") {
                        """{"id":"${it.runtime}","session_key":"${it.key}","status":"${it.status}","title":"${it.title}"}"""
                    }
                    "session.activate" -> """{"session_id":"$sid","running":true,"open_requests":$openRequests}"""
                    "session.resume" -> """{"session_id":"rt-$sid","running":false}"""
                    else -> "{}"
                }
                transport.push("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
            }
        }
    }

    private class Setup(val connection: GatewayConnection, val host: ChatHost, val tracker: AttentionTracker, val watcher: SessionWatcher)

    private suspend fun TestScope.setup(maxWatched: Int = 10): Setup {
        val scope = backgroundScope
        val http = createHttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/api/auth/ws-ticket") respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                else respond("""{"messages":[]}""", HttpStatusCode.OK, json)
            },
        )
        val connection = GatewayConnection(
            AuthApi(http, PersistentCookiesStorage(InMemoryKeyValueStore())),
            { _, _ ->
                FakeTransport().also { transport ->
                    sockets += transport
                    scope.serve(transport)
                    transport.push(ready)
                }
            },
            scope,
        )
        connection.start(url)
        connection.state.first { it is ConnectionState.Connected }
        val clock = { testScheduler.currentTime + 1 }
        val host = ChatHost(connection, SessionsApi(http), scope)
        val active = ActiveSessions(connection, scope, clock = clock)
        val tracker = AttentionTracker(connection, host, active, scope, clock)
        return Setup(connection, host, tracker, SessionWatcher(connection, host, active, tracker, scope, maxWatched, clock))
    }

    private fun approval(id: String, runtimeId: String) =
        """{"jsonrpc":"2.0","id":"$id","method":"approval","params":{"session_id":"$runtimeId","command":"rm -rf build","choices":["once","deny"]}}"""

    private fun event(type: String, runtimeId: String, payload: String = "{}") =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"$runtimeId","payload":$payload}}"""

    @Test
    fun aChatRunningElsewhereIsAttachedByItsRuntimeIdWithoutItsMessages() = runTest {
        live.value = listOf(Row("a", "working"), Row("b", "idle"))
        val watcher = setup().watcher
        val chats = watcher.chats.first { it.isNotEmpty() }
        assertEquals(setOf("a"), chats.keys)
        val activate = sentMethods("session.activate").single()["params"]!!.jsonObject
        assertEquals("rt-a", activate["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("true", activate["omit_messages"]?.jsonPrimitive?.contentOrNull)
        assertTrue(sentMethods("session.resume").isEmpty())
    }

    @Test
    fun theOpenChatBotChatsAndChatsAlreadyWatchedAreSkipped() = runTest {
        val setup = setup()
        setup.host.open(url, "open", null).state.first { it.runtimeSessionId == "rt-open" }
        live.value = listOf(Row("open", "working"), Row("bot", "working", title = "Bot Chat"), Row("a", "waiting"))
        setup.watcher.chats.first { "a" in it }
        advanceTimeBy(10_001)
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(setOf("a"), setup.watcher.chats.value.keys)
        assertEquals(listOf("rt-a"), sentMethods("session.activate").map { it["params"]!!.jsonObject["session_id"]!!.jsonPrimitive.content })
    }

    @Test
    fun noMoreThanTheLimitAreAttachedAndNoneIsEverClosed() = runTest {
        live.value = listOf(Row("a", "working"), Row("b", "starting"), Row("c", "waiting"))
        val watcher = setup(maxWatched = 2).watcher
        watcher.chats.first { it.size == 2 }
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(2, watcher.chats.value.size)
        assertEquals(2, sentMethods("session.activate").size)
        assertTrue(sentMethods("session.close").isEmpty())
    }

    @Test
    fun requestsComeFromTheAttachAndLiveAndLeaveWhenWithdrawnOrTheTurnEnds() = runTest {
        openRequests = """[{"id":"srq-old","method":"approval","params":{"session_id":"rt-a","command":"ls","choices":["once","deny"]}}]"""
        live.value = listOf(Row("a", "waiting"))
        val setup = setup()
        val watcher = setup.watcher
        assertEquals(listOf("srq-old"), watcher.chats.first { it["a"]?.requests?.isNotEmpty() == true }.getValue("a").requests.map { it.id })
        // The list's label knows the kind of request, not just that the chat waits.
        assertEquals(Waiting.Approval, setup.tracker.waiting.first { "a" in it }["a"])

        sockets.last().push(approval("srq-new", "rt-a"))
        watcher.chats.first { chats -> chats["a"]?.requests?.any { it.id == "srq-new" } == true }
        sockets.last().push(event("request.cancel", "rt-a", """{"id":"srq-old"}"""))
        watcher.chats.first { chats -> chats["a"]?.requests?.map { it.id } == listOf("srq-new") }

        val end = async { watcher.turnEnds.first() }
        runCurrent()
        sockets.last().push(event("message.complete", "rt-a", """{"text":"All done.","status":"complete"}"""))
        assertEquals(WatchedTurnEnd("a", "Chat a", "All done.", TurnOutcome.Complete, null), end.await())
        assertTrue(watcher.chats.value.getValue("a").requests.isEmpty())
    }

    @Test
    fun aRequestAnsweredOnAnotherClientLeavesOnceTheListStopsShowingItWaiting() = runTest {
        live.value = listOf(Row("a", "working"))
        val watcher = setup().watcher
        watcher.chats.first { "a" in it }
        sockets.last().push(approval("srq-1", "rt-a"))
        watcher.chats.first { it["a"]?.requests?.isNotEmpty() == true }
        live.value = listOf(Row("a", "waiting"))
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(1, watcher.chats.value.getValue("a").requests.size)

        // Desktop answered: no event says so, but the next list shows the turn working again.
        live.value = listOf(Row("a", "working"))
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(watcher.chats.value.getValue("a").requests.isEmpty())
    }

    @Test
    fun anAnswerFromANotificationGoesOutWithTheRequestId() = runTest {
        live.value = listOf(Row("a", "working"))
        val watcher = setup().watcher
        watcher.chats.first { "a" in it }
        sockets.last().push(approval("srq-1", "rt-a"))
        watcher.chats.first { it["a"]?.requests?.isNotEmpty() == true }
        assertEquals("srq-1", watcher.find("srq-1")?.second?.id)

        assertTrue(watcher.answer("srq-1", buildJsonObject { put("choice", "once") }))
        val response = sockets.last().awaitSent { it["id"]?.jsonPrimitive?.contentOrNull == "srq-1" && "result" in it }
        assertEquals("once", (response["result"] as JsonObject)["choice"]?.jsonPrimitive?.contentOrNull)
        assertNull(watcher.find("srq-1"))
    }

    @Test
    fun anInlineReplyIsSubmittedToTheLiveSession() = runTest {
        live.value = listOf(Row("a", "working"))
        val watcher = setup().watcher
        watcher.chats.first { "a" in it }
        assertTrue(watcher.reply("a", "Thanks, go on."))
        val submit = sentMethods("prompt.submit").single()["params"]!!.jsonObject
        assertEquals("rt-a", submit["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Thanks, go on.", submit["text"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun aNewSocketAttachesAgain() = runTest {
        live.value = listOf(Row("a", "working"))
        val setup = setup()
        setup.watcher.chats.first { "a" in it }
        sockets.last().serverClose(1006, "gone")
        setup.connection.state.first { it !is ConnectionState.Connected }
        advanceTimeBy(2_000)
        setup.connection.state.first { it is ConnectionState.Connected }
        setup.watcher.chats.first { "a" in it }
        runCurrent()
        assertEquals(2, sockets.size)
        assertEquals(1, sockets.last().sent.value.count { it["method"]?.jsonPrimitive?.contentOrNull == "session.activate" })
    }

    private suspend fun TestScope.reconnect(setup: Setup) {
        sockets.last().serverClose(1006, "gone")
        setup.connection.state.first { it !is ConnectionState.Connected }
        advanceTimeBy(2_000)
        setup.connection.state.first { it is ConnectionState.Connected }
        runCurrent()
    }

    @Test
    fun aRequestAnsweredInTheOpenChatNoLongerWaitsOnceTheChatIsLeft() = runTest {
        live.value = listOf(Row("a", "working"))
        val setup = setup()
        setup.watcher.chats.first { "a" in it }
        // Watched first, then opened: both hear its requests on the one socket.
        val session = setup.host.open(url, "a", null)
        session.state.first { it.runtimeSessionId == "rt-a" }
        sockets.last().push(approval("srq-1", "rt-a"))
        val request = session.state.first { it.inputRequests.isNotEmpty() }.inputRequests.single()
        setup.watcher.chats.first { it["a"]?.requests?.isNotEmpty() == true }

        assertTrue(session.answer(request, InputAnswers.approval(ApprovalChoice.Once)))
        session.state.first { it.inputRequests.isEmpty() }
        runCurrent()
        setup.host.open(url, "b", null)
        runCurrent()
        // Else, once out of sight, the approval just given would notify again.
        assertTrue(setup.watcher.chats.value.getValue("a").requests.isEmpty())
    }

    @Test
    fun aNewSocketKeepsAWaitingRequestInsteadOfDroppingItAndAskingAgain() = runTest {
        openRequests = """[{"id":"srq-1","method":"approval","params":{"session_id":"rt-a","command":"ls","choices":["once","deny"]}}]"""
        live.value = listOf(Row("a", "waiting"))
        val setup = setup()
        setup.watcher.chats.first { it["a"]?.requests?.isNotEmpty() == true }
        val seen = mutableListOf<List<String>>()
        backgroundScope.launch { setup.watcher.chats.collect { all -> seen += all["a"]?.requests.orEmpty().map { it.id } } }
        runCurrent()

        reconnect(setup)
        assertEquals(1, sockets.last().sent.value.count { it["method"]?.jsonPrimitive?.contentOrNull == "session.activate" })
        // A gap would take its notification down and post it again, with a second heads-up.
        assertTrue(seen.all { it == listOf("srq-1") }, "requests seen: $seen")
    }

    @Test
    fun aRequestAnsweredWhileTheSocketWasDownLeavesWithTheNewAttach() = runTest {
        openRequests = """[{"id":"srq-1","method":"approval","params":{"session_id":"rt-a","command":"ls","choices":["once","deny"]}}]"""
        live.value = listOf(Row("a", "waiting"))
        val setup = setup()
        setup.watcher.chats.first { it["a"]?.requests?.isNotEmpty() == true }
        openRequests = "[]"
        live.value = listOf(Row("a", "working"))
        reconnect(setup)
        assertTrue(setup.watcher.chats.value.getValue("a").requests.isEmpty())
    }

    @Test
    fun aReplyAfterANewSocketStillReachesAChatWhoseTurnEnded() = runTest {
        live.value = listOf(Row("a", "working"))
        val setup = setup()
        setup.watcher.chats.first { "a" in it }
        // Attached, not only being attached.
        runCurrent()
        live.value = listOf(Row("a", "idle"))
        reconnect(setup)

        assertEquals(setOf("a"), setup.watcher.chats.value.keys)
        assertTrue(setup.watcher.reply("a", "And then?"))
        val submit = sockets.last().sent.value.single { it["method"]?.jsonPrimitive?.contentOrNull == "prompt.submit" }
        assertEquals("rt-a", submit["params"]!!.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun signingOutForgetsTheWatchedChats() = runTest {
        live.value = listOf(Row("a", "working"))
        val setup = setup()
        setup.watcher.chats.first { "a" in it }
        setup.connection.stop()
        setup.watcher.chats.first { it.isEmpty() }
    }

    @Test
    fun aChatWhoseStoredIdChangesKeepsItsOneAttach() = runTest {
        live.value = listOf(Row("a", "working"))
        val watcher = setup().watcher
        watcher.chats.first { "a" in it }
        // Compressing a chat goes on in a new stored session, on the same runtime.
        live.value = listOf(Row("a2", "working", runtime = "rt-a"))
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(setOf("a2"), watcher.chats.value.keys)
        assertEquals(1, sentMethods("session.activate").size)

        sockets.last().push(approval("srq-1", "rt-a"))
        watcher.chats.first { it["a2"]?.requests?.isNotEmpty() == true }
    }
}
