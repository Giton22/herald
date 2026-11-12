package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.cron.CronApi
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T14 (`docs/WIDGETS-SPEC.md` §11): a pending job link is applied after bind's first refresh —
 * openJobId set, runs loading — a bind reset doesn't drop it, and an id the list doesn't have
 * clears silently.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledOpenJobQueueTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val jobsBody = """[{"id":"a1","name":"Briefing","prompt":"Brief me"},{"id":"b2","name":"Triage","prompt":"Triage it"}]"""

    /** The runs read hangs until [runsGate] completes, so the loading state is observable. */
    private fun viewModel(runsGate: CompletableDeferred<Unit>? = null): ScheduledViewModel {
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { request ->
            when {
                request.url.encodedPath == "/api/cron/jobs" -> respond(jobsBody, HttpStatusCode.OK, json)
                request.url.encodedPath.endsWith("/runs") -> {
                    runsGate?.await()
                    respond("""{"runs":[]}""", HttpStatusCode.OK, json)
                }
                else -> respond("""{"targets":[]}""", HttpStatusCode.OK, json)
            }
        }
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine(config), cookies)
        val connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> error("not connecting in tests") }, CoroutineScope(dispatcher))
        return ScheduledViewModel(CronApi(client), connection)
    }

    @Test
    fun aPendingJobAppliedAfterTheFirstRefreshOpensTheRuns() = runTest(dispatcher) {
        val runsGate = CompletableDeferred<Unit>()
        val vm = viewModel(runsGate)
        vm.bind(gateway, null)

        assertTrue(vm.applyPendingOpenJob("a1"))
        assertEquals("a1", vm.state.value.openJobId)
        // The runs read is in flight: the page shows its loading state until it answers.
        assertTrue(vm.state.value.runsLoading)
        runsGate.complete(Unit)
        vm.state.first { !it.runsLoading }
        assertEquals(emptyList<dev.hermeskotlin.core.sessions.SessionSummary>(), vm.state.value.runs)
    }

    @Test
    fun aBindResetDoesNotDropAPendingJob() = runTest(dispatcher) {
        val vm = viewModel()
        // The link lands before bind: the pending application waits out the refresh and bind's reset.
        val applied = async(dispatcher) { vm.applyPendingOpenJob("b2") }
        runCurrent()
        vm.bind(gateway, null)
        assertTrue(applied.await())
        assertEquals("b2", vm.state.value.openJobId)
    }

    @Test
    fun aJobTheListDoesNotHaveClearsSilently() = runTest(dispatcher) {
        val vm = viewModel()
        vm.bind(gateway, null)

        assertFalse(vm.applyPendingOpenJob("gone"))
        assertNull(vm.state.value.openJobId)
        assertNull(vm.state.value.error)
    }
}
