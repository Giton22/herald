package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private var jobsBody = """[{"id":"a1","name":"Briefing","prompt":"Brief me","schedule":{"kind":"cron","expr":"0 9 * * *"}}]"""
    private val created = mutableListOf<String>()
    /** Holds a create until completed, so a test can act while the save is in flight. */
    private var createGate: CompletableDeferred<Unit>? = null
    private var createStatus = HttpStatusCode.OK
    private var createdQuery: String? = null
    private var createdBody: String? = null

    private fun viewModel(): ScheduledViewModel {
        // On the test dispatcher, so no request is still finishing on another thread when a test ends.
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/cron/jobs" -> {
                    createGate?.await()
                    created += request.url.encodedPath
                    createdQuery = request.url.encodedQuery
                    createdBody = request.body.toByteArray().decodeToString()
                    if (createStatus == HttpStatusCode.OK) {
                        respond("""{"id":"n1","name":"New","prompt":"Do it","schedule":{"kind":"cron","expr":"0 9 * * *"}}""", createStatus, json)
                    } else {
                        respond("""{"detail":"Gateway is busy"}""", createStatus, json)
                    }
                }
                request.url.encodedPath == "/api/cron/jobs" -> respond(jobsBody, HttpStatusCode.OK, json)
                request.url.encodedPath.endsWith("/runs") -> respond("""{"runs":[]}""", HttpStatusCode.OK, json)
                else -> respond("""{"targets":[]}""", HttpStatusCode.OK, json)
            }
        }
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine(config), cookies)
        val connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> error("not connecting in tests") }, CoroutineScope(dispatcher))
        return ScheduledViewModel(CronApi(client), connection)
    }

    private suspend fun ScheduledViewModel.awaitLoaded() = state.first { !it.loading }

    private fun ScheduledViewModel.fillNewJob() {
        newJob()
        prompt.setTextAndPlaceCursorAtEnd("Do it")
        schedule.setTextAndPlaceCursorAtEnd("0 9 * * *")
    }

    private val routinesBody = """[
        {"id":"r1","name":"[bot:scribe] Morning brief","prompt":"Brief me","profile":"scribe","schedule":{"kind":"cron","expr":"0 9 * * *"}},
        {"id":"r2","name":"[bot:scribe] Old style","prompt":"x","profile":"default","schedule":{"kind":"cron","expr":"0 9 * * *"}},
        {"id":"r3","name":"Somebody else's","prompt":"y","profile":"researcher","schedule":{"kind":"cron","expr":"0 9 * * *"}},
        {"id":"a1","name":"Briefing","prompt":"Brief me","profile":"default","schedule":{"kind":"cron","expr":"0 9 * * *"}}
    ]"""

    @Test
    fun aBotsPageListsOnlyItsRoutines() = runTest(dispatcher) {
        jobsBody = routinesBody
        val vm = viewModel()
        vm.bind(gateway, RoutineOwner("scribe", "Scribe"))

        // Its own store's, and one an older Desktop kept in the launch profile's store, tagged for it.
        assertEquals(listOf("r1", "r2"), vm.awaitLoaded().jobs.map { it.id })
        assertEquals("Morning brief", vm.state.value.jobs.first().displayName)
    }

    @Test
    fun aNewRoutineGoesInTheBotsStoreTaggedAndReportingToItsChat() = runTest(dispatcher) {
        jobsBody = routinesBody
        val vm = viewModel()
        vm.bind(gateway, RoutineOwner("scribe", "Scribe"))
        vm.awaitLoaded()
        vm.fillNewJob()
        assertEquals("bot-chat", vm.state.value.editor?.deliver)
        vm.name.setTextAndPlaceCursorAtEnd("Evening wrap")

        vm.saveJob()
        val saved = vm.state.first { it.editor == null }

        assertEquals("profile=scribe", createdQuery)
        assertTrue(createdBody!!.contains(""""name":"[bot:scribe] Evening wrap""""), createdBody)
        assertTrue(createdBody!!.contains(""""deliver":"bot-chat""""), createdBody)
        // The gateway's answer carries no profile; the page keeps it, so the routine stays listed.
        assertEquals("scribe", saved.jobs.first { it.id == "n1" }.profile)
    }

    @Test
    fun aRoutineNeedsAName() = runTest(dispatcher) {
        val vm = viewModel()
        vm.bind(gateway, RoutineOwner("scribe", "Scribe"))
        vm.awaitLoaded()
        vm.fillNewJob()

        vm.saveJob()

        assertEquals("Give the routine a name.", vm.state.value.editor?.error)
        assertTrue(created.isEmpty())
    }

    @Test
    fun editingARoutineShowsItsTitleAndKeepsTheTag() = runTest(dispatcher) {
        jobsBody = routinesBody
        val vm = viewModel()
        vm.bind(gateway, RoutineOwner("scribe", "Scribe"))
        vm.awaitLoaded()
        vm.openJob("r1")

        vm.editJob()

        assertEquals("Morning brief", vm.name.text.toString())
    }

    @Test
    fun savingAJobDeletedElsewhereDoesNotCreateItAgain() = runTest(dispatcher) {
        val vm = viewModel()
        vm.bind(gateway)
        vm.awaitLoaded()
        vm.openJob("a1")
        vm.editJob()

        // Another client removes it, and the cron.changed refetch drops it from the list.
        jobsBody = "[]"
        vm.refresh()
        vm.state.first { it.jobs.isEmpty() }

        vm.saveJob()

        assertTrue(created.isEmpty())
        assertTrue("deleted" in vm.state.value.editor?.error.orEmpty())
    }

    @Test
    fun aSaveThatFinishesAfterTheFormClosedLeavesThePageAlone() = runTest(dispatcher) {
        val vm = viewModel()
        vm.bind(gateway)
        vm.awaitLoaded()
        val gate = CompletableDeferred<Unit>().also { createGate = it }
        vm.fillNewJob()
        vm.saveJob()

        // Back out of the form and open another job while the create is still in flight.
        vm.closeEditor()
        vm.openJob("a1")
        gate.complete(Unit)

        val state = vm.state.first { it.message != null }
        assertEquals("a1", state.openJobId)
        assertNull(state.editor)
        assertEquals("Job scheduled", state.message)
        assertTrue(state.jobs.any { it.id == "n1" })
    }

    @Test
    fun aSaveThatFailsAfterTheFormClosedSaysSo() = runTest(dispatcher) {
        createStatus = HttpStatusCode.ServiceUnavailable
        val vm = viewModel()
        vm.bind(gateway)
        vm.awaitLoaded()
        val gate = CompletableDeferred<Unit>().also { createGate = it }
        vm.fillNewJob()
        vm.saveJob()

        vm.closeEditor()
        gate.complete(Unit)

        val message = vm.state.first { it.message != null }.message!!
        assertTrue(message.startsWith("Couldn't save the job"), message)
        assertTrue("Gateway is busy" in message, message)
    }
}
