package dev.hermeskotlin.ui.settings

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
import dev.hermeskotlin.core.storage.KeyValueStore
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AgentScreenInstallTest {
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
        var holdScreenList = false
        var heldScreenList: JsonObject? = null
        var holdInstall = false
        var heldInstall: JsonObject? = null
        var installPluginName = "herald-screen"
        var onInstall: () -> Unit = {}
        var screenState: String? = null
        var screenName = "herald-screen"
        var screenPin = "0123456789abcdef0123456789abcdef01234567"
        override suspend fun send(text: String) {
            val f = Json.parseToJsonElement(text).jsonObject
            sent += f
            val method = f["method"]?.jsonPrimitive?.content
            val p = f["params"] as? JsonObject ?: return
            val action = p["action"]?.jsonPrimitive?.content
            val profile = p["profile"]?.jsonPrimitive?.content
            if (method == "plugins.manage" && action == "install" && profile == null) onInstall()
            if (method == "plugins.manage" && action == "list" && profile == null && holdScreenList) { heldScreenList = f; return }
            if (method == "plugins.manage" && action == "install" && profile == null && holdInstall) { heldInstall = f; return }
            val id = f["id"] ?: return
            val result = when {
                method == "plugins.manage" && action == "install" -> {
                    screenState = "disabled"; """{"ok":true,"plugin_name":"$installPluginName","enabled":false}"""
                }
                method == "plugins.manage" && action == "toggle" && profile == null -> { screenState = "enabled"; """{"ok":true}""" }
                method == "plugins.manage" -> {
                    val rows = mutableListOf<String>()
                    if (profile == null && screenState != null) rows += """{"name":"$screenName","key":"$screenName","status":"$screenState","pinned_sha":"$screenPin"}"""
                    """{"plugins":[${rows.joinToString()}]}"""
                }
                else -> return
            }
            incomingFrames.trySend("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }
        fun releaseScreenList() {
            heldScreenList?.let { incomingFrames.trySend("""{"jsonrpc":"2.0","id":${it["id"]},"result":{"plugins":[{"name":"herald-screen","status":"enabled"}]}}""") }
        }
        fun releaseInstall() {
            heldInstall?.let { incomingFrames.trySend("""{"jsonrpc":"2.0","id":${it["id"]},"result":{"ok":true,"plugin_name":"herald-screen","enabled":false}}""") }
        }
        override suspend fun close(code: Short, reason: String) { incomingFrames.close() }
        fun writes() = sent.filter { (it["params"] as? JsonObject)?.get("action")?.jsonPrimitive?.content == "install" }
    }
    private class ConfirmingStore : KeyValueStore {
        private val memory = InMemoryKeyValueStore()
        var confirm = true
        var confirmedValue: String? = null
        override suspend fun get(key: String) = memory.get(key)
        override suspend fun put(key: String, value: String) = memory.put(key, value)
        override suspend fun remove(key: String) = memory.remove(key)
        override suspend fun putConfirmed(key: String, value: String): Boolean {
            if (!confirm) return false
            memory.put(key, value)
            confirmedValue = value
            return true
        }
    }
    private data class Fixture(val vm: SettingsViewModel, val socket: Gateway, val connection: GatewayConnection, val other: Gateway, val store: SettingsStore)
    private suspend fun TestScope.fixture(settingsBacking: ConfirmingStore = ConfirmingStore()): Fixture {
        val socket = Gateway()
        val other = Gateway()
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/api/auth/ws-ticket") respond("""{"ticket":"T","ttl_seconds":30}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            else respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
        }, cookies)
        val auth = AuthApi(client, cookies)
        val connection = GatewayConnection(auth, { url, _ -> if (url == GatewayUrl.parse("https://a.example")) socket else other }, backgroundScope)
        connection.start(GatewayUrl.parse("https://a.example"))
        connection.state.first { it is ConnectionState.Connected }
        val store = SettingsStore(settingsBacking, backgroundScope)
        val probe = GatewayProbe(client)
        val push = PushSetup(PushApi(connection), Keys, connection, GatewayRepository(InMemoryKeyValueStore()), store, backgroundScope) { "test" }
        val vm = SettingsViewModel(store, auth, probe, ConnectionCheck(probe, auth, { _, _ -> socket }), push,
            WallpaperStore(InMemoryBlobFile(), backgroundScope), ProfilesApi(client), SessionsApi(client), NextPromptApi(client, PluginsApi(client)), connection)
        vm.screenReleaseSpec = { dev.hermeskotlin.core.plugins.GitPluginSpec("herald-screen", "https://github.com/Giton22/kyllene.git#plugins/herald-screen", pin) }
        vm.bind(SavedGateway("https://a.example"))
        runCurrent()
        return Fixture(vm, socket, connection, other, store)
    }
    private fun test(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }

    private fun SettingsViewModel.prepareScreenInstall() {
        openScreenInstall()
        confirmScreenInstall()
    }

    @Test fun failedConfirmationPreventsRemoteInstall() = test {
        val backing = ConfirmingStore().apply { confirm = false }
        val f = fixture(backing)
        f.vm.prepareScreenInstall(); runCurrent()
        assertTrue(f.socket.writes().isEmpty())
        assertIs<GitInstallResult.Uncertain>(f.vm.gitInstall.value.result)
    }

    @Test fun markerIsConfirmedBeforeRemoteInstall() = test {
        val backing = ConfirmingStore()
        val f = fixture(backing)
        var observed = false
        f.socket.onInstall = {
            val marker = Json.parseToJsonElement(assertNotNull(backing.confirmedValue)).jsonObject["uncertainGitInstalls"]!!.jsonArray
            assertTrue(marker.isNotEmpty())
            observed = true
        }
        f.vm.prepareScreenInstall(); runCurrent()
        assertTrue(observed)
    }

    @Test fun lostScreenInstallReplySurvivesReconstructionUntilExplicitReset() = test {
        val backing = ConfirmingStore()
        val first = fixture(backing)
        first.socket.holdInstall = true
        first.vm.prepareScreenInstall(); runCurrent()
        assertNotNull(first.socket.heldInstall)
        assertTrue(first.vm.gitInstall.value.busy)
        assertEquals(1, first.socket.writes().size)
        val params = first.socket.writes().single()["params"]!!.jsonObject
        assertEquals(JsonPrimitive(false), params["enable"])
        assertEquals(JsonPrimitive(false), params["force"])
        assertNull(params["profile"])
        assertTrue(first.store.settings.value!!.uncertainGitInstalls.isNotEmpty())

        // A second VM loads the durable marker even while the original call is pending.
        val restored = fixture(backing)
        restored.vm.openScreenInstall(); runCurrent()
        assertIs<GitInstallResult.Uncertain>(restored.vm.gitInstall.value.result)
        restored.vm.confirmScreenInstall(); restored.vm.closeScreenInstall()
        restored.vm.openScreenInstall(); restored.vm.confirmScreenInstall(); runCurrent()
        assertIs<GitInstallResult.Uncertain>(restored.vm.gitInstall.value.result)
        assertTrue(restored.socket.writes().isEmpty())

        advanceTimeBy(120_001); runCurrent()
        assertIs<GitInstallResult.Uncertain>(first.vm.gitInstall.value.result)
        first.vm.closeScreenInstall(); first.vm.openScreenInstall(); first.vm.confirmScreenInstall(); runCurrent()
        assertIs<GitInstallResult.Uncertain>(first.vm.gitInstall.value.result)
        assertEquals(1, first.socket.writes().size)

        // Reset is a deliberate acknowledgement, not a retry.
        restored.vm.resetScreenInstallWarning(); runCurrent()
        assertFalse(restored.vm.gitInstall.value.visible)
        assertTrue(restored.store.settings.value!!.uncertainGitInstalls.isEmpty())
        restored.vm.openScreenInstall(); runCurrent()
        assertNull(restored.vm.gitInstall.value.result)
        assertTrue(restored.socket.writes().isEmpty())
    }

    @Test fun staleInstallReplyAfterGatewayAndClientSwitchCannotChangeNewSheet() = test {
        val backing = ConfirmingStore()
        val f = fixture(backing)
        f.socket.holdInstall = true
        f.vm.prepareScreenInstall(); runCurrent()
        assertNotNull(f.socket.heldInstall)
        val oldKey = f.store.settings.value!!.uncertainGitInstalls.single()
        f.vm.bind(SavedGateway("https://b.example"))
        f.vm.closeScreenInstall(); f.vm.openScreenInstall(); runCurrent()
        val before = f.vm.gitInstall.value
        assertFalse(before.busy)
        assertEquals("https://b.example", before.gateway)
        f.socket.releaseInstall(); runCurrent()
        assertEquals(before, f.vm.gitInstall.value)
        assertTrue(oldKey in f.store.settings.value!!.uncertainGitInstalls)
        f.connection.start(GatewayUrl.parse("https://b.example")); runCurrent()
        assertIs<ConnectionState.Connected>(f.connection.state.value)
        f.vm.closeScreenInstall(); f.vm.openScreenInstall(); runCurrent()
        assertFalse(f.vm.gitInstall.value.busy)
        assertNull(f.vm.gitInstall.value.result)
        assertTrue(f.other.writes().isEmpty())
        val reconstructed = SettingsStore(backing, backgroundScope)
        runCurrent()
        // The old gateway marker remains associated with the old target, not the new sheet.
        assertTrue(oldKey in reconstructed.settings.value!!.uncertainGitInstalls)
    }

    @Test fun differentManifestIdentityAfterWriteIsUncertainAndCannotEnable() = test {
        val f = fixture()
        f.socket.installPluginName = "another-plugin"
        f.vm.prepareScreenInstall(); runCurrent()
        assertIs<GitInstallResult.Uncertain>(f.vm.gitInstall.value.result)
        assertEquals(1, f.socket.writes().size)
        assertTrue(f.socket.sent.none { (it["params"] as? JsonObject)?.get("action")?.jsonPrimitive?.content == "toggle" })
        assertTrue(f.store.settings.value!!.uncertainGitInstalls.isNotEmpty())
    }

    @Test fun replacingClientDuringPendingInstallLeavesNewGatewayUsable() = test {
        val backing = ConfirmingStore()
        val f = fixture(backing)
        f.socket.holdInstall = true
        f.vm.prepareScreenInstall(); runCurrent()
        assertNotNull(f.socket.heldInstall)
        val oldClient = (f.connection.state.value as ConnectionState.Connected).client
        val oldKey = f.store.settings.value!!.uncertainGitInstalls.single()
        f.vm.bind(SavedGateway("https://b.example"))
        f.connection.start(GatewayUrl.parse("https://b.example"))
        f.vm.closeScreenInstall()
        runCurrent()
        val newClient = (f.connection.state.value as ConnectionState.Connected).client
        assertNotSame(oldClient, newClient)
        f.vm.openScreenInstall(); runCurrent()
        val newSheet = f.vm.gitInstall.value
        assertFalse(newSheet.busy)
        assertNull(newSheet.result)
        // The old request completes as the old client closes; it must not overwrite this sheet.
        advanceTimeBy(120_001); runCurrent()
        assertEquals(newSheet, f.vm.gitInstall.value)
        val reloaded = SettingsStore(backing, backgroundScope)
        runCurrent()
        assertTrue(oldKey in reloaded.settings.value!!.uncertainGitInstalls)
        assertEquals(1, f.socket.writes().size)
        assertTrue(f.other.writes().isEmpty())
    }

    @Test fun refreshRequestsFreshStatusAndRejectsOlderResponse() = test {
        val (vm, socket) = fixture()
        assertEquals(ScreenPluginStatus.Missing, vm.screenStatus.value)
        socket.holdScreenList = true
        vm.refreshScreenStatus(); runCurrent()
        assertEquals(ScreenPluginStatus.Checking, vm.screenStatus.value)
        socket.holdScreenList = false
        vm.refreshScreenStatus(); runCurrent()
        assertEquals(ScreenPluginStatus.Missing, vm.screenStatus.value)
        socket.releaseScreenList(); runCurrent()
        assertEquals(ScreenPluginStatus.Missing, vm.screenStatus.value)
    }

    @Test fun staleScreenListCannotSurviveConnectionChange() = test {
        val (vm, socket, connection) = fixture()
        socket.holdScreenList = true
        vm.bind(SavedGateway("https://b.example")); runCurrent()
        assertEquals(ScreenPluginStatus.Unknown, vm.screenStatus.value)
        vm.bind(SavedGateway("https://a.example")); runCurrent()
        assertEquals(ScreenPluginStatus.Checking, vm.screenStatus.value)
        vm.bind(SavedGateway("https://b.example")); runCurrent()
        assertEquals(ScreenPluginStatus.Unknown, vm.screenStatus.value)
        socket.releaseScreenList(); runCurrent()
        assertEquals(ScreenPluginStatus.Unknown, vm.screenStatus.value)
        connection.stop(); runCurrent()
        assertEquals(ScreenPluginStatus.Unknown, vm.screenStatus.value)
    }

    @Test fun configuredScreenOneClickInstallsAndEnables() = test {
        val (vm, socket) = fixture()
        vm.openScreenInstall(); vm.confirmScreenInstall(); runCurrent()
        val writes = socket.sent.mapNotNull { it["params"] as? JsonObject }.filter { it["action"]?.jsonPrimitive?.content in listOf("install", "toggle") }
        assertEquals(listOf("install", "toggle"), writes.map { it["action"]!!.jsonPrimitive.content })
        assertEquals("https://github.com/Giton22/kyllene.git#plugins/herald-screen", writes[0]["identifier"]!!.jsonPrimitive.content)
        assertEquals(pin, writes[0]["ref"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), writes[0]["force"])
        assertEquals(JsonPrimitive(false), writes[0]["enable"])
        assertEquals(JsonPrimitive(true), writes[1]["enable"])
        assertIs<GitInstallResult.Reported>(vm.gitInstall.value.result)
    }
    @Test fun unconfiguredScreenReleaseNeverWrites() = test {
        val (vm, socket) = fixture()
        vm.screenReleaseSpec = { null }
        val before = socket.sent.size
        vm.openScreenInstall(); vm.confirmScreenInstall(); runCurrent()
        assertIs<GitInstallResult.Refused>(vm.gitInstall.value.result)
        assertEquals(before, socket.sent.size)
        assertTrue(socket.writes().isEmpty())
    }
    @Test fun existingCaseVariantAndWrongPinAreNeverReplacedOrEnabled() = test {
        for ((name, revision) in listOf("Herald-Screen" to pin, "herald-screen" to "ffffffffffffffffffffffffffffffffffffffff")) {
            val f = fixture()
            f.socket.screenState = "disabled"
            f.socket.screenName = name
            f.socket.screenPin = revision
            f.vm.prepareScreenInstall(); runCurrent()
            assertIs<GitInstallResult.Existing>(f.vm.gitInstall.value.result)
            assertTrue(f.socket.sent.none {
                (it["params"] as? JsonObject)?.get("action")?.jsonPrimitive?.content in listOf("install", "toggle")
            })
        }
    }

    @Test fun postInstallWrongPinIsUncertainAndNeverEnabled() = test {
        val f = fixture()
        f.socket.screenPin = "ffffffffffffffffffffffffffffffffffffffff"
        f.vm.prepareScreenInstall(); runCurrent()
        assertIs<GitInstallResult.Uncertain>(f.vm.gitInstall.value.result)
        assertEquals(1, f.socket.writes().size)
        assertTrue(f.socket.sent.none { (it["params"] as? JsonObject)?.get("action")?.jsonPrimitive?.content == "toggle" })
        assertTrue(f.store.settings.value!!.uncertainGitInstalls.isNotEmpty())
    }

    @Test fun clipboardGateRemainsDisabledUntilScreenIsEnabled() = test {
        val f = fixture()
        f.socket.holdScreenList = true
        f.vm.refreshScreenStatus(); runCurrent()
        assertEquals(ScreenPluginStatus.Checking, f.vm.screenStatus.value)
        assertFalse(f.vm.settings.value.screenClipboardAccess)
        assertFalse(canChangeScreenClipboard(false, f.vm.screenStatus.value))
        f.socket.holdScreenList = false
        f.vm.refreshScreenStatus(); runCurrent()
        assertEquals(ScreenPluginStatus.Missing, f.vm.screenStatus.value)
        assertFalse(canChangeScreenClipboard(false, f.vm.screenStatus.value))
    }

    @Test fun changedScreenTargetNeverWrites() = test {
        val (vm, socket) = fixture()
        vm.openScreenInstall()
        vm.bind(SavedGateway("https://b.example"))
        vm.confirmScreenInstall(); runCurrent()
        assertIs<GitInstallResult.Refused>(vm.gitInstall.value.result)
        assertTrue(socket.writes().isEmpty())
    }

}
