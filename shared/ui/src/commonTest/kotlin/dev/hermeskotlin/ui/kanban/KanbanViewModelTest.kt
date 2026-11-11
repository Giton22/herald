package dev.hermeskotlin.ui.kanban

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.kanban.KanbanAge
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.kanban.KanbanEvent
import dev.hermeskotlin.core.kanban.KanbanEvents
import dev.hermeskotlin.core.kanban.KanbanEventsFrame
import dev.hermeskotlin.core.kanban.KanbanTask
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.kanbanBoard
import dev.hermeskotlin.core.settings.withKanbanBoard
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure helpers (staleness, the dashboard's search predicate) — plus the ViewModel's orchestration. */
@OptIn(ExperimentalCoroutinesApi::class)
class KanbanViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val url = gateway.gatewayUrl
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun task(status: String, createdAge: Long? = null, startedAge: Long? = null, assignee: String? = "coder", tenant: String? = "herald", title: String = "T", body: String? = null) =
        KanbanTask(
            id = "t_1",
            title = title,
            status = status,
            assignee = assignee,
            tenant = tenant,
            body = body,
            age = if (createdAge == null && startedAge == null) null else KanbanAge(createdAge, startedAge, null),
        )

    @Test
    fun staleUsesCreatedAgeExceptWhileRunning() {
        val hour = 3600L
        // Ready: amber 1h, red 24h (dashboard STALENESS).
        assertEquals(KanbanStale.Amber, task("ready", createdAge = hour + 1).stale())
        assertEquals(KanbanStale.None, task("ready", createdAge = hour - 1).stale())
        assertEquals(KanbanStale.Red, task("ready", createdAge = 24 * hour).stale())
        // Running: amber 10m, red 60m, on the started clock.
        assertEquals(KanbanStale.Amber, task("running", startedAge = 10 * 60 + 1).stale())
        assertEquals(KanbanStale.Red, task("running", startedAge = 60 * 60).stale())
        assertEquals(KanbanStale.None, task("running", createdAge = 5 * hour, startedAge = 59).stale())
        // Blocked: amber 1h, red 24h. Todo: amber 7d, red 30d.
        assertEquals(KanbanStale.Amber, task("blocked", createdAge = hour + 1).stale())
        assertEquals(KanbanStale.None, task("todo", createdAge = 6 * 24 * hour).stale())
        assertEquals(KanbanStale.Amber, task("todo", createdAge = 7 * 24 * hour).stale())
        // Any other status never tints.
        assertEquals(KanbanStale.None, task("done", createdAge = 90 * 24 * hour).stale())
        assertEquals(KanbanStale.None, task("ready", createdAge = null).stale())
    }

    @Test
    fun matchesIsTheDashboardsPredicate() {
        val t = task("ready", title = "APIs: the schedule", body = "Off the wire", tenant = "herald", assignee = "coder").copy(
            result = "All green",
        )
        assertEquals(true, t.matches("schedule", null, null))
        assertEquals(true, t.matches("WIRE", null, null))
        assertEquals(true, t.matches("t_1", null, null))
        assertEquals(true, t.matches("", "herald", "coder"))
        assertEquals(true, t.matches("", null, "coder"))
        assertEquals(false, t.matches("", "other", null))
        assertEquals(false, t.matches("", null, "reviewer"))
        assertEquals(false, t.matches("nothinglikeit", null, null))
    }

    // ---------------------------------------------------------------------------------------------
    // Orchestration: fake events tail + MockEngine REST, asserting on real calls and state.
    // ---------------------------------------------------------------------------------------------

    private fun boardsJson(slugs: List<Pair<String, String>> = listOf("main" to "main"), current: String = "main") =
        """{"boards":[${slugs.joinToString(",") { """{"slug":"${it.first}","name":"${it.second}","total":1}""" }}],"current":"$current"}"""

    private fun boardJson() =
        """{"columns":[{"name":"ready","tasks":[{"id":"t_1","title":"A card","status":"ready"}]}],"tenants":[],"assignees":[],"latest_event_id":7,"now":1}"""

    private fun detailJson(id: String = "t_1") =
        """{"task":{"id":"$id","title":"A card","status":"ready"},"comments":[],"events":[],"attachments":[],"links":{"parents":[],"children":[]},"link_tasks":[],"child_results":[],"runs":[]}"""

    private fun logJson() = """{"task_id":"t_1","path":"","exists":true,"size_bytes":1,"content":"ok","truncated":false}"""

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        createHttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    // Answer on the test scheduler, not a real dispatcher: a real-thread callback can
                    // resume a coroutine onto Main after runTest resets it, which flakes the teardown.
                    dispatcher = this@KanbanViewModelTest.dispatcher
                    addHandler(handler)
                },
            ),
        )

    /** One channel per board; records observes, tail starts and collector cancellations. */
    private class FakeEvents : KanbanEvents {
        data class Call(val url: GatewayUrl, val board: String, val since: Long?)

        val calls = mutableListOf<Call>()
        val tailStarts = MutableStateFlow(0)
        val tailCollecting = MutableStateFlow(0)
        val framesFlowed = MutableStateFlow(0)
        val tailEnds = MutableStateFlow(0)
        val cancelledTails = mutableListOf<String>()
        private val channels = mutableMapOf<String, Channel<KanbanEventsFrame>>()

        override fun observe(url: GatewayUrl, board: String, since: Long?): Flow<KanbanEventsFrame> {
            calls += Call(url, board, since)
            tailStarts.value += 1
            val channel = Channel<KanbanEventsFrame>(Channel.UNLIMITED)
            channels[board] = channel
            return channel.receiveAsFlow()
                .onStart { tailCollecting.value += 1 }
                .onEach { framesFlowed.value += 1 }
                .onCompletion {
                    cancelledTails += board
                    tailEnds.value += 1
                }
        }

        suspend fun emit(board: String, taskId: String, cursor: Long) {
            channels.getValue(board).send(
                KanbanEventsFrame(listOf(KanbanEvent(taskId = taskId, kind = "commented", createdAt = 1)), cursor),
            )
        }
    }

    private fun viewModel(client: HttpClient, events: FakeEvents, store: SettingsStore? = null): KanbanViewModel =
        KanbanViewModel(KanbanApi(client), events, store ?: SettingsStore(InMemoryKeyValueStore(), CoroutineScope(dispatcher)))

    /** The common engine: boards + board + generic task/log, with counters and switches.
     *  MockEngine answers on the test scheduler; tests poll the counters until they land. */
    private inner class Engine {
        @Volatile var boardsCalls = 0
        @Volatile var boardGets = 0
        @Volatile var taskCalls = 0
        @Volatile var logCalls = 0
        var failBoards = false
        var failBoard = false
        var failTask = false
        var failTaskOnce = false
        var failLogOnce = false

        /** Suspends (pumping queued unconfined work and virtual time) until the counters reach the asks. */
        suspend fun waitForCalls(boards: Int? = null, board: Int? = null, task: Int? = null, log: Int? = null) {
            val deadline = System.currentTimeMillis() + 10_000
            fun met() = (boards == null || boardsCalls >= boards) && (board == null || boardGets >= board) && (task == null || taskCalls >= task) && (log == null || logCalls >= log)
            while (!met()) {
                check(System.currentTimeMillis() < deadline) { "timed out: boards=$boardsCalls/$boards board=$boardGets/$board task=$taskCalls/$task log=$logCalls/$log" }
                kotlinx.coroutines.yield()
                dispatcher.scheduler.advanceTimeBy(100)
            }
        }

        fun client(): HttpClient = client { request ->
            when {
                request.url.encodedPath.endsWith("/boards") -> {
                    boardsCalls++
                    if (failBoards) respond("""{"detail":"boom"}""", HttpStatusCode.InternalServerError, json)
                    else respond(boardsJson(), HttpStatusCode.OK, json)
                }
                request.url.encodedPath.endsWith("/board") -> {
                    boardGets++
                    if (failBoard) respond("""{"detail":"boom"}""", HttpStatusCode.InternalServerError, json)
                    else respond(boardJson(), HttpStatusCode.OK, json)
                }
                request.url.encodedPath.endsWith("/log") -> {
                    logCalls++
                    if (failLogOnce && logCalls == 1) respond("""{"detail":"boom"}""", HttpStatusCode.InternalServerError, json)
                    else respond(logJson(), HttpStatusCode.OK, json)
                }
                else -> {
                    taskCalls++
                    if (failTask || (failTaskOnce && taskCalls == 1)) respond("""{"detail":"gone"}""", HttpStatusCode.NotFound, json)
                    else respond(detailJson(), HttpStatusCode.OK, json)
                }
            }
        }
    }

    /**
     * A passively suspended `first {}` await does not drive the test scheduler, so a continuation
     * queued behind real IO may never resume. Pump queued unconfined work and virtual time until
     * [condition] holds instead.
     */
    private suspend fun TestScope.awaitPumped(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition never met" }
            kotlinx.coroutines.yield()
            testScheduler.advanceTimeBy(100)
        }
    }

    @Test
    fun stopCancelsTheTailAndReopenRefreshes() = runTest(dispatcher) {
        val engine = Engine()
        val events = FakeEvents()
        val vm = viewModel(engine.client(), events)
        vm.bind(gateway)
        vm.state.first { it.response != null }
        // Wait until the tail is actually being collected: tailStarts fires inside observe(),
        // before the returned flow has a collector, and cancelling before that would skip
        // onCompletion entirely.
        events.tailCollecting.first { it >= 1 }

        vm.stop()
        // Cancellation reaches the fake's onCompletion asynchronously; pump until it lands.
        awaitPumped { events.tailEnds.value >= 1 }
        assertTrue("main" in events.cancelledTails)
        assertEquals(1, engine.boardGets)

        // Back on the same gateway: the board refreshes and a fresh tail starts.
        vm.bind(gateway)
        engine.waitForCalls(board = 2)
        awaitPumped { events.tailCollecting.value >= 2 }
        assertEquals(2, engine.boardGets)
        vm.stop()
        awaitPumped { events.tailEnds.value >= 2 }
    }

    @Test
    fun gatewaySwitchCancelsTheOldTailAndDropsItsLateReplies() = runTest(dispatcher) {
        val gatewayA = SavedGateway("https://a.example.ts.net")
        val gatewayB = SavedGateway("https://b.example.ts.net")
        val aDetailArrived = CompletableDeferred<Unit>()
        val releaseADetail = CompletableDeferred<Unit>()
        val client = client { request ->
            when {
                request.url.host == "a.example.ts.net" && request.url.encodedPath.endsWith("/boards") ->
                    respond(boardsJson(slugs = listOf("mainA" to "mainA"), current = "mainA"), HttpStatusCode.OK, json)
                request.url.host == "a.example.ts.net" && request.url.encodedPath.endsWith("/board") ->
                    respond(boardJson(), HttpStatusCode.OK, json)
                request.url.host == "a.example.ts.net" -> {
                    aDetailArrived.complete(Unit)
                    releaseADetail.await()
                    respond(detailJson(), HttpStatusCode.OK, json)
                }
                request.url.encodedPath.endsWith("/boards") ->
                    respond(boardsJson(slugs = listOf("mainB" to "mainB"), current = "mainB"), HttpStatusCode.OK, json)
                else -> respond(boardJson(), HttpStatusCode.OK, json)
            }
        }
        val events = FakeEvents()
        val vm = viewModel(client, events)
        try {
            vm.bind(gatewayA)
            vm.state.first { it.response != null }
            events.tailStarts.first { it >= 1 }
            vm.openTask("t_1")
            aDetailArrived.await()

            // Switch gateways mid-detail: everything of A's must be cancelled, and A's late answer dropped.
            vm.bind(gatewayB)
            vm.state.first { it.board == "mainB" && it.response != null }
            // B's tail starts right after its board lands; only then is its observe call the latest.
            events.tailStarts.first { it >= 2 }
            releaseADetail.complete(Unit)
            kotlinx.coroutines.yield()

            assertNull(vm.state.value.openTaskId)
            assertNull(vm.state.value.detail)
            assertTrue("mainA" in events.cancelledTails)
            assertEquals("mainB", events.calls.last().board)
        } finally {
            releaseADetail.complete(Unit)
            vm.stop()
        }
    }

    @Test
    fun failedDetailRetriesTheDetailNeverTheLog() = runTest(dispatcher) {
        val engine = Engine()
        engine.failTaskOnce = true
        val events = FakeEvents()
        val vm = viewModel(engine.client(), events)
        vm.bind(gateway)
        vm.state.first { it.response != null }

        vm.openTask("t_1")
        vm.state.first { !it.detailLoading }
        engine.waitForCalls(task = 1)
        assertEquals("t_1", vm.state.value.openTaskId)
        assertNull(vm.state.value.detail)
        assertNotNull(vm.state.value.detailError)
        assertEquals(1, engine.taskCalls)
        assertEquals(0, engine.logCalls)

        // The drawer's Try again loads the task again — no log call is involved.
        vm.reloadDetail()
        vm.state.first { it.detail != null }
        engine.waitForCalls(task = 2, log = 1)
        assertEquals(2, engine.taskCalls)
        assertEquals(1, engine.logCalls)
        vm.stop()
    }

    @Test
    fun failedLogHasItsOwnRetry() = runTest(dispatcher) {
        val engine = Engine()
        engine.failLogOnce = true
        val events = FakeEvents()
        val vm = viewModel(engine.client(), events)
        vm.bind(gateway)
        vm.state.first { it.response != null }

        vm.openTask("t_1")
        vm.state.first { it.log != null || it.logError != null }
        engine.waitForCalls(log = 1)
        assertNull(vm.state.value.log)
        assertNotNull(vm.state.value.logError)
        assertEquals(1, engine.logCalls)

        vm.reloadLog()
        vm.state.first { it.log != null }
        engine.waitForCalls(log = 2)
        assertEquals(2, engine.logCalls)
        vm.stop()
    }

    @Test
    fun savedBoardPinWinsAndSelectingPersists() = runTest(dispatcher) {
        val store = SettingsStore(InMemoryKeyValueStore(), CoroutineScope(dispatcher))
        store.update { it.withKanbanBoard(url, "other") }
        val client = client { request ->
            when {
                request.url.encodedPath.endsWith("/boards") ->
                    respond(boardsJson(slugs = listOf("main" to "Main", "other" to "Other"), current = "main"), HttpStatusCode.OK, json)
                else -> respond(boardJson(), HttpStatusCode.OK, json)
            }
        }
        val events = FakeEvents()
        val vm = viewModel(client, events, store)
        vm.bind(gateway)
        vm.state.first { it.response != null }
        // The stored pin beats the gateway's current pointer, even on cold start.
        assertEquals("other", vm.state.value.board)

        vm.selectBoard("main")
        vm.state.first { it.response != null }
        assertEquals("main", store.settings.value?.kanbanBoard(url))
        vm.stop()
    }

    @Test
    fun aFrameForTheOpenTaskReloadsTheDrawer() = runTest(dispatcher) {
        val engine = Engine()
        val events = FakeEvents()
        val vm = viewModel(engine.client(), events)
        vm.bind(gateway)
        vm.state.first { it.response != null }
        events.tailStarts.first { it >= 1 }
        vm.openTask("t_1")
        vm.state.first { it.detail != null && it.log != null }
        engine.waitForCalls(task = 1, log = 1)
        assertEquals(1, engine.taskCalls)

        events.emit("main", "t_1", cursor = 9)
        events.framesFlowed.first { it >= 1 }
        advanceTimeBy(300)
        engine.waitForCalls(task = 2, board = 2)
        assertEquals(2, engine.taskCalls)
        assertEquals(2, engine.boardGets)
        vm.stop()
    }

    @Test
    fun quietReloadFailureKeepsTheSnapshotAndWarns() = runTest(dispatcher) {
        val engine = Engine()
        val events = FakeEvents()
        val vm = viewModel(engine.client(), events)
        vm.bind(gateway)
        vm.state.first { it.response != null }
        events.tailStarts.first { it >= 1 }
        assertEquals(false, vm.state.value.reloadError)

        engine.failBoard = true
        events.emit("main", "t_9", cursor = 9)
        events.framesFlowed.first { it >= 1 }
        advanceTimeBy(300)
        // The quiet reload fails on a real dispatcher; wait for the state to say so.
        vm.state.first { it.reloadError }
        // The last snapshot stays, and the page says it may be stale.
        assertNotNull(vm.state.value.response)
        assertEquals(true, vm.state.value.reloadError)
        assertNull(vm.state.value.error)
        vm.stop()
    }

    @Test
    fun boardsFailureOverASnapshotKeepsItAndWarns() = runTest(dispatcher) {
        val engine = Engine()
        val events = FakeEvents()
        val vm = viewModel(engine.client(), events)
        vm.bind(gateway)
        vm.state.first { it.response != null }
        assertEquals(false, vm.state.value.reloadError)

        // A /boards refresh that fails over a live snapshot warns like a quiet board reload:
        // the columns stay, flagged as possibly stale.
        try {
            engine.failBoards = true
            vm.refresh()
            vm.state.first { it.reloadError }
            assertEquals(2, engine.boardsCalls)
            assertNotNull(vm.state.value.response)
            assertNull(vm.state.value.error)
        } finally {
            vm.stop()
            // The cancelled tail's last dispatch lands on the test queue; this test's last
            // await resumed on a real IO thread, so drain it here while Dispatchers.Main is
            // still installed, or it leaks into the next test.
            kotlinx.coroutines.yield()
            runCurrent()
        }
    }

    @Test
    fun quietDetailReloadFailureKeepsTheDrawerAndWarns() = runTest(dispatcher) {
        val engine = Engine()
        val events = FakeEvents()
        val vm = viewModel(engine.client(), events)
        try {
            vm.bind(gateway)
            vm.state.first { it.response != null }
            events.tailStarts.first { it >= 1 }
            vm.openTask("t_1")
            vm.state.first { it.detail != null && it.log != null }
            assertNull(vm.state.value.detailError)

            // A live frame for the open task reloads it quietly; the failure must keep the stale
            // drawer and set the warning, never evict the detail that is still shown.
            engine.failTask = true
            val detailBefore = vm.state.value.detail
            events.emit("main", "t_1", cursor = 9)
            events.framesFlowed.first { it >= 1 }
            advanceTimeBy(300)
            vm.state.first { it.detailError != null }
            assertEquals(detailBefore, vm.state.value.detail)
            assertEquals(false, vm.state.value.detailLoading)
        } finally {
            vm.stop()
            // The cancelled tail's last dispatch lands on the test queue; this test's last
            // await resumed on a real IO thread, so drain it here while Dispatchers.Main is
            // still installed, or it leaks into the next test.
            kotlinx.coroutines.yield()
            runCurrent()
        }
    }

    /**
     * The gateway filters tenant server-side on /board; the mock does the same, so a setTenant that
     * only updated state (no reload) would leave the herald-only snapshot from the quiet reload in
     * place and picking waypoint would filter it to nothing. Regression: the chip change reloads.
     */
    @Test
    fun tenantChangeReloadsTheServerSnapshot() = runTest(dispatcher) {
        val requestedTenants = mutableListOf<String?>()
        val client = client { request ->
            when {
                request.url.encodedPath.endsWith("/boards") -> respond(boardsJson(), HttpStatusCode.OK, json)
                request.url.encodedPath.endsWith("/board") -> {
                    val tenant = request.url.parameters["tenant"]
                    requestedTenants += tenant
                    val task = if (tenant == "waypoint") {
                        """{"id":"t_wp","title":"Waypoint card","status":"ready","tenant":"waypoint"}"""
                    } else {
                        """{"id":"t_herald","title":"Herald card","status":"ready","tenant":"herald"}"""
                    }
                    respond(
                        """{"columns":[{"name":"ready","tasks":[$task]}],"tenants":["herald","waypoint"],"assignees":[],"latest_event_id":${requestedTenants.size},"now":1}""",
                        HttpStatusCode.OK, json,
                    )
                }
                else -> respond(detailJson(), HttpStatusCode.OK, json)
            }
        }
        val events = FakeEvents()
        val vm = viewModel(client, events)
        try {
            vm.bind(gateway)
            vm.state.first { it.response != null }
            events.tailStarts.first { it >= 1 }

            // Pick herald: the request must carry it (a fresh snapshot, not just the chip).
            vm.setTenant("herald")
            awaitPumped { vm.state.value.response?.latestEventId == 2L }
            assertEquals("herald", requestedTenants.last())

            // Any quiet reload (here a live frame) snapshots the herald-only server view.
            events.emit("main", "t_9", cursor = 9)
            events.framesFlowed.first { it >= 1 }
            awaitPumped { vm.state.value.response?.latestEventId == 3L }

            // Pick waypoint: the request and the chip agree again, and the waypoint card survives
            // the client-side filter as the second layer.
            vm.setTenant("waypoint")
            awaitPumped { vm.state.value.response?.latestEventId == 4L }
            assertEquals("waypoint", requestedTenants.last())
            assertEquals(listOf("t_wp"), vm.state.value.filteredColumns.flatMap { it.tasks }.map { it.id })

            // An unchanged value is a no-op: no extra request.
            vm.setTenant("waypoint")
            kotlinx.coroutines.yield()
            assertEquals(4, requestedTenants.size)
        } finally {
            vm.stop()
        }
    }

    @Test
    fun anEmptyBoardsListIsAReachableState() = runTest(dispatcher) {
        val client = client { respond("""{"boards":[],"current":"default"}""", HttpStatusCode.OK, json) }
        val events = FakeEvents()
        val vm = viewModel(client, events)
        vm.bind(gateway)
        vm.state.first { !it.boardsLoading }
        assertEquals(emptyList<dev.hermeskotlin.core.kanban.KanbanBoardMeta>(), vm.state.value.boards)
        assertNull(vm.state.value.board)
        assertNull(vm.state.value.boardsError)
        vm.stop()
    }

    @Test
    fun aDisappearingBoardDropsItsSnapshot() = runTest(dispatcher) {
        var boards = boardsJson(slugs = listOf("main" to "Main", "other" to "Other"), current = "main")
        val client = client { request ->
            when {
                request.url.encodedPath.endsWith("/boards") -> respond(boards, HttpStatusCode.OK, json)
                request.url.parameters["board"] == "main" ->
                    respond("""{"columns":[{"name":"ready","tasks":[]}],"tenants":[],"assignees":[],"latest_event_id":1,"now":1}""", HttpStatusCode.OK, json)
                else ->
                    respond("""{"columns":[{"name":"todo","tasks":[]}],"tenants":[],"assignees":[],"latest_event_id":2,"now":1}""", HttpStatusCode.OK, json)
            }
        }
        val events = FakeEvents()
        val vm = viewModel(client, events)
        vm.bind(gateway)
        vm.state.first { it.board == "main" && it.response != null }

        // "main" is gone on the next refresh; the selection falls back and the old columns must not linger.
        boards = boardsJson(slugs = listOf("other" to "Other"), current = "other")
        vm.refresh()
        vm.state.first { it.board == "other" && it.response != null }
        assertEquals(listOf("todo"), vm.state.value.response?.columns?.map { it.name })
        vm.stop()
    }
}
