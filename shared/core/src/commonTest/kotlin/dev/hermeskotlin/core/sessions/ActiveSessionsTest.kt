package dev.hermeskotlin.core.sessions

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.FakeTransport
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
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActiveSessionsTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** `gateway.ready` without the heartbeat, so moving the test clock never trips it. */
    private val ready = """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}"""

    private class Gateway(val transport: FakeTransport, val connection: GatewayConnection) {
        /** What `session.active_list` answers next: a result object, or an `"error":{…}` member. */
        var answer: String = """{"sessions":[]}"""
        var asked = 0
    }

    private fun row(key: String, status: String) =
        """{"id":"rt-$key","session_key":"$key","status":"$status","title":"","preview":"","current":false}"""

    private suspend fun connect(scope: CoroutineScope): Gateway {
        val http = createHttpClient(MockEngine { respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json) })
        val transport = FakeTransport()
        val connection = GatewayConnection(AuthApi(http, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> transport }, scope)
        val gateway = Gateway(transport, connection)
        scope.launch {
            var answered = 0
            transport.sent.collect { sent ->
                sent.drop(answered).forEach { message ->
                    answered++
                    val id = message["id"] ?: return@forEach
                    val method = message["method"]?.jsonPrimitive?.contentOrNull
                    val body = if (method == "session.active_list") {
                        gateway.asked++
                        gateway.answer.takeIf { it.startsWith(""""error"""") } ?: """"result":${gateway.answer}"""
                    } else {
                        """"result":{}"""
                    }
                    transport.push("""{"jsonrpc":"2.0","id":$id,$body}""")
                }
            }
        }
        connection.start(url)
        transport.push(ready)
        connection.state.first { it is ConnectionState.Connected }
        return gateway
    }

    private fun TestScope.active(gateway: Gateway) =
        ActiveSessions(gateway.connection, backgroundScope, pollMs = 10_000, settleMs = 500, clock = { testScheduler.currentTime + 1 })

    private fun event(type: String) = """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","payload":{}}}"""

    @Test
    fun eachStatusMapsByStoredId() = runTest {
        val gateway = connect(backgroundScope)
        gateway.answer = """{"sessions":[${row("a", "working")},${row("b", "waiting")},${row("c", "starting")},${row("d", "idle")},${row("e", "streaming")},${row("f", "resuming")},${row("g", "sleeping")}]}"""
        val live = active(gateway).live.first { it.statuses.isNotEmpty() }
        assertEquals(
            mapOf(
                "a" to LiveStatus.Working, "b" to LiveStatus.Waiting, "c" to LiveStatus.Starting, "d" to LiveStatus.Idle,
                "e" to LiveStatus.Working, "f" to LiveStatus.Working, "g" to LiveStatus.Idle,
            ),
            live.statuses,
        )
    }

    @Test
    fun itAsksAgainOnEachIntervalAndStopsWhenNothingCollects() = runTest {
        val gateway = connect(backgroundScope)
        gateway.answer = """{"sessions":[${row("a", "working")}]}"""
        val active = active(gateway)
        val collector = backgroundScope.launch { active.live.collect {} }
        active.live.first { it.statuses.isNotEmpty() }
        val first = gateway.asked

        gateway.answer = """{"sessions":[${row("a", "idle")}]}"""
        advanceTimeBy(10_001)
        active.live.first { it.statuses["a"] == LiveStatus.Idle }
        assertTrue(gateway.asked > first)

        collector.cancel()
        advanceTimeBy(6_000)
        val stopped = gateway.asked
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(stopped, gateway.asked)
    }

    @Test
    fun aTurnEventAsksAgainOnceEventsSettle() = runTest {
        val gateway = connect(backgroundScope)
        val active = active(gateway)
        backgroundScope.launch { active.live.collect {} }
        active.live.first { it.askedAtMillis > 0 }
        runCurrent()
        val before = gateway.asked

        gateway.answer = """{"sessions":[${row("a", "working")}]}"""
        gateway.transport.push(event("message.start"))
        gateway.transport.push(event("message.complete"))
        advanceTimeBy(600)
        active.live.first { it.statuses["a"] == LiveStatus.Working }
        assertEquals(before + 1, gateway.asked)
    }

    @Test
    fun comingBackInSightAsksAtOnceInsteadOfAtTheEndOfTheSlowWait() = runTest {
        val gateway = connect(backgroundScope)
        val active = active(gateway)
        active.inBackground = true
        backgroundScope.launch { active.live.collect {} }
        active.live.first { it.askedAtMillis > 0 }
        runCurrent()
        val before = gateway.asked

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(before, gateway.asked)
        // Something always collects now (the watcher), so nothing restarts the poll on its own.
        active.inBackground = false
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(before + 1, gateway.asked)
    }

    @Test
    fun anOldGatewayKeepsTheLastAnswerAndIsNotAskedAgain() = runTest {
        val gateway = connect(backgroundScope)
        gateway.answer = """{"sessions":[${row("a", "working")}]}"""
        val active = active(gateway)
        backgroundScope.launch { active.live.collect {} }
        active.live.first { it.statuses.isNotEmpty() }

        gateway.answer = """"error":{"code":-32601,"message":"Method not found"}"""
        advanceTimeBy(10_001)
        runCurrent()
        val asked = gateway.asked
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(asked, gateway.asked)
        assertEquals(mapOf("a" to LiveStatus.Working), active.live.value.statuses)
    }

    @Test
    fun aDroppedConnectionShowsNothingAsLive() = runTest {
        val gateway = connect(backgroundScope)
        gateway.answer = """{"sessions":[${row("a", "working")}]}"""
        val active = active(gateway)
        backgroundScope.launch { active.live.collect {} }
        active.live.first { it.statuses.isNotEmpty() }

        gateway.transport.serverClose(1006, "gone")
        assertEquals(LiveSessions(), active.live.first { it.statuses.isEmpty() })
    }
}
