package dev.hermeskotlin.ui.bots

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotChatBackend
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.BotHealth
import dev.hermeskotlin.core.bots.BotSession
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.bots.RuntimeCheck
import dev.hermeskotlin.core.bots.SidebarModeStore
import dev.hermeskotlin.core.chat.AttentionTracker
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.cron.CronApi
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.models.ModelsApi
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.sessions.ActiveSessions
import dev.hermeskotlin.core.sessions.SeenStore
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.widget.NowWidgetSnapshot
import dev.hermeskotlin.core.widget.WidgetSnapshotStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B6 (docs/WIDGETS-SPEC.md D7): deleting a bot clears the widget snapshots its profile wrote, beside
 * the saved chats — the Now and Chats widgets must not keep showing a bot the gateway no longer has.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotsViewModelForgetTest {

    private val gateway = GatewayUrl.parse("https://bots.example.ts.net")
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = InMemoryKeyValueStore()
    private val cookies = PersistentCookiesStorage(store)

    // Every REST call but the profile delete fails; the socket never connects, so the roster read
    // after the delete throws and is caught — the test only waits for the deletion itself.
    private val client = createHttpClient(
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = this@BotsViewModelForgetTest.dispatcher
                addHandler { request ->
                    if (request.url.encodedPath == "/api/profiles/researcher") {
                        respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    } else {
                        respond("", HttpStatusCode.ServiceUnavailable)
                    }
                }
            },
        ),
        cookies,
    )
    private val auth = AuthApi(client, cookies)

    private object NoBotChats : BotChatBackend {
        override suspend fun botChat(profile: String) = BotSession(id = "bc-$profile", resolvedId = "bc-$profile")
        override suspend fun startBotChat(profile: String) = "bc-$profile"
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun deletingABotClearsItsWidgetSnapshots() = runTest {
        val connection = GatewayConnection(auth, openSocket = { _, _ -> error("no socket in tests") }, scope = backgroundScope)
        val host = ChatHost(connection, SessionsApi(client), backgroundScope)
        val widgets = WidgetSnapshotStore(InMemoryKeyValueStore())
        widgets.saveNow(NowWidgetSnapshot(gateway.value, profile = "researcher", savedAtEpochSeconds = 1))
        assertNotNull(widgets.readNow(gateway.value, "researcher"))
        val vm = BotsViewModel(
            BotsApi(connection),
            BotChats(NoBotChats),
            connection,
            SeenStore(store) { 0.0 },
            SidebarModeStore(store),
            SessionsApi(client),
            ProfilesApi(client),
            BotHealth(connection, host, backgroundScope) { RuntimeCheck(ok = true) },
            CronApi(client),
            ModelsApi(connection),
            host,
            AttentionTracker(connection, host, ActiveSessions(connection, backgroundScope), backgroundScope),
            widgetStore = widgets,
        )
        vm.bind(gateway)

        var deleted = false
        vm.delete(Bot(name = "researcher")) { deleted = true }
        runCurrent()

        assertTrue(deleted)
        assertNull(widgets.readNow(gateway.value, "researcher"), "the deleted bot's widget snapshots go with it (D7)")
    }
}
