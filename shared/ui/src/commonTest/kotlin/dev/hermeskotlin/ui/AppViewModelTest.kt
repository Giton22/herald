package dev.hermeskotlin.ui

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotChatBackend
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.BotSession
import dev.hermeskotlin.core.chat.ComposeDraft
import dev.hermeskotlin.ui.chat.ChatTarget
import dev.hermeskotlin.core.cache.InMemoryOfflineDao
import dev.hermeskotlin.core.cache.OfflineCache
import dev.hermeskotlin.core.cache.PlainSealer
import kotlin.coroutines.EmptyCoroutineContext
import dev.hermeskotlin.core.chat.AppLink
import dev.hermeskotlin.core.chat.DraftStart
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatLinks
import dev.hermeskotlin.core.sessions.SeenStore
import dev.hermeskotlin.core.chat.LastChat
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.AccessToken
import dev.hermeskotlin.core.gateway.AccessTokens
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.core.push.PushGateway
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.sessions.SessionListFilter
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.CallTarget
import kotlinx.coroutines.CompletableDeferred
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val home = SavedGateway("http://100.64.0.1:9119")
    private val work = SavedGateway("https://hermes.work.example")

    private val links = ChatLinks()
    private var botTransport: BotTransport? = null
    private var profileGate: CompletableDeferred<Unit>? = null
    private val disk = InMemoryKeyValueStore()
    private val store = object : KeyValueStore by disk {
        override suspend fun put(key: String, value: String) {
            if (key.startsWith("profile.v1.")) profileGate?.await()
            disk.put(key, value)
        }
    }
    private val cookies = PersistentCookiesStorage(store)
    private val gateways = GatewayRepository(store)
    private val lastChats = LastChatStore(store)
    private val access = AccessTokens(store)
    private val cache = OfflineCache(InMemoryOfflineDao(), PlainSealer, EmptyCoroutineContext) { 0L }

    // The connection waits on its ticket for good (a retry loop would keep the test clock busy, so a missed
    // route would hang instead of time out); every other call fails, and sign-out still wipes cookies.
    // On the test dispatcher, so no request is still finishing on another thread when a test ends.
    private val client = createHttpClient(
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = this@AppViewModelTest.dispatcher
                addHandler { request ->
                    if (request.url.encodedPath.endsWith("ws-ticket")) {
                        if (botTransport == null) awaitCancellation()
                        return@addHandler respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    if (request.url.encodedPath == "/api/sessions/s1/messages") {
                        sessionGate?.await()
                        respond("""{"session_id":"s1","messages":[]}""", sessionStatus, headersOf(HttpHeaders.ContentType, "application/json"))
                    } else respond("", HttpStatusCode.ServiceUnavailable)
                }
            },
        ),
        cookies,
    )
    private val auth = AuthApi(client, cookies)
    private lateinit var callSettings: SettingsStore
    private var sessionStatus = HttpStatusCode.OK
    private var sessionGate: CompletableDeferred<Unit>? = null

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // The connection's retry loop runs in the test's background scope, so it stops with the test.
    private fun TestScope.viewModel(callStore: SettingsStore? = null): AppViewModel {
        val connection = GatewayConnection(auth, openSocket = { _, _ -> botTransport ?: error("no socket in tests") }, scope = backgroundScope)
        val host = ChatHost(connection, SessionsApi(client), backgroundScope)
        callSettings = callStore ?: SettingsStore(store, backgroundScope)
        val push = PushSetup(PushApi(connection), NoPushKeys, connection, gateways, SettingsStore(store, backgroundScope), backgroundScope) { "Test" }
        return AppViewModel(gateways, auth, connection, lastChats, host, ProfileStore(store), links, BotChats(if (botTransport == null) NoBotChats else dev.hermeskotlin.core.bots.BotsApi(connection)), push, access, callSettings, SessionsApi(client), BotsApi(connection), cache, seen)
    }

    private val seen = SeenStore(store) { 1_000.0 }

    // Push is never registered; bot-call tests supply their own connected RPC transport.
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
    fun theCallShortcutUsesItsGatewayTargetIncludingAnAlreadyOpenSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        gateways.save(home)
        signedIn(home)
        store.put("settings.v1", """{"callTargets":{"${home.url}":{"sessionId":"s1","label":"Planning"},"${work.url}":{"profile":"other"}}}""")
        val vm = viewModel()
        runCurrent()
        vm.awaitChat(home)
        links.follow(AppLink.VoiceChat)
        runCurrent()
        val first = assertIs<Route.Chat>(vm.route.value).target
        assertEquals("s1", first.storedSessionId)
        assertNull(first.profile)
        assertEquals(DraftStart.VoiceChat, assertNotNull(first.draft).start)
        links.follow(AppLink.VoiceChat)
        runCurrent()
        val second = assertIs<Route.Chat>(vm.route.value).target
        assertEquals("s1", second.storedSessionId)
        assertTrue(second.nonce > first.nonce)
    }

    @Test
    fun freshDefaultCallsKeepTheSelectedProfileAndPickedSessionsKeepTheirOwnScope() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        gateways.save(home)
        signedIn(home)
        ProfileStore(store).set(home.gatewayUrl, "work")
        val vm = viewModel()
        runCurrent()
        for (target in listOf(null, CallTarget(), CallTarget(sessionId = "s1"))) {
            callSettings.update { it.copy(callTargets = target?.let { mapOf(home.url to it) }.orEmpty()) }
            links.follow(AppLink.VoiceChat)
            runCurrent()
            val chat = assertIs<Route.Chat>(vm.route.value).target
            assertEquals(if (target?.sessionId == null) "work" else null, chat.profile)
            assertEquals(target?.sessionId, chat.storedSessionId)
            assertEquals(DraftStart.VoiceChat, assertNotNull(chat.draft).start)
        }
    }

    @Test
    fun defaultAndExistingSessionsReuseCallRoutingWithoutChangingDictation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        gateways.save(home)
        signedIn(home)
        val vm = viewModel()
        runCurrent()
        for (target in listOf(CallTarget(), CallTarget(sessionId = "s1"))) {
            callSettings.update { it.copy(callTargets = mapOf(home.url to target)) }
            links.follow(AppLink.VoiceChat)
            runCurrent()
            val chat = assertIs<Route.Chat>(vm.route.value).target
            assertEquals(target.profile, chat.profile)
            assertEquals(target.sessionId, chat.storedSessionId)
            assertEquals(DraftStart.VoiceChat, assertNotNull(chat.draft).start)
        }
        links.follow(AppLink.NewChatVoice)
        runCurrent()
        val dictation = assertNotNull(assertIs<Route.Chat>(vm.route.value).target.draft)
        assertEquals(DraftStart.Dictation, dictation.start)
    }

    private class BotTransport : dev.hermeskotlin.core.rpc.RpcTransport {
        private val frames = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        override val incoming = kotlinx.coroutines.flow.flow { for (frame in frames) emit(frame) }
        suspend fun ready() = frames.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"heartbeat":true}}}""")
        override suspend fun send(text: String) {
            val request = dev.hermeskotlin.core.network.HermesJson.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
            check(request["method"].toString() == "\"profiles.list\"")
            frames.send("""{"jsonrpc":"2.0","id":${request["id"]},"result":{"profiles":[{"name":"sample_bot","display_name":"Sample bot","ui_meta":{"hermes-bots":{"title":"Personal assistant"}},"canonical_session":{"id":"old","resolved_id":"s1"}}]}}""")
        }
        override suspend fun close(code: Short, reason: String) = Unit
    }

    @Test
    fun aBotCallUsesItsPermanentConversationAndBotTitle() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        gateways.save(home)
        signedIn(home)
        ProfileStore(store).set(home.gatewayUrl, "other_chats")
        botTransport = BotTransport()
        botTransport!!.ready()
        val vm = viewModel()
        runCurrent()
        callSettings.update { it.copy(callTargets = mapOf(home.url to CallTarget(profile = "sample_bot", label = "Sample bot"))) }
        repeat(2) {
            links.follow(AppLink.VoiceChat)
            runCurrent()
            val target = assertIs<Route.Chat>(vm.route.value).target
            assertEquals("s1", target.storedSessionId)
            assertEquals("sample_bot", target.profile)
            assertEquals("Personal assistant", target.bot?.label)
            assertEquals("other_chats", target.rememberedIn)
            assertEquals(DraftStart.VoiceChat, assertNotNull(target.draft).start)
        }
    }

    @Test
    fun aSavedBotSessionChoiceRequiresReselectionInsteadOfCallingAnotherChat() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        gateways.save(home)
        signedIn(home)
        val vm = viewModel()
        runCurrent()
        callSettings.update { it.copy(callTargets = mapOf(home.url to CallTarget(profile = "sample_bot", sessionId = "s1"))) }
        val before = vm.route.value
        links.follow(AppLink.VoiceChat)
        runCurrent()
        assertEquals(before, vm.route.value)
        assertNotNull(vm.callError.value)
    }

    @Test
    fun unavailableTargetsAndNavigationDuringLookupNeverStartAFallbackCall() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        gateways.save(home)
        signedIn(home)
        botTransport = BotTransport()
        botTransport!!.ready()
        val vm = viewModel()
        runCurrent()
        val before = vm.route.value
        for (target in listOf(CallTarget(sessionId = "s1"), CallTarget(profile = "removed"), CallTarget(profile = " "), CallTarget(profile = "sample_bot", sessionId = "s1"))) {
            sessionStatus = HttpStatusCode.NotFound
            callSettings.update { it.copy(callTargets = mapOf(home.url to target)) }
            links.follow(AppLink.VoiceChat)
            runCurrent()
            assertEquals(before, vm.route.value)
            assertNotNull(vm.callError.value)
        }
        sessionStatus = HttpStatusCode.OK
        sessionGate = CompletableDeferred()
        callSettings.update { it.copy(callTargets = mapOf(home.url to CallTarget(sessionId = "s1"))) }
        links.follow(AppLink.VoiceChat)
        runCurrent()
        assertTrue(vm.openingCall.value)
        profileGate = CompletableDeferred()
        vm.switchProfile("sample_bot")
        runCurrent()
        val navigated = vm.route.value
        sessionGate!!.complete(Unit)
        runCurrent()
        assertEquals(navigated, vm.route.value)
        assertNull(assertIs<Route.Chat>(vm.route.value).target.draft)
        assertNull(vm.callError.value)
        assertFalse(vm.openingCall.value)
        profileGate!!.complete(Unit)
        runCurrent()
        sessionGate = CompletableDeferred()
        links.follow(AppLink.VoiceChat)
        runCurrent()
        links.follow(AppLink.VoiceChat)
        runCurrent()
        assertTrue(vm.openingCall.value)
        val pending = vm.route.value
        vm.cancelPendingCall()
        sessionGate!!.complete(Unit)
        runCurrent()
        assertFalse(vm.openingCall.value)
        assertEquals(pending, vm.route.value)
    }

    @Test
    fun aColdShortcutWaitsForItsSavedTarget() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        gateways.save(home)
        signedIn(home)
        store.put("settings.v1", """{"callTargets":{"${home.url}":{"sessionId":"s1"}}}""")
        val loaded = CompletableDeferred<Unit>()
        val delayed = object : KeyValueStore by store {
            override suspend fun get(key: String): String? {
                if (key == "settings.v1") loaded.await()
                return store.get(key)
            }
        }
        val vm = viewModel(SettingsStore(delayed, backgroundScope))
        links.follow(AppLink.VoiceChat)
        runCurrent()
        assertNull(assertIs<Route.Chat>(vm.route.value).target.draft)
        loaded.complete(Unit)
        runCurrent()
        assertEquals("s1", assertIs<Route.Chat>(vm.route.value).target.storedSessionId)
    }

    @Test
    fun freshCallResolutionWinsOverCachedBotChatBeforeNormalFollowingResumes() = runTest {
        val vm = viewModel()
        val target = ChatTarget(home, "fresh", "Sample", draft = ComposeDraft(start = DraftStart.VoiceChat))
        assertFalse(vm.shouldFollowBotChat(target, null))
        assertFalse(vm.shouldFollowBotChat(target, BotSession(id = "cached")))
        assertFalse(vm.shouldFollowBotChat(target, BotSession(id = "fresh")))
        assertTrue(vm.shouldFollowBotChat(target, BotSession(id = "moved_again")))
        assertTrue(vm.shouldFollowBotChat(target.copy(readUpTo = 1_000.0), BotSession(id = "moved_again")))
        assertTrue(vm.shouldFollowBotChat(target.copy(draft = null), BotSession(id = "cached")))
    }

    @Test
    fun aNewCallDoesNotInheritAnEarlierCallsRosterGuard() = runTest {
        val vm = viewModel()
        val previous = ChatTarget(home, "previous_call", "Sample", draft = ComposeDraft(start = DraftStart.VoiceChat))
        assertFalse(vm.shouldFollowBotChat(previous, BotSession(id = "previous_call")))
        val target = previous.copy(storedSessionId = "fresh")
        assertFalse(vm.shouldFollowBotChat(target, BotSession(id = "cached")))
        assertFalse(vm.shouldFollowBotChat(target, BotSession(id = "fresh")))
        assertFalse(viewModel().shouldFollowBotChat(target, BotSession(id = "cached")))
    }

    @Test
    fun aCallShowsItsConversationOnceEvenWhenResolvedBeforeHomeIsMounted() = runTest {
        val vm = viewModel()
        val target = ChatTarget(home, "s1", "Sample", draft = ComposeDraft(start = DraftStart.VoiceChat))
        assertFalse(vm.consumeCallNavigation(target.copy(draft = null)))
        assertTrue(vm.consumeCallNavigation(target))
        assertFalse(vm.consumeCallNavigation(target))
        assertFalse(vm.consumeCallNavigation(target.copy(readUpTo = 1_000.0)))
        assertTrue(vm.consumeCallNavigation(target.copy(nonce = target.nonce + 1)))
        assertTrue(viewModel().consumeCallNavigation(target))
    }

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

    @Test
    fun removingAGatewayForgetsTheChatsSavedFromIt() = runTest {
        gateways.save(home)
        signedIn(home)
        cache.saveList(home.gatewayUrl, null, SessionListFilter.Recent, listOf(SessionSummary("a")))
        val vm = viewModel()
        vm.awaitChat(home)

        vm.removeGateway(home)
        vm.route.first { it is Route.Connect }

        assertNull(cache.savedList(home.gatewayUrl, null, SessionListFilter.Recent))
    }

    @Test
    fun signingOutForgetsTheChatsSavedFromThatGateway() = runTest {
        gateways.save(home)
        signedIn(home)
        val other = SavedGateway("https://other.example.ts.net")
        cache.saveList(home.gatewayUrl, null, SessionListFilter.Recent, listOf(SessionSummary("a")))
        cache.saveList(other.gatewayUrl, null, SessionListFilter.Recent, listOf(SessionSummary("b")))
        val vm = viewModel()
        vm.awaitChat(home)

        vm.signOut()
        vm.awaitSignIn(home)

        assertNull(cache.savedList(home.gatewayUrl, null, SessionListFilter.Recent))
        assertNotNull(cache.savedList(other.gatewayUrl, null, SessionListFilter.Recent))
    }

    @Test
    fun aChatOpenedFromANotificationStartsWhereItWasLastRead() = runTest {
        gateways.save(home)
        signedIn(home)
        val app = viewModel()
        app.awaitChat(home)
        seen.markSeen(home.gatewayUrl, SessionSummary("s9", lastActive = 1_200.0))

        links.open("s9", "Report")
        val chat = app.route.first { it is Route.Chat && it.target.storedSessionId == "s9" } as Route.Chat

        // The same last look the chat list would give, so the "New messages" line goes in the same place.
        assertEquals(1_200.0, chat.target.readUpTo)
    }

    @Test
    fun aChatNeverOpenedOnThisPhoneCountsFromWhenTrackingBegan() = runTest {
        gateways.save(home)
        signedIn(home)
        val app = viewModel()
        app.awaitChat(home)

        links.open("s7", "Report")
        val chat = app.route.first { it is Route.Chat && it.target.storedSessionId == "s7" } as Route.Chat

        assertEquals(1_000.0, chat.target.readUpTo)
    }

    @Test
    fun aNotificationForTheChatLeftOpenPlacesTheLineInIt() = runTest {
        gateways.save(home)
        signedIn(home)
        lastChats.set(home.gatewayUrl, LastChat("s9", "Report"))
        val app = viewModel()
        assertEquals("s9", app.awaitChat(home).target.storedSessionId)
        // The user left the app on this chat; a reply came in and its notification was tapped.
        seen.markLooked(home.gatewayUrl, "s9", at = 1_300.0)

        links.open("s9", "Report")
        val chat = app.route.first { it is Route.Chat && it.target.readUpTo != null } as Route.Chat

        assertEquals("s9", chat.target.storedSessionId)
        assertEquals(1_300.0, chat.target.readUpTo)
    }
}
