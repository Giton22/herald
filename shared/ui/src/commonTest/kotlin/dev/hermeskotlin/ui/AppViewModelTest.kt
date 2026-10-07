package dev.hermeskotlin.ui

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotChatBackend
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatLinks
import dev.hermeskotlin.core.chat.LastChat
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.AccessToken
import dev.hermeskotlin.core.gateway.AccessTokens
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.core.push.PushGateway
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val home = SavedGateway("http://100.64.0.1:9119")
    private val work = SavedGateway("https://hermes.work.example")

    private val store = InMemoryKeyValueStore()
    private val cookies = PersistentCookiesStorage(store)
    private val gateways = GatewayRepository(store)
    private val lastChats = LastChatStore(store)
    private val access = AccessTokens(store)

    // The connection waits on its ticket for good (a retry loop would keep the test clock busy, so a missed
    // route would hang instead of time out); every other call fails, and sign-out still wipes cookies.
    // On the test dispatcher, so no request is still finishing on another thread when a test ends.
    private val client = createHttpClient(
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = this@AppViewModelTest.dispatcher
                addHandler { request ->
                    if (request.url.encodedPath.endsWith("ws-ticket")) awaitCancellation()
                    respond("", HttpStatusCode.ServiceUnavailable)
                }
            },
        ),
        cookies,
    )
    private val auth = AuthApi(client, cookies)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // The connection's retry loop runs in the test's background scope, so it stops with the test.
    private fun TestScope.viewModel(): AppViewModel {
        val connection = GatewayConnection(auth, openSocket = { _, _ -> error("no socket in tests") }, scope = backgroundScope)
        val host = ChatHost(connection, SessionsApi(client), backgroundScope)
        val push = PushSetup(PushApi(connection), NoPushKeys, connection, gateways, SettingsStore(store, backgroundScope), backgroundScope) { "Test" }
        return AppViewModel(gateways, auth, connection, lastChats, host, ProfileStore(store), ChatLinks(), BotChats(NoBotChats), push, access)
    }

    // Push was never set up and no bot is opened in these tests.
    private object NoPushKeys : PushKeys {
        override val deviceId = "test"
        override fun registration(name: String) = error("not registering in tests")
        override fun pinned(gatewayUrl: String): PushGateway? = null
        override fun accepts(keys: PushGateway) = false
        override fun pin(gatewayUrl: String, keys: PushGateway) = Unit
        override fun retire(gatewayUrl: String) = Unit
        override fun retired(gatewayUrl: String) = emptySet<String>()
        override fun forgetRetired(gatewayUrl: String, deviceId: String) = Unit
        override fun pinnedGatewayUrl(): String? = null
    }

    private object NoBotChats : BotChatBackend {
        override suspend fun botChat(profile: String) = error("no bots in tests")
        override suspend fun startBotChat(profile: String) = error("no bots in tests")
    }

    private suspend fun signedIn(gateway: SavedGateway) =
        cookies.addCookie(Url(gateway.url + "/"), Cookie("hermes_session_at", "token", path = "/"))

    private suspend fun AppViewModel.awaitChat(gateway: SavedGateway) =
        route.first { it is Route.Chat && it.gateway.url == gateway.url } as Route.Chat

    private suspend fun AppViewModel.awaitSignIn(gateway: SavedGateway) =
        route.first { it is Route.SignIn && it.gateway.url == gateway.url } as Route.SignIn

    @Test
    fun opensTheGatewayInUseWhereItWasLeft() = runTest {
        gateways.save(home)
        gateways.save(work)
        signedIn(work)
        lastChats.set(work.gatewayUrl, LastChat("s1", "Report"))

        val chat = viewModel().awaitChat(work)

        assertEquals("s1", chat.target.storedSessionId)
    }

    @Test
    fun switchingKeepsBothSignedInAndReopensEachOnesChat() = runTest {
        gateways.save(home)
        gateways.save(work)
        signedIn(home)
        signedIn(work)
        lastChats.set(home.gatewayUrl, LastChat("h1"))
        val vm = viewModel()
        vm.awaitChat(work)

        vm.switchGateway(home)

        assertEquals("h1", vm.awaitChat(home).target.storedSessionId)
        assertEquals(home, gateways.current())
        assertEquals(setOf(home.url, work.url), vm.gatewayChoices.first { it.signedIn.size == 2 }.signedIn)
    }

    @Test
    fun switchingToASignedOutGatewayAsksToSignIn() = runTest {
        gateways.save(home)
        gateways.save(work)
        signedIn(work)
        val vm = viewModel()
        vm.awaitChat(work)

        vm.switchGateway(home)

        val signIn = vm.awaitSignIn(home)
        assertFalse(signIn.adding)
        // Back on a saved gateway's sign-in leaves the app rather than forgetting it.
        assertFalse(vm.back())
    }

    @Test
    fun aNewGatewayIsSavedOnlyOnceSignedIn() = runTest {
        gateways.save(home)
        signedIn(home)
        val vm = viewModel()
        vm.awaitChat(home)

        vm.addGateway()
        assertEquals(Route.Connect(canCancel = true), vm.route.first { it is Route.Connect })
        vm.onGatewayChosen(work)
        assertTrue(vm.awaitSignIn(work).adding)
        assertEquals(listOf(home), gateways.all().gateways)

        signedIn(work)
        vm.onSignedIn(work)

        vm.awaitChat(work)
        assertEquals(listOf(home, work), gateways.all().gateways)
        assertEquals(work, gateways.current())
        assertEquals(home, gateways.all().primary)
    }

    @Test
    fun backingOutOfAddingReturnsToTheGatewayInUse() = runTest {
        gateways.save(home)
        signedIn(home)
        val vm = viewModel()
        vm.awaitChat(home)
        vm.addGateway()
        vm.route.first { it is Route.Connect }
        vm.onGatewayChosen(work)
        vm.awaitSignIn(work)

        assertTrue(vm.back())
        assertEquals(Route.Connect(canCancel = true), vm.route.first { it is Route.Connect })
        assertTrue(vm.back())

        vm.awaitChat(home)
        assertEquals(listOf(home), gateways.all().gateways)
    }

    @Test
    fun enteringASavedSignedOutAddressStillBacksOutToAdding() = runTest {
        gateways.save(home)
        gateways.save(work)
        signedIn(work)
        val vm = viewModel()
        vm.awaitChat(work)

        vm.addGateway()
        vm.route.first { it is Route.Connect }
        vm.onGatewayChosen(home)
        assertTrue(vm.awaitSignIn(home).adding)

        // Back goes to the add screen, not out of the app.
        assertTrue(vm.back())
        assertEquals(Route.Connect(canCancel = true), vm.route.first { it is Route.Connect })
        assertTrue(vm.back())
        vm.awaitChat(work)
    }

    @Test
    fun enteringASavedSignedInAddressJustSwitchesToIt() = runTest {
        gateways.save(home)
        gateways.save(work)
        signedIn(home)
        signedIn(work)
        val vm = viewModel()
        vm.awaitChat(work)

        vm.addGateway()
        vm.route.first { it is Route.Connect }
        vm.onGatewayChosen(home)

        vm.awaitChat(home)
    }

    @Test
    fun removingTheGatewayInUseSignsOutAndMovesToThePrimary() = runTest {
        gateways.save(home)
        gateways.save(work)
        signedIn(home)
        signedIn(work)
        val vm = viewModel()
        vm.awaitChat(work)

        vm.removeGateway(work)

        vm.awaitChat(home)
        assertEquals(listOf(home), gateways.all().gateways)
        assertFalse(auth.hasStoredSession(work.gatewayUrl))
        assertTrue(auth.hasStoredSession(home.gatewayUrl))
    }

    @Test
    fun removingAGatewayForgetsItsAccessTokenUnlessItsHostIsStillSaved() = runTest {
        val workAgain = SavedGateway("https://hermes.work.example/other")
        val token = AccessToken("id.access", "secret")
        gateways.save(work)
        gateways.save(workAgain)
        gateways.save(home)
        access.set(work.gatewayUrl.host, token)
        signedIn(home)
        val vm = viewModel()
        vm.awaitChat(home)

        vm.removeGateway(work)
        gateways.list.first { it.gateways.size == 2 }
        assertEquals(token, access.get(work.gatewayUrl.host), "another saved gateway still uses the host")

        vm.removeGateway(workAgain)
        gateways.list.first { it.gateways.size == 1 }
        assertNull(access.get(work.gatewayUrl.host))
    }

    @Test
    fun launchForgetsAccessTokensOfAddressesNeverSignedInTo() = runTest {
        val token = AccessToken("id.access", "secret")
        gateways.save(work)
        signedIn(work)
        access.set(work.gatewayUrl.host, token)
        access.set("typo.example.com", token)

        viewModel().awaitChat(work)

        assertEquals(token, access.get(work.gatewayUrl.host))
        assertNull(access.get("typo.example.com"))
    }

    @Test
    fun removingTheLastGatewayGoesBackToConnect() = runTest {
        gateways.save(home)
        signedIn(home)
        val vm = viewModel()
        vm.awaitChat(home)

        vm.removeGateway(home)

        assertEquals(Route.Connect(canCancel = false), vm.route.first { it is Route.Connect })
        assertNull(gateways.current())
    }

    @Test
    fun signingOutKeepsTheGatewaySaved() = runTest {
        gateways.save(home)
        signedIn(home)
        val vm = viewModel()
        vm.awaitChat(home)

        vm.signOut()

        assertIs<Route.SignIn>(vm.awaitSignIn(home))
        assertEquals(listOf(home), gateways.all().gateways)
        assertTrue(vm.gatewayChoices.first { it.signedIn.isEmpty() }.signedIn.isEmpty())
    }
}
