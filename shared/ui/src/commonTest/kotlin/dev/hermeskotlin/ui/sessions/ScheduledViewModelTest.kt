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

    private fun viewModel(): ScheduledViewModel {
        // On the test dispatcher, so no request is still finishing on another thread when a test ends.
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/cron/jobs" -> {
                    createGate?.await()
                    created += request.url.encodedPath
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
}
