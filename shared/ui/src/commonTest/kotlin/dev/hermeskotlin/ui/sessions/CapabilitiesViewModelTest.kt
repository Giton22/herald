package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.capabilities.CapabilitiesApi
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CapabilitiesViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aLateAnswerForThePreviousProfileIsDropped() = runTest(dispatcher) {
        val releaseOld = CompletableDeferred<Unit>()
        val oldAsked = CompletableDeferred<Unit>()
        val oldAnswered = CompletableDeferred<Unit>()
        val api = CapabilitiesApi(createHttpClient(MockEngine { request ->
            val profile = request.url.parameters["profile"]
            if (profile == "old") {
                oldAsked.complete(Unit)
                // Finishes either way: released, or cancelled with the binding.
                try { releaseOld.await() } finally { oldAnswered.complete(Unit) }
            }
            respond("""[{"name":"$profile-skill"}]""", HttpStatusCode.OK, json)
        }))
        val vm = CapabilitiesViewModel(api)

        vm.bind(gateway, "old")
        oldAsked.await()
        vm.bind(gateway, "new")
        vm.state.first { it.skills.items != null }
        releaseOld.complete(Unit)
        oldAnswered.await()
        // The mock engine answers on Ktor's own dispatcher; give a late answer real time to arrive.
        withContext(Dispatchers.Default) { delay(300) }

        assertEquals(listOf("new-skill"), vm.state.value.skills.items?.map { it.name })
    }
}
