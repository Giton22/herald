package dev.hermeskotlin.core.kanban

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.HandshakeRejectedException
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A scripted socket: [script] drives what the collector sees, then throws, ends, or hangs. */
private class FakeSocket(
    val script: suspend FakeSocket.(FlowCollector<Frame>) -> Unit,
) : KanbanEventsSocket {
    var closed = false
    private val reason = CompletableDeferred<CloseReason?>()

    fun finish(code: Short?) {
        if (!reason.isCompleted) reason.complete(code?.let { CloseReason(it, "end") })
    }

    override val incoming: Flow<Frame> = flow { script(this@FakeSocket, this) }
    override suspend fun closeReason(): CloseReason? = reason.await()
    override suspend fun close() {
        closed = true
        finish(null)
    }
}

/** A minting endpoint that counts requests and can fail the first few with a transport error. */
private class Mint(private val failFirst: Int) {
    var calls = 0

    fun client(): HttpClient = createHttpClient(MockEngine {
        calls++
        if (calls <= failFirst) throw Exception("offline")
        respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, jsonHeaders)
    })
}

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

@OptIn(ExperimentalCoroutinesApi::class)
class KanbanEventsTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = jsonHeaders

    @Test
    fun frameParsesEventsAndCursor() {
        val frame = parseEventsFrame(
            """{"events":[{"id":9,"task_id":"t_1","run_id":7,"kind":"claimed","payload":{"profile":"coder"},"created_at":1794010500}],"cursor":42}""",
        )!!
        assertEquals(42, frame.cursor)
        assertEquals(1, frame.events.size)
        assertEquals("claimed", frame.events.single().kind)
        assertEquals(7, frame.events.single().runId)
    }

    @Test
    fun garbageNeverBreaksTheTail() {
        assertNull(parseEventsFrame("not json"))
        assertNull(parseEventsFrame("""{"events":[],"cursor":0}"""))
        assertNull(parseEventsFrame("""{"events":[]}"""))
    }

    // ---------------------------------------------------------------------------------------------
    // The retry loop: backoff ladder, terminal refusals, cancellation. The socket opener and the
    // minting client are faked; `runTest` drives the backoff's virtual time.
    // ---------------------------------------------------------------------------------------------

    private fun events(client: HttpClient, open: suspend (String) -> KanbanEventsSocket): GatewayKanbanEvents =
        GatewayKanbanEvents(client, AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), open)

    private fun frame(cursor: Long) = Frame.Text("""{"events":[{"task_id":"t_1","kind":"commented","created_at":1}],"cursor":$cursor}""")

    private fun okhttpRefusal(status: Int) = Exception("Expected HTTP 101 response but was '$status'")

    /**
     * Drives virtual timers and real IO round-trips until [condition] holds. The MockEngine mint
     * round-trips on a real dispatcher, so `advanceUntilIdle` alone returns while a mint is still
     * in flight; alternating scheduler flushes with real sleeps lets both clocks make progress.
     */
    private fun kotlinx.coroutines.test.TestScope.driveUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for condition" }
            testScheduler.advanceUntilIdle()
            Thread.sleep(10)
        }
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun refusedHandshake403GivesUpAfterBoundedAttempts() = runTest {
        val mint = Mint(failFirst = 0)
        val opens = mutableListOf<Long>()
        val tail = events(mint.client()) {
            opens += testScheduler.currentTime
            throw okhttpRefusal(403)
        }
        // The flow completes (instead of looping) once the refusal budget is spent.
        assertNull(tail.observe(url, "main", null).firstOrNull())
        assertEquals(3, opens.size)
        assertEquals(3, mint.calls)
    }

    @Test
    fun structuredHandshakeRejectionCountsAsARefusal() = runTest {
        val mint = Mint(failFirst = 0)
        var opens = 0
        val tail = events(mint.client()) {
            opens++
            throw HandshakeRejectedException(403, Exception("denied"))
        }
        assertNull(tail.observe(url, "main", null).firstOrNull())
        assertEquals(3, opens)
    }

    @Test
    fun redirectHandshakeIsTerminalAtOnce() = runTest {
        val mint = Mint(failFirst = 0)
        var opens = 0
        val tail = events(mint.client()) {
            opens++
            throw okhttpRefusal(302)
        }
        assertNull(tail.observe(url, "main", null).firstOrNull())
        assertEquals(1, opens)
        assertEquals(1, mint.calls)
    }

    @Test
    fun missingEndpointHandshakeIsTerminalAtOnce() = runTest {
        val mint = Mint(failFirst = 0)
        var opens = 0
        val tail = events(mint.client()) {
            opens++
            throw okhttpRefusal(404)
        }
        assertNull(tail.observe(url, "main", null).firstOrNull())
        assertEquals(1, opens)
    }

    @Test
    fun policyClose1008IsTerminal() = runTest {
        val mint = Mint(failFirst = 0)
        var opens = 0
        val tail = events(mint.client()) {
            opens++
            FakeSocket { collector ->
                collector.emit(frame(5))
                finish(1008) // WS_1008_POLICY_VIOLATION
            }
        }
        // Drive the flow to its natural end (no first() abort): the frame lands, the 1008 close
        // ends the tail — one frame, one socket open, one mint, no reconnect.
        val frames = tail.observe(url, "main", null).toList()
        assertEquals(listOf(5L), frames.map { it.cursor })
        assertEquals(1, opens)
        assertEquals(1, mint.calls)
    }

    @Test
    fun dropsBackOffAndResetAfterAFrame() = runTest {
        val mint = Mint(failFirst = 0)
        val opens = mutableListOf<Long>()
        val sockets = mutableListOf<FakeSocket>()
        val tail = events(mint.client()) {
            val socket = FakeSocket { collector ->
                when (opens.size) {
                    // 2nd session: one frame then a drop (resets the ladder); other early sessions drop
                    // at once; the 4th hangs until the collector goes away.
                    2 -> {
                        collector.emit(frame(1))
                        throw Exception("drop")
                    }
                    4 -> kotlinx.coroutines.awaitCancellation()
                    else -> throw Exception("drop")
                }
            }
            sockets += socket
            opens += testScheduler.currentTime
            socket
        }
        val job = launch { tail.observe(url, "main", null).collect { } }
        driveUntil { sockets.size >= 4 }
        job.cancel()
        testScheduler.advanceUntilIdle()
        // Backoff ladder 1s/2s with a reset after the framed session: opens at 0, 1s, 2s, 4s.
        assertEquals(listOf(0L, 1_000L, 2_000L, 4_000L), opens)
        assertEquals(4, sockets.size)
        assertTrue(sockets.last().closed, "cancellation must close the hung socket")
    }

    @Test
    fun mintFailureRetriesUntilTheGatewayComesBack() = runTest {
        val mint = Mint(failFirst = 2)
        val opens = mutableListOf<Long>()
        val tail = events(mint.client()) {
            opens += testScheduler.currentTime
            FakeSocket { kotlinx.coroutines.awaitCancellation() }
        }
        val job = launch { tail.observe(url, "main", null).collect { } }
        driveUntil { mint.calls >= 3 && opens.isNotEmpty() }
        job.cancel()
        testScheduler.advanceUntilIdle()
        // Two failed mints (1 s, then 2 s backoff) and the socket opens on the third.
        assertEquals(listOf(3_000L), opens)
        assertEquals(3, mint.calls)
    }

    @Test
    fun aSignedOutMintEndsTheTail() = runTest {
        val client = createHttpClient(MockEngine { respond("""{"detail":"Unauthorized"}""", HttpStatusCode.Unauthorized, json) })
        var opens = 0
        val tail = events(client) {
            opens++
            FakeSocket { }
        }
        // SessionExpired is terminal: no point minting refusals every backoff step after sign-out.
        assertNull(tail.observe(url, "main", null).firstOrNull())
        assertEquals(0, opens)
    }
}
