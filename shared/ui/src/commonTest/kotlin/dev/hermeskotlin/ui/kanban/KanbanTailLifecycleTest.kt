package dev.hermeskotlin.ui.kanban

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.kanban.KanbanEvent
import dev.hermeskotlin.core.kanban.KanbanEvents
import dev.hermeskotlin.core.kanban.KanbanEventsFrame
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The events tail and refresh lifecycle. These tests intentionally use only the pre-existing
 * [KanbanViewModel] API so they can run against the old behaviour: each one fails there
 * (the tail is torn down per reload, or `refresh` issues a second board GET).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KanbanTailLifecycleTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun boardsJson() =
        """{"boards":[{"slug":"main","name":"Main","total":1},{"slug":"other","name":"Other","total":0}],"current":"main"}"""

    private fun boardJson(latestEventId: Int = 7) =
        """{"columns":[{"name":"ready","tasks":[{"id":"t_1","title":"A card","status":"ready"}]}],"tenants":[],"assignees":[],"latest_event_id":$latestEventId,"now":1}"""

    /** A scripted engine: board GETs counted, the rest answered generically. MockEngine answers on the
     *  test scheduler; tests pump the unconfined queue until the counters land. */
    private inner class Engine {
        @Volatile var boardGets = 0
        @Volatile var otherGets = 0
        var holdFirstOtherGet: CompletableDeferred<Unit>? = null
        val firstOtherGetArrived = CompletableDeferred<Unit>()

        /** Suspends (pumping queued unconfined work and virtual time) until [boardGets] reaches [n]. */
        suspend fun waitForBoardGets(n: Int) {
            val deadline = System.currentTimeMillis() + 10_000
            while (boardGets < n) {
                check(System.currentTimeMillis() < deadline) { "timed out waiting for $n board GETs (got $boardGets)" }
                kotlinx.coroutines.yield()
                dispatcher.scheduler.advanceTimeBy(100)
            }
        }

        suspend fun waitForOtherGets(n: Int) {
            val deadline = System.currentTimeMillis() + 10_000
            while (otherGets < n) {
                check(System.currentTimeMillis() < deadline) { "timed out waiting for $n 'other' board GETs (got $otherGets)" }
                kotlinx.coroutines.yield()
                dispatcher.scheduler.advanceTimeBy(100)
            }
        }

        fun client(): HttpClient = createHttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    // Answer on the test scheduler, not a real dispatcher: a real-thread callback can
                    // resume a coroutine onto Main after runTest resets it, which flakes the teardown.
                    dispatcher = this@KanbanTailLifecycleTest.dispatcher
                    addHandler { request ->
                        when {
                            request.url.encodedPath.endsWith("/boards") -> respond(boardsJson(), HttpStatusCode.OK, json)
                            request.url.encodedPath.endsWith("/board") && request.url.parameters["board"] == "other" -> {
                                boardGets++
                                otherGets++
                                if (otherGets == 1) {
                                    firstOtherGetArrived.complete(Unit)
                                    holdFirstOtherGet?.await()
                                }
                                respond(boardJson(), HttpStatusCode.OK, json)
                            }
                            request.url.encodedPath.endsWith("/board") -> {
                                boardGets++
                                // A fresh `latest_event_id` per GET lets tests await a reload's full completion
                                // (response applied to state), not just the request leaving the client.
                                respond(boardJson(boardGets), HttpStatusCode.OK, json)
                            }
                            request.url.encodedPath.endsWith("/log") -> respond(
                                """{"task_id":"t_1","path":"","exists":false,"size_bytes":0,"content":"","truncated":false}""",
                                HttpStatusCode.OK, json,
                            )
                            else -> respond(
                                """{"task":{"id":"t_1","title":"A card","status":"ready"},"comments":[],"events":[],"attachments":[],"links":{"parents":[],"children":[]},"link_tasks":[],"child_results":[],"runs":[]}""",
                                HttpStatusCode.OK, json,
                            )
                        }
                    }
                },
            ),
        )
    }

    /** One channel per board; records every observe, each frame that reaches the tail, and collector cancellations. */
    private class FakeEvents : KanbanEvents {
        data class Call(val url: GatewayUrl, val board: String, val since: Long?)

        val calls = mutableListOf<Call>()
        val tailStarts = MutableStateFlow(0)
        val framesFlowed = MutableStateFlow(0)
        val cancelledTails = mutableListOf<String>()
        private val channels = mutableMapOf<String, Channel<KanbanEventsFrame>>()

        override fun observe(url: GatewayUrl, board: String, since: Long?): Flow<KanbanEventsFrame> {
            calls += Call(url, board, since)
            tailStarts.value += 1
            val channel = Channel<KanbanEventsFrame>(Channel.UNLIMITED)
            channels[board] = channel
            return channel.receiveAsFlow()
                .onEach { framesFlowed.value += 1 }
                .onCompletion { cancelledTails += board }
        }

        suspend fun emit(board: String, taskId: String, cursor: Long) {
            channels.getValue(board).send(
                KanbanEventsFrame(listOf(KanbanEvent(taskId = taskId, kind = "commented", createdAt = 1)), cursor),
            )
        }
    }

    private fun viewModel(engine: Engine, events: FakeEvents): KanbanViewModel {
        val store = SettingsStore(InMemoryKeyValueStore(), CoroutineScope(dispatcher))
        return KanbanViewModel(KanbanApi(engine.client()), events, store)
    }

    /**
     * A passively suspended `first {}` await does not drive the test scheduler, so a continuation
     * queued behind real IO may never resume. Pump queued unconfined work and virtual time until
     * [condition] holds instead.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.awaitPumped(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition never met" }
            kotlinx.coroutines.yield()
            dispatcher.scheduler.advanceTimeBy(100)
        }
    }

    @Test
    fun twoEventDrivenReloadsKeepOneTail() = runTest(dispatcher) {
        val engine = Engine()
        val events = FakeEvents()
        val vm = viewModel(engine, events)
        try {
            vm.bind(gateway)
            vm.state.first { it.response != null }
            // The tail starts right after the board lands; await it instead of assuming call order.
            events.tailStarts.first { it >= 1 }
            assertEquals(1, engine.boardGets)
            assertEquals(1, vm.state.value.response?.latestEventId)

            // Each frame is debounced, then drives one quiet reload. Await each reload's full
            // completion (a distinct latest_event_id reaches state, and startTail has settled)
            // before emitting the next frame, so the two can never coalesce into one debounce
            // window and a socket restart would show up in the observe calls immediately.
            events.emit("main", "t_9", cursor = 8)
            events.framesFlowed.first { it >= 1 }
            awaitPumped { vm.state.value.response?.latestEventId == 2L }
            assertEquals(1, events.calls.size)
            events.emit("main", "t_10", cursor = 9)
            events.framesFlowed.first { it >= 2 }
            awaitPumped { vm.state.value.response?.latestEventId == 3L }
            assertEquals(3, engine.boardGets)
            // ...on the SAME tail: quiet reloads must not re-open the socket.
            assertEquals(1, events.calls.size)
        } finally {
            vm.stop()
        }
    }

    @Test
    fun refreshWhileBoardIsLoadingIssuesOneBoardGet() = runTest(dispatcher) {
        val engine = Engine()
        engine.holdFirstOtherGet = CompletableDeferred()
        val events = FakeEvents()
        val vm = viewModel(engine, events)
        try {
            vm.bind(gateway)
            vm.state.first { it.response != null }
            vm.selectBoard("other")
            assertNull(vm.state.value.response)
            // Wait for the in-flight load of "other" before refreshing mid-load.
            engine.firstOtherGetArrived.await()
            assertEquals(1, engine.otherGets)

            vm.refresh()
            engine.holdFirstOtherGet!!.complete(Unit)
            // One refresh is exactly one board GET, even mid-load: the hanging first load was cancelled.
            engine.waitForOtherGets(2)
            assertEquals(2, engine.otherGets)
        } finally {
            engine.holdFirstOtherGet?.complete(Unit)
            vm.stop()
        }
    }
}
