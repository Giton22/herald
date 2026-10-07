package dev.hermeskotlin.ui.connect

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.hermeskotlin.core.gateway.AccessToken
import dev.hermeskotlin.core.gateway.AccessTokens
import dev.hermeskotlin.core.gateway.CF_ACCESS_CLIENT_ID
import dev.hermeskotlin.core.gateway.CF_ACCESS_CLIENT_SECRET
import dev.hermeskotlin.core.gateway.GatewayProbe
import dev.hermeskotlin.core.gateway.ProbeResult
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val access = AccessTokens(InMemoryKeyValueStore())
    private val seen = mutableListOf<HttpRequestData>()

    private fun viewModel(): ConnectViewModel {
        // On the test dispatcher, so no request is still finishing on another thread when a test ends.
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { request ->
            seen += request
            respond(
                """{"version":"0.42.0","auth_required":true,"auth_providers":["basic"]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return ConnectViewModel(GatewayProbe(createHttpClient(MockEngine(config), access = access)), access)
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

    @Test
    fun accessTokenIsSavedForTheHostAndSentWithTheProbe() = runTest(dispatcher) {
        val vm = viewModel()
        vm.url.setTextAndPlaceCursorAtEnd("https://hermes.example.com")
        vm.accessClientId.setTextAndPlaceCursorAtEnd(" id.access ")
        vm.accessClientSecret.setTextAndPlaceCursorAtEnd("secret")
        vm.testConnection()
        awaitResult(vm)
        assertEquals(AccessToken("id.access", "secret"), access.get("hermes.example.com"))
        assertEquals("id.access", seen.single().headers[CF_ACCESS_CLIENT_ID])
        assertEquals("secret", seen.single().headers[CF_ACCESS_CLIENT_SECRET])
        assertTrue(vm.accessSaved.value)
        assertEquals("", vm.accessClientSecret.text.toString(), "the secret doesn't linger on screen")
    }

    @Test
    fun aSavedTokenShowsForItsAddressAndCanBeForgotten() = runTest(dispatcher) {
        access.set("hermes.example.com", AccessToken("id.access", "secret"))
        val vm = viewModel()

        vm.url.setTextAndPlaceCursorAtEnd("https://hermes.example.com")
        vm.onUrlEdited()
        assertTrue(vm.accessSaved.value)

        vm.url.setTextAndPlaceCursorAtEnd("https://other.example.com")
        vm.onUrlEdited()
        assertFalse(vm.accessSaved.value)

        vm.url.setTextAndPlaceCursorAtEnd("https://hermes.example.com")
        vm.onUrlEdited()
        vm.forgetAccessToken()
        assertFalse(vm.accessSaved.value)
        assertNull(access.get("hermes.example.com"))
    }

    @Test
    fun halfAnAccessTokenIsAnErrorWithoutProbing() = runTest(dispatcher) {
        val vm = viewModel()
        vm.url.setTextAndPlaceCursorAtEnd("https://hermes.example.com")
        vm.accessClientId.setTextAndPlaceCursorAtEnd("id.access")
        vm.testConnection()
        assertNotNull(vm.state.value.accessError)
        assertNull(access.get("hermes.example.com"))
        assertEquals(emptyList(), seen)
    }

    @Test
    fun blankAccessFieldsKeepTheSavedToken() = runTest(dispatcher) {
        access.set("hermes.example.com", AccessToken("id.access", "secret"))
        val vm = viewModel()
        vm.url.setTextAndPlaceCursorAtEnd("https://hermes.example.com")
        vm.testConnection()
        awaitResult(vm)
        assertEquals("id.access", seen.single().headers[CF_ACCESS_CLIENT_ID])
    }
}
