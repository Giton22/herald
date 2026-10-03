package dev.hermeskotlin.ui.connect

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.hermeskotlin.core.gateway.GatewayProbe
import dev.hermeskotlin.core.gateway.ProbeResult
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): ConnectViewModel {
        val engine = MockEngine {
            respond(
                """{"version":"0.42.0","auth_required":true,"auth_providers":["basic"]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return ConnectViewModel(GatewayProbe(createHttpClient(engine)))
    }

    /** The mock engine completes on Ktor's own dispatcher, so wait for the state instead of the test scheduler. */
    private suspend fun awaitResult(vm: ConnectViewModel) = vm.state.first { it.result != null }

    @Test
    fun invalidUrlShowsErrorWithoutProbing() = runTest(dispatcher) {
        val vm = viewModel()
        vm.url.setTextAndPlaceCursorAtEnd("ftp://nope")
        vm.testConnection()
        assertNotNull(vm.state.value.urlError)
        assertNull(vm.state.value.result)
    }

    @Test
    fun successfulProbeProducesReachableResult() = runTest(dispatcher) {
        val vm = viewModel()
        vm.url.setTextAndPlaceCursorAtEnd("100.64.0.1:9119")
        vm.testConnection()
        awaitResult(vm)
        val result = assertIs<ProbeResult.Reachable>(vm.state.value.result)
        assertEquals("http://100.64.0.1:9119", result.url.value)
        assertFalse(vm.state.value.testing)
    }

    @Test
    fun editingClearsPreviousResult() = runTest(dispatcher) {
        val vm = viewModel()
        vm.url.setTextAndPlaceCursorAtEnd("100.64.0.1:9119")
        vm.testConnection()
        awaitResult(vm)
        vm.onUrlEdited()
        assertNull(vm.state.value.result)
    }
}
