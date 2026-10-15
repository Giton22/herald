package dev.hermeskotlin.ui.settings

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.ConnectionCheck
import dev.hermeskotlin.core.gateway.GatewayProbe
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.plugins.NextPromptApi
import dev.hermeskotlin.core.plugins.PluginsApi
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.core.push.PushGateway
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushRegistration
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.InMemoryBlobFile
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.WallpaperStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The setup sheet's Use existing path ([SettingsViewModel.useAssistant]): pointing the assistant at a profile the
 * gateway already has changes only the local mapping. No profile is created, no toolset is pinned, nothing is
 * deleted — the socket stays silent, so the chosen profile keeps its gateway-side setup exactly as it is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantUseExistingTest {

    private val main = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUpMain() = Dispatchers.setMain(main)

    @AfterTest
    fun resetMain() = Dispatchers.resetMain()

    /** Records every text frame the connection layer would send the gateway. */
    private class RecordingTransport : RpcTransport {
        val sent = mutableListOf<String>()

        override val incoming: Flow<String> = emptyFlow()

        override suspend fun send(text: String) {
            sent += text
        }

        override suspend fun close(code: Short, reason: String) {}
    }

    /** Use existing never touches the push identity; these only need to exist. */
    private class FakeKeys : PushKeys {
        override val deviceId: String = "test-phone"
        override fun registration(name: String): PushRegistration = error("Use existing never registers for push")
        override fun pinned(gatewayUrl: String): PushGateway? = null
        override fun accepts(keys: PushGateway): Boolean = true
        override fun pin(gatewayUrl: String, keys: PushGateway) {}
        override fun retire(gatewayUrl: String) {}
        override fun retired(gatewayUrl: String): Set<String> = emptySet()
        override fun forgetRetired(gatewayUrl: String, deviceId: String) {}
        override fun pinnedGatewayUrl(): String? = null
    }

    private fun TestScope.viewModel(transport: RecordingTransport, disk: InMemoryKeyValueStore): Pair<SettingsViewModel, SettingsStore> {
        val store = SettingsStore(disk, backgroundScope)
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
        val auth = AuthApi(http, PersistentCookiesStorage(InMemoryKeyValueStore()))
        val connection = GatewayConnection(auth, { _, _ -> transport }, backgroundScope)
        val probe = GatewayProbe(http)
        val push = PushSetup(
            api = PushApi(connection),
            keys = FakeKeys(),
            connection = connection,
            gateways = GatewayRepository(InMemoryKeyValueStore()),
            settings = store,
            scope = backgroundScope,
            deviceName = { "test" },
        )
        val vm = SettingsViewModel(
            store = store,
            auth = auth,
            probe = probe,
            connectionCheck = ConnectionCheck(probe, auth, { _, _ -> transport }),
            push = push,
            wallpapers = WallpaperStore(InMemoryBlobFile(), backgroundScope),
            profilesApi = ProfilesApi(http),
            sessionsApi = SessionsApi(http),
            nextPrompt = NextPromptApi(http, PluginsApi(http)),
            bots = BotsApi(connection),
        )
        return vm to store
    }

    @Test
    fun useExistingWritesOnlyTheLocalMappingAndNothingToTheGateway() = runTest {
        val transport = RecordingTransport()
        val disk = InMemoryKeyValueStore()
        val (vm, store) = viewModel(transport, disk)
        store.settings.filterNotNull().first()
        val gateway = SavedGateway(url = "https://home.example")

        vm.useAssistant(gateway, "voice")

        assertEquals("voice", vm.settings.value.assistantProfiles["https://home.example"])
        // The path's whole contract: no profiles.create, no profiles.configure (no pin), no profiles.delete —
        // no socket traffic of any kind, so the profile's toolsets and everything else stay as they are.
        assertEquals(emptyList(), transport.sent)

        // And it survives a reload, written to the settings store only.
        advanceUntilIdle()
        val reopened = SettingsStore(disk, backgroundScope)
        assertEquals(mapOf("https://home.example" to "voice"), reopened.settings.filterNotNull().first().assistantProfiles)
    }

    @Test
    fun switchingToAnotherExistingProfileReplacesOnlyThisGatewaysMapping() = runTest {
        val disk = InMemoryKeyValueStore()
        // The assistant is on with one profile, and another gateway has its own mapping.
        disk.put("settings.v1", """{"assistantProfiles":{"https://home.example":"scribe","https://work.example":"helper"}}""")
        val transport = RecordingTransport()
        val (vm, store) = viewModel(transport, disk)
        store.settings.filterNotNull().first()

        vm.useAssistant(SavedGateway(url = "https://home.example"), "voice")

        // This gateway points at the new profile; the other gateway's pick is untouched; nothing went out.
        assertEquals(
            mapOf("https://home.example" to "voice", "https://work.example" to "helper"),
            vm.settings.value.assistantProfiles,
        )
        assertEquals(emptyList(), transport.sent)
    }
}
