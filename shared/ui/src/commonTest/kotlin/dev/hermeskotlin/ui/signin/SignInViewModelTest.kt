package dev.hermeskotlin.ui.signin

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.BrowserSignIn
import dev.hermeskotlin.core.auth.LoopbackListener
import dev.hermeskotlin.core.auth.LoopbackReceiver
import dev.hermeskotlin.core.auth.NativeTokens
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.gateway.GatewayProbe
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val sso = SavedGateway("https://sso.example.com")
    private val password = SavedGateway("https://pw.example.com")
    private val offline = SavedGateway("https://offline.example.com")

    private val store = InMemoryKeyValueStore()
    private val cookies = PersistentCookiesStorage(store)
    private val tokens = NativeTokens(store)

    /** The browser's answer for the sign-in in progress; completed by the test. */
    private var callback = CompletableDeferred<Map<String, String>>()
    private var opened: Url? = null
    private var listenersClosed = 0

    private val loopback = LoopbackReceiver {
        object : LoopbackListener {
            override val redirectUri = "http://127.0.0.1:5/callback"
            override suspend fun awaitCallback() = callback.await()
            override fun close() {
                listenersClosed++
            }
        }
    }

    private fun viewModel(): SignInViewModel {
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { request ->
            when {
                request.url.host == offline.gatewayUrl.host -> respondError(HttpStatusCode.BadGateway)
                request.url.encodedPath == "/auth/native/token" -> respond(
                    """{"access_token":"AT","refresh_token":"RT","expires_at":1900000000,"provider":"self-hosted"}""",
                    HttpStatusCode.OK, json,
                )
                request.url.host == sso.gatewayUrl.host -> respond(
                    """{"version":"0.21.5","auth_required":true,"auth_providers":["self-hosted"],"auth_flows":["cookie","native_pkce"]}""",
                    HttpStatusCode.OK, json,
                )
                else -> respond("""{"version":"0.21.5","auth_required":true,"auth_providers":["basic"]}""", HttpStatusCode.OK, json)
            }
        }
        val client = createHttpClient(MockEngine(config), cookies, bearer = tokens)
        val auth = AuthApi(client, cookies, tokens)
        return SignInViewModel(auth, GatewayProbe(client), BrowserSignIn(auth, loopback))
    }

    private fun answer() = callback.complete(mapOf("code" to "C", "state" to opened!!.parameters["state"]!!))

    @Test
    fun anSsoOnlyGatewayOffersJustTheBrowser() = runTest {
        val vm = viewModel()
        vm.load(sso)
        assertEquals(SignInMethods(password = false, browser = true), vm.state.value.methods)

        vm.load(password)
        assertEquals(SignInMethods(password = true, browser = false), vm.state.value.methods)
    }

    @Test
    fun aGatewayThatCantBeAskedOffersBoth() = runTest {
        val vm = viewModel()
        vm.load(offline)
        assertEquals(SignInMethods(password = true, browser = true), vm.state.value.methods)
    }

    @Test
    fun aBrowserSignInSignsItsGatewayIn() = runTest {
        val vm = viewModel()
        vm.load(sso)
        vm.signInWithBrowser(sso) { opened = Url(it) }
        assertTrue(vm.state.value.waitingForBrowser)

        answer()

        assertEquals(sso.url, vm.state.value.signedInUrl)
        assertFalse(vm.state.value.waitingForBrowser)
        assertEquals("AT", tokens.get(sso.gatewayUrl)?.accessToken)
    }

    @Test
    fun switchingGatewaysDropsTheUnfinishedBrowserSignIn() = runTest {
        val vm = viewModel()
        vm.load(sso)
        vm.signInWithBrowser(sso) { opened = Url(it) }

        vm.load(password)

        assertFalse(vm.state.value.waitingForBrowser)
        assertFalse(vm.state.value.signingIn)
        assertEquals(1, listenersClosed)
        answer()
        assertNull(vm.state.value.signedInUrl)
        assertNull(tokens.get(sso.gatewayUrl))
    }

    @Test
    fun cancelStopsWaiting() = runTest {
        val vm = viewModel()
        vm.load(sso)
        vm.signInWithBrowser(sso) { opened = Url(it) }
        vm.cancelBrowser()
        assertFalse(vm.state.value.signingIn)
        assertEquals(1, listenersClosed)
    }
}
