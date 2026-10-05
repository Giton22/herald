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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class AttentionTrackerTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client() = createHttpClient(
        MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                else -> respond("""{"messages":[]}""", HttpStatusCode.OK, json)
            }
        },
    )

    /** The rows `session.active_list` answers with, as `stored id to status`. */
    private var live: Map<String, String> = emptyMap()

    /** Resumes `stored-N` as runtime `rtN` and lists [live]; everything else answers `{}`. */
    private fun CoroutineScope.serve(transport: FakeTransport) = launch {
        var answered = 0
        transport.sent.collect { sent ->
            sent.drop(answered).forEach { message ->
                answered++
                val id = message["id"] ?: return@forEach
                val method = message["method"]?.jsonPrimitive?.contentOrNull
                val stored = message["params"]?.jsonObject?.get("session_id")?.jsonPrimitive?.contentOrNull.orEmpty()
                val result = when (method) {
                    "session.resume" -> """{"session_id":"rt${stored.removePrefix("stored-")}","running":true}"""
                    "session.active_list" -> live.entries.joinToString(",", """{"sessions":[""", "]}") { (key, status) ->
                        """{"id":"live-$key","session_key":"$key","status":"$status"}"""
                    }
                    else -> "{}"
                }
                transport.push("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
            }
        }
    }

    private suspend fun TestScope.setup(scope: CoroutineScope): Triple<ChatHost, AttentionTracker, FakeTransport> {
        val http = client()
        val transport = FakeTransport()
        val connection = GatewayConnection(AuthApi(http, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> transport }, scope)
        scope.serve(transport)
        connection.start(url)
        // No heartbeat: moving the test clock must not trip it.
        transport.push("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        connection.state.first { it is ConnectionState.Connected }
        val host = ChatHost(connection, SessionsApi(http), scope)
        val clock = { testScheduler.currentTime + 1 }
        val active = ActiveSessions(connection, scope, clock = clock)
        return Triple(host, AttentionTracker(connection, host, active, scope, clock), transport)
    }

    private fun approval(id: String, runtimeId: String) =
        """{"jsonrpc":"2.0","id":"$id","method":"approval","params":{"session_id":"$runtimeId","command":"rm -rf build","choices":["once","deny"]}}"""

    private fun event(type: String, runtimeId: String, payload: String = "{}") =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"$runtimeId","payload":$payload}}"""

    @Test
    fun aChatLeftWaitingStaysMarkedAfterOpeningAnother() = runTest {
        val (host, tracker, transport) = setup(backgroundScope)
        host.open(url, "stored-1", null).state.first { it.runtimeSessionId == "rt1" }

        transport.push(approval("srq-1", "rt1"))
        assertEquals(mapOf("stored-1" to Waiting.Approval), tracker.waiting.first { it.isNotEmpty() })

        host.open(url, "stored-2", null).state.first { it.runtimeSessionId == "rt2" }
        assertEquals(mapOf("stored-1" to Waiting.Approval), tracker.waiting.first { "stored-1" in it })
    }

    @Test
    fun aWithdrawnRequestOrAFinishedTurnClearsIt() = runTest {
        val (host, tracker, transport) = setup(backgroundScope)
        host.open(url, "stored-1", null).state.first { it.runtimeSessionId == "rt1" }
        host.open(url, "stored-2", null).state.first { it.runtimeSessionId == "rt2" }

        transport.push(approval("srq-1", "rt1"))
        tracker.waiting.first { "stored-1" in it }
        transport.push(event("request.cancel", "rt1", """{"id":"srq-1"}"""))
        tracker.waiting.first { it.isEmpty() }

        transport.push(approval("srq-2", "rt1"))
        tracker.waiting.first { "stored-1" in it }
        transport.push(event("message.complete", "rt1", """{"text":"done","status":"complete"}"""))
        tracker.waiting.first { it.isEmpty() }
    }

    @Test
    fun aChatStopsRunningWhenItsTurnEndsEvenAfterLeavingIt() = runTest {
        val (host, tracker, transport) = setup(backgroundScope)
        // The resume says a turn is running (see serve).
        host.open(url, "stored-1", null).state.first { it.runtimeSessionId == "rt1" }
        tracker.running.first { it["stored-1"] == true }

        host.open(url, "stored-2", null).state.first { it.runtimeSessionId == "rt2" }
        assertEquals(true, tracker.running.value["stored-1"])

        transport.push(event("message.complete", "rt1", """{"text":"done","status":"complete"}"""))
        tracker.running.first { it["stored-1"] == false }

        transport.push(event("message.start", "rt1"))
        tracker.running.first { it["stored-1"] == true }
    }

    @Test
    fun aTurnOnlyAnotherClientStartedShowsRunning() = runTest {
        live = mapOf("stored-9" to "working", "stored-8" to "idle")
        val (_, tracker, _) = setup(backgroundScope)
        assertEquals(mapOf("stored-9" to true, "stored-8" to false), tracker.running.first { it.isNotEmpty() })
    }

    @Test
    fun aChatWhoseAgentIsStillBuildingIsNotRunning() = runTest {
        // A cold `session.resume` (a chat opened on Desktop, a bot chat watched) builds the agent at once: no turn.
        live = mapOf("stored-9" to "starting", "stored-8" to "waiting")
        val (_, tracker, _) = setup(backgroundScope)
        assertEquals(mapOf("stored-9" to false, "stored-8" to true), tracker.running.first { it.isNotEmpty() })
    }

    @Test
    fun aWaitingChatWhoseRequestThisPhoneNeverSawIsUnknown() = runTest {
        live = mapOf("stored-9" to "waiting")
        val (_, tracker, _) = setup(backgroundScope)
        assertEquals(mapOf("stored-9" to Waiting.Unknown), tracker.waiting.first { it.isNotEmpty() })
    }

    @Test
    fun aRequestThisPhoneSawKeepsItsKind() = runTest {
        val (host, tracker, transport) = setup(backgroundScope)
        host.open(url, "stored-1", null).state.first { it.runtimeSessionId == "rt1" }
        host.open(url, "stored-2", null).state.first { it.runtimeSessionId == "rt2" }
        live = mapOf("stored-1" to "waiting")
        transport.push(approval("srq-1", "rt1"))
        val collector = backgroundScope.launch { tracker.waiting.collect {} }
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(Waiting.Approval, tracker.waiting.value["stored-1"])
        collector.cancel()
    }

    @Test
    fun theNewestRequestSaysWhatAChatWaitsFor() = runTest {
        val (host, tracker, transport) = setup(backgroundScope)
        host.open(url, "stored-1", null).state.first { it.runtimeSessionId == "rt1" }
        host.open(url, "stored-2", null).state.first { it.runtimeSessionId == "rt2" }
        live = mapOf("stored-1" to "waiting")
        val collector = backgroundScope.launch { tracker.waiting.collect {} }
        // Desktop answers the approval (no word of that reaches this phone); the agent then asks for a password.
        transport.push(approval("srq-1", "rt1"))
        runCurrent()
        transport.push("""{"jsonrpc":"2.0","id":"srq-2","method":"sudo","params":{"session_id":"rt1","command":"apt update"}}""")
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(Waiting.Input, tracker.waiting.value["stored-1"])
        collector.cancel()
    }

    @Test
    fun aNewerAnswerBeatsATurnEndThisPhoneMissed() = runTest {
        val (host, tracker, _) = setup(backgroundScope)
        // The resume says a turn is running (see serve); the phone then leaves the chat.
        host.open(url, "stored-1", null).state.first { it.runtimeSessionId == "rt1" }
        host.open(url, "stored-2", null).state.first { it.runtimeSessionId == "rt2" }
        val collector = backgroundScope.launch { tracker.running.collect {} }
        runCurrent()
        assertEquals(true, tracker.running.value["stored-1"])

        // Its `message.complete` never came (the socket was elsewhere); the gateway now lists it idle.
        live = mapOf("stored-1" to "idle")
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(false, tracker.running.value["stored-1"])
        collector.cancel()
    }
}
