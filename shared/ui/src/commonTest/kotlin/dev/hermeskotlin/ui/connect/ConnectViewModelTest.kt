package dev.hermeskotlin.ui.connect

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.hermeskotlin.core.gateway.AccessToken
import dev.hermeskotlin.core.gateway.AccessTokens
import dev.hermeskotlin.core.gateway.CF_ACCESS_CLIENT_ID
import dev.hermeskotlin.core.gateway.CF_ACCESS_CLIENT_SECRET
import dev.hermeskotlin.core.gateway.ClientCertAliases
import dev.hermeskotlin.core.gateway.GatewayProbe
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.ProbeResult
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.gateway.clientCertAliasFor
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

    private fun viewModel(
        certs: ClientCertAliases = ClientCertAliases(),
        gateways: GatewayRepository = GatewayRepository(InMemoryKeyValueStore()),
    ): ConnectViewModel {
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
        return ConnectViewModel(GatewayProbe(createHttpClient(MockEngine(config), access = access)), access, certs, gateways)
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

    @Test
    fun aPickedCertificateIsRememberedForTheAddressAndCanBeCleared() = runTest(dispatcher) {
        val certs = ClientCertAliases()
        val vm = viewModel(certs)
        vm.url.setTextAndPlaceCursorAtEnd("https://hermes.example.com")

        vm.pickClientCert("user-cert")
        assertEquals("user-cert", vm.certAlias.value)
        assertEquals("user-cert", certs.get("hermes.example.com"))

        // Retyping the address finds the pending pick again.
        vm.onUrlEdited()
        assertEquals("user-cert", vm.certAlias.value)

        vm.clearClientCert()
        assertNull(vm.certAlias.value)
        assertNull(certs.get("hermes.example.com"))
    }

    @Test
    fun aConnectScreenPickReachesTheHandshakeThroughTheSharedMap() = runTest(dispatcher) {
        // Regression: the ViewModel and the mTLS engine must share one ClientCertAliases. A pick that
        // lands in a ViewModel-local map never reaches the handshake, so the probe gets a 400/403 and
        // the user sees "Test" fail even with the certificate picked.
        val shared = ClientCertAliases()
        val vm = viewModel(shared)
        vm.url.setTextAndPlaceCursorAtEnd("https://hermes.example.com")

        vm.pickClientCert("user-cert")

        // The engine resolves live from the same instance (the socket's host, then the handshake's
        // alias lookup), so both reads must see the pick.
        assertEquals("user-cert", shared.get("hermes.example.com"), "engine-side map misses the pick")
        assertEquals(
            "user-cert",
            clientCertAliasFor("hermes.example.com", shared, emptyList()),
            "handshake-side resolve misses the pick",
        )
        assertEquals(
            "user-cert",
            clientCertAliasFor("hermes.example.com", shared, listOf(SavedGateway("https://hermes.example.com"))),
            "pending pick must win before the gateway is saved",
        )
    }

    @Test
    fun typingAnotherAddressShowsItsOwnCertificate() = runTest(dispatcher) {
        val certs = ClientCertAliases()
        certs.set("other.example.com", "other-cert")
        val vm = viewModel(certs)
        vm.url.setTextAndPlaceCursorAtEnd("https://hermes.example.com")
        vm.onUrlEdited()
        assertNull(vm.certAlias.value)

        vm.url.setTextAndPlaceCursorAtEnd("https://other.example.com")
        vm.onUrlEdited()
        assertEquals("other-cert", vm.certAlias.value)
    }

    @Test
    fun typingASavedAddressShowsItsStoredCertificate() = runTest(dispatcher) {
        val store = InMemoryKeyValueStore()
        val gateways = GatewayRepository(store)
        gateways.save(SavedGateway("https://saved.example.com", clientCertAlias = "saved-cert"))
        val vm = viewModel(gateways = gateways)

        vm.url.setTextAndPlaceCursorAtEnd("https://saved.example.com")
        vm.onUrlEdited()
        assertEquals("saved-cert", vm.certAlias.value)
    }

    @Test
    fun aSavedAddressTakesThePickOnItsSavedGateway() = runTest(dispatcher) {
        // A pending pick would shadow the saved one until restart, and the sign-in would keep the old one.
        val certs = ClientCertAliases()
        val gateways = GatewayRepository(InMemoryKeyValueStore())
        gateways.save(SavedGateway("https://saved.example.com", clientCertAlias = "old-cert"))
        val vm = viewModel(certs, gateways)
        vm.url.setTextAndPlaceCursorAtEnd("https://saved.example.com")
        vm.onUrlEdited()

        vm.pickClientCert("new-cert")
        assertEquals("new-cert", vm.certAlias.value)
        assertEquals("new-cert", gateways.all().find("https://saved.example.com")?.clientCertAlias)
        assertNull(certs.get("saved.example.com"))
    }

    @Test
    fun forgettingASavedAddresssCertificateClearsItForGood() = runTest(dispatcher) {
        val gateways = GatewayRepository(InMemoryKeyValueStore())
        gateways.save(SavedGateway("https://saved.example.com", clientCertAlias = "saved-cert"))
        val vm = viewModel(gateways = gateways)
        vm.url.setTextAndPlaceCursorAtEnd("https://saved.example.com")
        vm.onUrlEdited()

        vm.clearClientCert()
        assertNull(gateways.all().find("https://saved.example.com")?.clientCertAlias)
        // The sign-in that follows saves the gateway without one: nothing brings the old one back.
        gateways.save(SavedGateway("https://saved.example.com", clientCertAlias = vm.certAlias.value))
        assertNull(gateways.all().find("https://saved.example.com")?.clientCertAlias)
        vm.onUrlEdited()
        assertNull(vm.certAlias.value)
    }

    @Test
    fun pickingWithAnInvalidAddressClearsTheShownCertificate() = runTest(dispatcher) {
        val vm = viewModel()
        vm.url.setTextAndPlaceCursorAtEnd("https://hermes.example.com")
        vm.pickClientCert("user-cert")
        assertEquals("user-cert", vm.certAlias.value)

        vm.url.setTextAndPlaceCursorAtEnd("not a url at all !!!")
        vm.pickClientCert("ignored-cert")
        assertNull(vm.certAlias.value)

        vm.clearClientCert()
        assertNull(vm.certAlias.value)
    }
}
