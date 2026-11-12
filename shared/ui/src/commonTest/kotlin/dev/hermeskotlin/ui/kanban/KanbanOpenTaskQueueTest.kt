package dev.hermeskotlin.ui.kanban

import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.kanban.KanbanEvents
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * T13 (`docs/WIDGETS-SPEC.md` §11): the research risk-5 case — a task link that arrives before its
 * board loads must still open the drawer once the load lands; a task the board no longer has clears
 * silently; without a link nothing opens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KanbanOpenTaskQueueTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private inner class Engine {
        /** Completed by the test to let the board read answer. */
        val boardGate = CompletableDeferred<Unit>()
        val boards = mutableListOf<String>()
        val boardReads = mutableListOf<String>()

        fun client(dispatcher: kotlinx.coroutines.CoroutineDispatcher): HttpClient {
            val config = MockEngineConfig()
            config.dispatcher = dispatcher
            config.addHandler { request ->
                when {
                    request.url.encodedPath.endsWith("/boards") -> {
                        boards += request.url.encodedPath
                        respond(
                            """{"boards":[{"slug":"main","name":"Main","total":1},{"slug":"other","name":"Other","total":1}],"current":"main"}""",
                            HttpStatusCode.OK, json,
                        )
                    }
                    request.url.encodedPath.endsWith("/board") -> {
                        boardReads += request.url.parameters["board"].orEmpty()
                        boardGate.await()
                        val task = if (request.url.parameters["board"] == "other") {
                            """{"id":"t_9","title":"Other card","status":"ready"}"""
                        } else {
                            """{"id":"t_1","title":"A card","status":"ready"}"""
                        }
                        respond("""{"columns":[{"name":"ready","tasks":[$task]}],"tenants":[],"assignees":[],"latest_event_id":7,"now":1}""", HttpStatusCode.OK, json)
                    }
                    request.url.encodedPath.contains("/tasks/") -> respond(
                        """{"task":{"id":"t_1","title":"A card","status":"ready"},"comments":[],"events":[],"attachments":[],"links":{"parents":[],"children":[]},"link_tasks":[],"child_results":[],"runs":[]}""",
                        HttpStatusCode.OK, json,
                    )
                    else -> respond("""{}""", HttpStatusCode.OK, json)
                }
            }
            return createHttpClient(MockEngine(config))
        }
    }

    private fun viewModel(engine: Engine): KanbanViewModel = KanbanViewModel(
        KanbanApi(engine.client(dispatcher)),
        KanbanEvents { _, _, _ -> emptyFlow() },
        SettingsStore(InMemoryKeyValueStore(), CoroutineScope(dispatcher)),
    )

    @Test
    fun aTaskLinkQueuedBeforeTheBoardLoadsOpensTheDrawerAfterIt() = runTest(dispatcher) {
        val engine = Engine()
        val vm = viewModel(engine)
        vm.bind(gateway)
        // The board read is still in flight (the gate holds it); the link arrives now.
        vm.queueOpenTask("main", "t_1")
        engine.boardGate.complete(Unit)

        val state = vm.state.first { it.openTaskId != null }
        assertEquals("t_1", state.openTaskId)
        assertEquals("main", state.board)
        assertNull(vm.pendingOpenTask.value)
    }

    @Test
    fun aTaskLinkNamingAnotherBoardSelectsItFirst() = runTest(dispatcher) {
        val engine = Engine()
        val vm = viewModel(engine)
        vm.bind(gateway)
        vm.queueOpenTask("other", "t_9")
        engine.boardGate.complete(Unit)

        val state = vm.state.first { it.openTaskId != null }
        assertEquals("other", state.board)
        assertEquals("t_9", state.openTaskId)
    }

    @Test
    fun aTaskTheBoardNoLongerHasClearsSilently() = runTest(dispatcher) {
        val engine = Engine()
        val vm = viewModel(engine)
        vm.bind(gateway)
        vm.queueOpenTask("main", "t_gone")
        engine.boardGate.complete(Unit)

        // The queue is consumed without the drawer opening: no error row for a deleted task.
        vm.pendingOpenTask.first { it == null }
        assertNull(vm.state.value.openTaskId)
        assertNull(vm.state.value.error)
    }

    @Test
    fun withoutALinkNothingOpens() = runTest(dispatcher) {
        val engine = Engine()
        val vm = viewModel(engine)
        vm.bind(gateway)
        engine.boardGate.complete(Unit)

        val state = vm.state.first { it.response != null }
        assertNull(state.openTaskId)
        assertNull(vm.pendingOpenTask.value)
    }
}
