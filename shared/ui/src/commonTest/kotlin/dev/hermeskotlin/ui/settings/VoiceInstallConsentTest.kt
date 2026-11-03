package dev.hermeskotlin.ui.settings

import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.auth.*
import dev.hermeskotlin.core.connection.*
import dev.hermeskotlin.core.gateway.*
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.plugins.*
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.push.*
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.*
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInstallConsentTest {
    private val pin = "0123456789abcdef0123456789abcdef01234567"
    private object Keys : PushKeys {
        override val deviceId = "test"
        override fun registration(name: String): PushRegistration = error("unexpected registration")
        override fun pinned(gatewayUrl: String): PushGateway? = null
        override fun accepts(keys: PushGateway) = false
        override fun pin(gatewayUrl: String, keys: PushGateway) = Unit
        override fun retire(gatewayUrl: String) = Unit
        override fun retired(gatewayUrl: String) = emptySet<String>()
        override fun forgetRetired(gatewayUrl: String, deviceId: String) = Unit
        override fun pinnedGatewayUrl(): String? = null
    }

    private class Gateway : RpcTransport {
        val incomingFrames = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }
        override val incoming = incomingFrames.receiveAsFlow()
        val sent = mutableListOf<JsonObject>()
        var scoped = true
        var supportsPin = true
        var holdRoster = false
        var held: JsonObject? = null
        val installed = mutableSetOf<String>()
        override suspend fun send(text: String) {
            val f = Json.parseToJsonElement(text).jsonObject
            sent += f
            val method = f["method"]?.jsonPrimitive?.content
            val p = f["params"] as? JsonObject ?: return
            val action = p["action"]?.jsonPrimitive?.content
            val profile = p["profile"]?.jsonPrimitive?.content
            if (method == "profiles.list" && holdRoster) { held = f; return }
            val id = f["id"] ?: return
            if (method == "plugins.manage" && profile?.startsWith("kyllene_scope_probe") == true && scoped) {
                incomingFrames.trySend("""{"jsonrpc":"2.0","id":$id,"error":{"code":4064,"message":"profile '$profile' not found"}}""")
                return
            }
            val result = when {
                method == "profiles.list" -> """{"profiles":[{"name":"one"},{"name":"two"},{"name":"three"}]}"""
                method == "plugins.manage" && action == "install" -> {
                    if (profile == "two") """{"ok":false}""" else { installed += profile!!; """{"ok":true}""" }
                }
                method == "plugins.manage" -> {
                    val rows = mutableListOf<String>()
                    if (supportsPin && profile == "one") rows += """{"name":"herald-push","pinned_sha":"0123456789abcdef0123456789abcdef01234567"}"""
                    if (profile in installed) rows += """{"name":"kyllene_voice","pinned_sha":"0123456789abcdef0123456789abcdef01234567"}"""
                    """{"plugins":[${rows.joinToString()}]}"""
                }
                else -> return
            }
            incomingFrames.trySend("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }
        fun releaseRoster() {
            held?.let { incomingFrames.trySend("""{"jsonrpc":"2.0","id":${it["id"]},"result":{"profiles":[{"name":"old"}]}}""") }
        }
        override suspend fun close(code: Short, reason: String) { incomingFrames.close() }
        fun writes() = sent.filter { (it["params"] as? JsonObject)?.get("action")?.jsonPrimitive?.content == "install" }
    }
    private data class Fixture(val vm: SettingsViewModel, val socket: Gateway, val connection: GatewayConnection)
    private suspend fun TestScope.fixture(): Fixture {
        val socket = Gateway()
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                if (request.url.encodedPath == "/api/plugins/kyllene_voice/gpt-live/status") respond("""{"ok":true,"source":"plugin","contract":1}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                else if (request.url.encodedPath == "/api/auth/ws-ticket") respond("""{"ticket":"T","ttl_seconds":30}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                else respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }), cookies)
        val auth = AuthApi(client, cookies)
        val connection = GatewayConnection(auth, { _, _ -> socket }, backgroundScope)
        connection.start(GatewayUrl.parse("https://a.example"))
        connection.state.first { it is ConnectionState.Connected }
        val store = SettingsStore(InMemoryKeyValueStore(), backgroundScope)
        val probe = GatewayProbe(client)
        val push = PushSetup(PushApi(connection), Keys, connection, GatewayRepository(InMemoryKeyValueStore()), store, backgroundScope) { "test" }
        val vm = SettingsViewModel(store, auth, probe, ConnectionCheck(probe, auth, { _, _ -> socket }), push,
            WallpaperStore(InMemoryBlobFile(), backgroundScope), ProfilesApi(client), SessionsApi(client), NextPromptApi(client, PluginsApi(client)), connection, AudioApi(client))
        vm.voiceReleaseRevision = { pin }
        vm.bind(SavedGateway("https://a.example"))
        runCurrent()
        return Fixture(vm, socket, connection)
    }
    private fun test(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }

    @Test fun toggleChecksSelectedProfileWithoutInstalling() = test {
        val (vm, socket) = fixture()
        vm.bind(SavedGateway("https://a.example"), "one")
        vm.setSubscriptionVoice(true); runCurrent()
        assertTrue(vm.settings.value.subscriptionVoicePreview)
        assertEquals("Setup found for one. Test a call.", vm.voiceSetup.value)
        assertTrue(socket.writes().isEmpty())
        vm.setSubscriptionVoice(false); runCurrent()
        assertFalse(vm.settings.value.subscriptionVoicePreview)
        assertNull(vm.voiceSetup.value)
    }

    @Test fun enablingOpeningAndCancellingNeverWrites() = test {
        val (vm, socket) = fixture()
        vm.update { it.copy(subscriptionVoicePreview = true) }; runCurrent()
        assertTrue(vm.settings.value.subscriptionVoicePreview)
        assertTrue(socket.writes().isEmpty())
        vm.openVoiceInstall(); runCurrent()
        assertEquals(listOf("one", "two", "three"), vm.voiceInstall.value.profiles)
        assertTrue(vm.voiceInstall.value.selected.isEmpty())
        vm.confirmVoiceInstall(); vm.selectVoiceProfile("one"); vm.closeVoiceInstall(); runCurrent()
        assertFalse(vm.voiceInstall.value.visible)
        assertTrue(socket.writes().isEmpty())
    }
    @Test fun consentInstallsOnlySelectedProfileWithFixedRefAndReadback() = test {
        val (vm, socket) = fixture()
        vm.openVoiceInstall(); runCurrent()
        assertNull(vm.voiceInstall.value.error)
        vm.selectVoiceProfile("three")
        vm.selectVoiceProfile("not-in-roster")
        assertEquals(setOf("three"), vm.voiceInstall.value.selected)
        assertTrue(socket.writes().isEmpty())
        vm.confirmVoiceInstall(); runCurrent()
        assertIs<VoiceInstallResult.Installed>(vm.voiceInstall.value.results?.get("three"))
        assertEquals(setOf("three"), vm.voiceInstall.value.results?.keys)
        val params = socket.writes().single()["params"]!!.jsonObject
        assertEquals("three", params["profile"]!!.jsonPrimitive.content)
        assertEquals(VoicePluginInstaller.SOURCE, params["identifier"]!!.jsonPrimitive.content)
        assertEquals(pin, params["ref"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), params["force"])
        assertEquals(JsonPrimitive(true), params["enable"])
        assertEquals(setOf("three"), socket.installed)
    }
    @Test fun partialFailureReportsUnknownWithoutHidingSuccess() = test {
        val (vm, socket) = fixture()
        vm.openVoiceInstall(); runCurrent()
        vm.selectVoiceProfile("one"); vm.selectVoiceProfile("two")
        vm.confirmVoiceInstall(); runCurrent()
        assertIs<VoiceInstallResult.Installed>(vm.voiceInstall.value.results?.get("one"))
        assertIs<VoiceInstallResult.Unknown>(vm.voiceInstall.value.results?.get("two"))
        assertEquals(setOf("one", "two"), socket.writes().map { it["params"]!!.jsonObject["profile"]!!.jsonPrimitive.content }.toSet())
        assertEquals(setOf("one"), socket.installed)
    }
    @Test fun stalePickerAfterGatewaySwitchOrDisconnectCannotWrite() = test {
        val (vm, socket, connection) = fixture()
        vm.openVoiceInstall(); runCurrent()
        vm.selectVoiceProfile("one")
        vm.bind(SavedGateway("https://b.example"))
        vm.confirmVoiceInstall(); runCurrent()
        assertNotNull(vm.voiceInstall.value.error)
        assertTrue(socket.writes().isEmpty())
        vm.closeVoiceInstall()
        vm.bind(SavedGateway("https://a.example"))
        vm.openVoiceInstall(); runCurrent()
        vm.selectVoiceProfile("one")
        connection.stop(); runCurrent()
        vm.confirmVoiceInstall(); runCurrent()
        assertNotNull(vm.voiceInstall.value.error)
        assertTrue(socket.writes().isEmpty())
    }
    @Test fun missingPinnedPushEvidenceBlocksInstall() = test {
        val (vm, socket) = fixture()
        // A successful scope probe without pinned Push evidence is insufficient.
        socket.supportsPin = false
        vm.openVoiceInstall(); runCurrent()
        assertTrue(socket.sent.any { (it["params"] as? JsonObject)?.get("profile")?.jsonPrimitive?.content?.startsWith("kyllene_scope_probe") == true })
        assertNotNull(vm.voiceInstall.value.error)
        vm.selectVoiceProfile("one"); vm.confirmVoiceInstall(); runCurrent()
        assertTrue(vm.voiceInstall.value.selected.isEmpty())
        assertTrue(socket.writes().isEmpty())
    }
    @Test fun invalidPinAndUnscopedGatewayBlockWrite() = test {
        val (vm, socket) = fixture()
        vm.voiceReleaseRevision = { "main" }; vm.openVoiceInstall(); runCurrent()
        assertTrue(vm.voiceInstall.value.error!!.contains("revision")); vm.confirmVoiceInstall()
        assertTrue(socket.sent.none { it["method"]?.jsonPrimitive?.content == "profiles.list" })
        vm.closeVoiceInstall(); vm.voiceReleaseRevision = { pin }; socket.scoped = false
        vm.openVoiceInstall(); runCurrent()
        assertTrue(vm.voiceInstall.value.error!!.contains("profile-scoped"))
        vm.selectVoiceProfile("one"); vm.confirmVoiceInstall(); runCurrent()
        assertTrue(socket.writes().isEmpty())
    }
    @Test fun gatewaySwitchDuringFetchDoesNotShowStaleProfiles() = test {
        val (vm, socket) = fixture()
        socket.holdRoster = true; vm.openVoiceInstall(); runCurrent()
        assertNotNull(socket.held)
        vm.bind(SavedGateway("https://b.example")); socket.releaseRoster(); runCurrent()
        assertTrue(vm.voiceInstall.value.profiles.isEmpty())
        assertNotNull(vm.voiceInstall.value.error)
        vm.selectVoiceProfile("old"); vm.confirmVoiceInstall(); runCurrent()
        assertTrue(socket.writes().isEmpty())
    }
    @Test fun cancelledPendingPickerCannotPopulateReopenedPicker() = test {
        val (vm, socket) = fixture()
        socket.holdRoster = true; vm.openVoiceInstall(); runCurrent()
        assertTrue(vm.voiceInstall.value.loading); assertNotNull(socket.held)
        vm.closeVoiceInstall(); socket.holdRoster = false
        vm.openVoiceInstall(); runCurrent()
        assertEquals(listOf("one", "two", "three"), vm.voiceInstall.value.profiles)
        socket.releaseRoster(); runCurrent()
        assertEquals(listOf("one", "two", "three"), vm.voiceInstall.value.profiles)
        assertTrue(socket.writes().isEmpty())
    }
}
