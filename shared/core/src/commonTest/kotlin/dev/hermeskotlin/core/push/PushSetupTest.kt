package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushSetupTest {

    private val gatewayKeys = PushGateway("gw-1", "ENC", "SIG")

    /** Keys kept in memory, with the same one-gateway rules as the Android store. */
    private class FakeKeys(var acceptsAll: Boolean = true) : PushKeys {
        var id = 1
        var pin: Pair<String, PushGateway>? = null
        val retiredIds = mutableMapOf<String, Set<String>>()
        override val deviceId: String get() = "device-$id"
        override fun registration(name: String) = PushRegistration(deviceId, name, "https://ntfy.sh", "hp-a", "hp-b", "E", "S")
        override fun pinned(gatewayUrl: String) = pin?.takeIf { it.first == gatewayUrl }?.second
        override fun accepts(keys: PushGateway) = acceptsAll
        override fun pin(gatewayUrl: String, keys: PushGateway) {
            check(acceptsAll) { "unusable keys" }
            pin = gatewayUrl to keys
        }
        override fun retire(gatewayUrl: String) {
            retiredIds[gatewayUrl] = retired(gatewayUrl) + deviceId
            id++
            pin = null
        }
        override fun retired(gatewayUrl: String) = retiredIds[gatewayUrl].orEmpty()
        override fun forgetRetired(gatewayUrl: String, deviceId: String) {
            retiredIds[gatewayUrl] = retired(gatewayUrl) - deviceId
        }
        override fun pinnedGatewayUrl() = pin?.first
    }

    /**
     * A dashboard that answers what PushApi asks. [installed] is the plugin directory, [enabled] whether it
     * is loaded (only then does `/herald-push` exist); `install` makes both true at [latest].
     */
    private inner class FakeGateway(
        var installed: Boolean,
        var enabled: Boolean = installed,
        var version: String = PushApi.PLUGIN_VERSION,
        val latest: String = PushApi.PLUGIN_VERSION,
    ) {
        val profiles = mutableMapOf<String, Copy?>()
        val failedProfiles = mutableSetOf<String>()
        val failedChecks = mutableSetOf<String>()
        var profilesSupported = true
        var profileListFails = false
        val calls = mutableListOf<JsonObject>()
        val registered = mutableSetOf<String>()

        /** When set, the next `info` waits for it: keeps PushSetup's lock busy. */
        var holdInfo: CompletableDeferred<Unit>? = null

        fun serve(scope: CoroutineScope, transport: FakeTransport) = scope.launch {
            var answered = 0
            transport.sent.collect { sent ->
                while (answered < sent.size) {
                    val request = sent[answered++]
                    val id = request["id"] ?: continue
                    calls += request
                    val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
                    val reply = when ((request["method"] as JsonPrimitive).content) {
                        "profiles.list" -> if (profileListFails) "ERROR:profiles unavailable" else if (profilesSupported) buildJsonObject {
                            put("profiles", JsonArray(profiles.keys.map { name -> buildJsonObject { put("name", name) } }))
                        }.toString() else "UNSUPPORTED"
                        "plugins.manage" -> manage(params)
                        "command.dispatch" -> if (!(installed && enabled)) null else command(params["arg"]!!.jsonPrimitive.content)
                        else -> "{}"
                    }
                    transport.push(
                        when {
                            reply == "UNSUPPORTED" -> """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"unknown method"}}"""
                            reply == null -> """{"jsonrpc":"2.0","id":$id,"error":{"code":4018,"message":"not a command"}}"""
                            reply.startsWith("ERROR:") -> """{"jsonrpc":"2.0","id":$id,"error":{"code":5026,"message":"${reply.removePrefix("ERROR:")}"}}"""
                            else -> """{"jsonrpc":"2.0","id":$id,"result":$reply}"""
                        },
                    )
                }
            }
        }

        private fun manage(params: JsonObject): String? {
            val profile = params["profile"]?.jsonPrimitive?.contentOrNull
            if (profile != null) {
                val copy = profiles[profile]
                return when (params["action"]!!.jsonPrimitive.content) {
                    "list" -> if (profile in failedChecks) "ERROR:profile unavailable" else buildJsonObject {
                        put("plugins", JsonArray(listOfNotNull(copy?.let {
                            buildJsonObject {
                                put("name", "herald-push")
                                put("version", it.version)
                                put("source", it.source)
                                put("status", if (it.enabled) "enabled" else "disabled")
                            }
                        })))
                    }.toString()
                    "install" -> if (profile in failedProfiles) "ERROR:install refused" else {
                        profiles[profile] = Copy(latest, params["enable"]!!.jsonPrimitive.booleanOrNull == true)
                        """{"ok":true}"""
                    }
                    else -> "{}"
                }
            }
            return when (params["action"]!!.jsonPrimitive.content) {
                "install" -> {
                    val force = (params["force"] as? JsonPrimitive)?.booleanOrNull == true
                    if (installed && !force) {
                        // Hermes refuses a plain install over an existing copy.
                        "ERROR:Plugin 'herald-push' already exists. Use force reinstall"
                    } else {
                        installed = true
                        enabled = true
                        version = latest
                        """{"ok":true}"""
                    }
                }
                "toggle" -> if (installed) {
                    enabled = true
                    """{"ok":true}"""
                } else {
                    """{"ok":false,"error":"not installed"}"""
                }
                else -> "{}"
            }
        }

        private suspend fun command(arg: String): String {
            val (op, rest) = arg.split(" ", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val output = when (op) {
                "info" -> {
                    holdInfo?.await()
                    holdInfo = null
                    buildJsonObject {
                        put("ok", true)
                        put("version", version)
                        put("gateway", HermesJson.encodeToJsonElement(PushGateway.serializer(), gatewayKeys))
                    }
                }
                "register" -> {
                    registered += HermesJson.decodeFromString(PushRegistration.serializer(), rest).deviceId
                    buildJsonObject {
                        put("ok", true)
                        put("version", version)
                        put("watching", true)
                        put("gateway", HermesJson.encodeToJsonElement(PushGateway.serializer(), gatewayKeys))
                    }
                }
                "unregister" -> buildJsonObject { put("ok", registered.remove(rest)) }
                else -> buildJsonObject { put("ok", false) }
            }
            return HermesJson.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("type", "plugin")
                put("output", output.toString())
            })
        }

        fun actions(): List<String> = calls.mapNotNull { call ->
            (call["params"] as? JsonObject)?.let { p -> p["action"]?.jsonPrimitive?.contentOrNull ?: p["arg"]?.jsonPrimitive?.contentOrNull?.substringBefore(' ') }
        }
    }

    private data class Copy(val version: String, val enabled: Boolean = true, val source: String = "git")

    private class Setup(val setup: PushSetup, val connection: GatewayConnection, val settings: SettingsStore, val gateways: GatewayRepository)

    private suspend fun TestScope.setup(keys: PushKeys, url: String = "https://a.example"): Setup {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val engine = MockEngine { respond("""{"ticket":"T","ttl_seconds":30}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        val connection = GatewayConnection(AuthApi(createHttpClient(engine, cookies), cookies), { _, _ -> transports.removeFirst() }, backgroundScope)
        val settings = SettingsStore(InMemoryKeyValueStore(), backgroundScope)
        settings.settings.filterNotNull().first()
        val gateways = GatewayRepository(InMemoryKeyValueStore()).apply { save(SavedGateway(url)) }
        val setup = PushSetup(PushApi(connection), keys, connection, gateways, settings, backgroundScope) { "Pixel" }
        setup.start()
        return Setup(setup, connection, settings, gateways)
    }

    private val transports = ArrayDeque<FakeTransport>()

    private suspend fun TestScope.connect(s: Setup, gateway: FakeGateway, url: String = "https://a.example"): FakeTransport {
        val transport = FakeTransport()
        transports += transport
        gateway.serve(backgroundScope, transport)
        s.connection.start(GatewayUrl.parse(url))
        transport.push(FakeTransport.READY)
        s.connection.state.first { it is ConnectionState.Connected }
        return transport
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(10) }

    @Test
    fun enableInstallsThePinnedCommitThenRegistersAndPins() = runTest {
        val keys = FakeKeys()
        val s = setup(keys)
        val gateway = FakeGateway(installed = false)
        connect(s, gateway)
        s.setup.enable()

        val install = gateway.calls.first { (it["method"] as JsonPrimitive).content == "plugins.manage" && (it["params"] as JsonObject)["action"]!!.jsonPrimitive.content == "install" }["params"] as JsonObject
        assertEquals(PushApi.PLUGIN_REF, install["ref"]!!.jsonPrimitive.content)
        assertTrue(PushApi.PLUGIN_REF.matches(Regex("^[0-9a-f]{40}$")))
        assertEquals(setOf("device-1"), gateway.registered)
        assertEquals(gatewayKeys, keys.pinned("https://a.example"))
        assertEquals(PushStatus.State.On, s.setup.status.value.state)
    }

    @Test
    fun aDisabledPluginIsSwitchedOnNotReinstalled() = runTest {
        val keys = FakeKeys()
        val s = setup(keys)
        val gateway = FakeGateway(installed = true, enabled = false)
        connect(s, gateway)
        s.setup.enable()

        assertEquals(listOf("info", "toggle", "info", "register"), gateway.actions())
        assertEquals(setOf("device-1"), gateway.registered)
        assertEquals(PushStatus.State.On, s.setup.status.value.state)
    }

    @Test
    fun anOldPluginIsBroughtToTheReviewedCommitOnlyWhenTheUserAsks() = runTest {
        val keys = FakeKeys()
        val s = setup(keys)
        val gateway = FakeGateway(installed = true, version = "0.1.0")
        connect(s, gateway)
        s.setup.enable()

        // Too old to work with, but code on the gateway changes only when the user says so.
        assertEquals(listOf("info"), gateway.actions())
        assertEquals(PushStatus.State.Outdated, s.setup.status.value.state)
        assertEquals(PluginUpdate("0.1.0", PushApi.PLUGIN_VERSION, needed = true), s.setup.status.value.update)
        assertTrue(gateway.registered.isEmpty())

        s.setup.updatePlugin()
        val reinstall = gateway.calls.first { (it["params"] as? JsonObject)?.get("action")?.jsonPrimitive?.contentOrNull == "install" }["params"] as JsonObject
        assertEquals(true, reinstall["force"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(PushApi.PLUGIN_REF, reinstall["ref"]!!.jsonPrimitive.content)
        assertEquals(setOf("device-1"), gateway.registered)
        assertEquals(PushStatus.State.On, s.setup.status.value.state)
        assertNull(s.setup.status.value.update)
    }

    @Test
    fun aWorkingButOlderPluginIsOfferedAnUpdateAndKeepsWorking() = runTest {
        val keys = FakeKeys()
        val s = setup(keys)
        val gateway = FakeGateway(installed = true, version = PushApi.MIN_PLUGIN_VERSION)
        connect(s, gateway)
        s.setup.enable()

        assertEquals(listOf("info", "register"), gateway.actions())
        assertEquals(PushStatus.State.On, s.setup.status.value.state)
        assertEquals(PluginUpdate(PushApi.MIN_PLUGIN_VERSION, PushApi.PLUGIN_VERSION, needed = false), s.setup.status.value.update)
    }

    @Test
    fun aCurrentGatewayStillOffersAnUpdateForAnOldBotCopy() = runTest {
        val s = setup(FakeKeys())
        val gateway = FakeGateway(installed = true).apply { profiles["assistant"] = Copy("0.1.1") }
        connect(s, gateway)
        s.setup.enable()

        assertEquals(PushStatus.State.On, s.setup.status.value.state)
        assertEquals("0.1.1", s.setup.status.value.update?.installed)
        assertTrue(gateway.calls.none { (it["params"] as? JsonObject)?.get("action")?.jsonPrimitive?.contentOrNull == "install" })
        s.setup.updatePlugin()
        assertEquals(PushApi.PLUGIN_VERSION, gateway.profiles["assistant"]?.version)
        assertNull(s.setup.status.value.update)
    }

    @Test
    fun updatingCopiesPreservesDisabledBotsAndSkipsMissingCurrentAndNewerCopies() = runTest {
        val s = setup(FakeKeys())
        val gateway = FakeGateway(installed = true, version = "0.1.1").apply {
            profiles["assistant"] = Copy("0.1.1", source = "user")
            profiles["disabled"] = Copy("0.1.1", enabled = false)
            profiles["missing"] = null
            profiles["current"] = Copy(PushApi.PLUGIN_VERSION)
            profiles["newer"] = Copy("9.0.0")
            profiles["packaged"] = Copy("0.1.1", source = "entrypoint")
            profiles["bundled"] = Copy("0.1.1", source = "bundled")
        }
        connect(s, gateway)
        s.setup.enable()
        s.setup.updatePlugin()

        assertEquals(PushApi.PLUGIN_VERSION, gateway.version)
        assertEquals(Copy(PushApi.PLUGIN_VERSION), gateway.profiles["assistant"])
        assertEquals(Copy(PushApi.PLUGIN_VERSION, enabled = false), gateway.profiles["disabled"])
        assertNull(gateway.profiles["missing"])
        assertEquals(Copy("9.0.0"), gateway.profiles["newer"])
        assertEquals(Copy("0.1.1", source = "entrypoint"), gateway.profiles["packaged"])
        assertEquals(Copy("0.1.1", source = "bundled"), gateway.profiles["bundled"])
        val installs = gateway.calls.mapNotNull { it["params"] as? JsonObject }.filter { it["action"]?.jsonPrimitive?.contentOrNull == "install" }
        assertEquals(3, installs.size)
        assertTrue(installs.all { it["ref"]?.jsonPrimitive?.content == PushApi.PLUGIN_REF && it["force"]?.jsonPrimitive?.booleanOrNull == true })
        assertNull(s.setup.status.value.update)
    }

    @Test
    fun aBotCopyWhoseVersionDoesNotReadAsOneIsNeverReplaced() = runTest {
        val s = setup(FakeKeys())
        val gateway = FakeGateway(installed = true).apply {
            profiles["dev"] = Copy("0.4.0-dev")
            profiles["blank"] = Copy("")
        }
        connect(s, gateway)
        s.setup.enable()

        // Maybe newer than the reviewed one: nothing is offered, and an update elsewhere doesn't touch it.
        assertNull(s.setup.status.value.update)
        s.setup.updatePlugin()
        assertEquals(Copy("0.4.0-dev"), gateway.profiles["dev"])
        assertEquals(Copy(""), gateway.profiles["blank"])
    }

    @Test
    fun theBotsAreLookedAtOncePerConnection() = runTest {
        val s = setup(FakeKeys())
        val gateway = FakeGateway(installed = true).apply { profiles["assistant"] = Copy("0.1.1") }
        connect(s, gateway)
        s.setup.enable()
        s.setup.enable()
        s.setup.enable()

        // Each look is one call per profile, under the lock every push and phone tools check waits on.
        assertEquals(1, gateway.calls.count { (it["method"] as JsonPrimitive).content == "profiles.list" })
        assertEquals("0.1.1", s.setup.status.value.update?.installed)
        // An update always looks afresh.
        s.setup.updatePlugin()
        assertEquals(PushApi.PLUGIN_VERSION, gateway.profiles["assistant"]?.version)
        assertNull(s.setup.status.value.update)
    }

    @Test
    fun aLookThatFailedSomewhereIsTriedAgainNotKept() = runTest {
        val s = setup(FakeKeys())
        val gateway = FakeGateway(installed = true).apply {
            profiles["flaky"] = Copy(PushApi.PLUGIN_VERSION)
            failedChecks += "flaky"
        }
        connect(s, gateway)
        s.setup.enable()
        assertTrue(s.setup.status.value.detail.orEmpty().contains("flaky"))

        // A timeout once isn't the gateway's state for the rest of the connection.
        gateway.failedChecks.clear()
        s.setup.enable()
        assertNull(s.setup.status.value.detail)
        assertNull(s.setup.status.value.update)
    }

    @Test
    fun aBotUpdateFailureNamesTheProfileAndDoesNotPreventOtherUpdates() = runTest {
        val s = setup(FakeKeys())
        val gateway = FakeGateway(installed = true).apply {
            profiles["unavailable"] = Copy("0.1.1")
            profiles["assistant"] = Copy("0.1.1")
            failedProfiles += "unavailable"
        }
        connect(s, gateway)
        s.setup.enable()
        s.setup.updatePlugin()

        assertEquals(PushStatus.State.On, s.setup.status.value.state)
        assertTrue(s.setup.status.value.detail.orEmpty().contains("unavailable"))
        assertEquals("0.1.1", s.setup.status.value.update?.installed)
        assertEquals("0.1.1", gateway.profiles["unavailable"]?.version)
        assertEquals(PushApi.PLUGIN_VERSION, gateway.profiles["assistant"]?.version)
        gateway.failedProfiles.clear()
        s.setup.updatePlugin()
        assertNull(s.setup.status.value.detail)
        assertNull(s.setup.status.value.update)
    }

    @Test
    fun anUnavailableBotDoesNotBlockLaunchProfileRegistration() = runTest {
        for (failRoster in listOf(false, true)) {
            val gateway = FakeGateway(installed = true).apply {
                profiles["unavailable"] = Copy("0.1.1")
                if (failRoster) profileListFails = true else failedChecks += "unavailable"
            }
            val s = setup(FakeKeys())
            connect(s, gateway)
            s.setup.enable()

            assertEquals(PushStatus.State.On, s.setup.status.value.state)
            assertEquals(setOf("device-1"), gateway.registered)
            assertTrue(s.setup.status.value.detail.orEmpty().contains("unavailable"))
            assertTrue(s.setup.status.value.update != null)
        }
    }

    @Test
    fun anUnavailableBotDoesNotHideTheRequiredLaunchProfileUpdate() = runTest {
        val gateway = FakeGateway(installed = true, version = "0.1.0").apply {
            profiles["unavailable"] = Copy("0.1.1")
            failedChecks += "unavailable"
        }
        val s = setup(FakeKeys())
        connect(s, gateway)
        s.setup.enable()

        assertEquals(PushStatus.State.Outdated, s.setup.status.value.state)
        assertTrue(s.setup.status.value.update?.needed == true)
    }

    @Test
    fun gatewaysWithoutProfilesKeepTheirExistingUpdateFlow() = runTest {
        val s = setup(FakeKeys())
        val gateway = FakeGateway(installed = true, version = "0.1.1").apply { profilesSupported = false }
        connect(s, gateway)
        s.setup.enable()
        s.setup.updatePlugin()

        assertEquals(PushApi.PLUGIN_VERSION, gateway.version)
        assertEquals(PushStatus.State.On, s.setup.status.value.state)
    }

    @Test
    fun unusableGatewayKeysAreNeverRegisteredWith() = runTest {
        val keys = FakeKeys(acceptsAll = false)
        val s = setup(keys)
        val gateway = FakeGateway(installed = true)
        connect(s, gateway)
        s.setup.enable()

        assertTrue(gateway.registered.isEmpty())
        assertNull(keys.pinnedGatewayUrl())
        assertEquals(PushStatus.State.Failed, s.setup.status.value.state)
    }

    @Test
    fun turningOffOutOfReachRetiresAndUnregistersOnTheNextConnection() = runTest {
        val keys = FakeKeys()
        val s = setup(keys)
        val gateway = FakeGateway(installed = true)
        connect(s, gateway)
        s.setup.enable()
        s.connection.stop()
        s.connection.state.first { it !is ConnectionState.Connected }

        s.setup.disable()
        assertNull(keys.pinnedGatewayUrl())
        assertEquals(setOf("device-1"), keys.retired("https://a.example"))

        connect(s, gateway)
        // The setting is off: the gateway is told to forget the old phone, and nothing registers again.
        until { keys.retired("https://a.example").isEmpty() }
        assertTrue(gateway.registered.isEmpty())
        assertTrue(gateway.actions().none { it == "register" && gateway.registered.contains("device-2") })
    }

    @Test
    fun anotherGatewayGetsAFreshIdentity() = runTest {
        val keys = FakeKeys()
        keys.pin("https://old.example", gatewayKeys)
        val s = setup(keys, url = "https://a.example")
        val gateway = FakeGateway(installed = true)
        connect(s, gateway)
        s.setup.enable()

        assertEquals(setOf("device-1"), keys.retired("https://old.example"))
        assertEquals(setOf("device-2"), gateway.registered)
        assertEquals("https://a.example", keys.pinnedGatewayUrl())
    }

    @Test
    fun signingOutForgetsThePhoneWhileTheSocketIsUp() = runTest {
        val keys = FakeKeys()
        val s = setup(keys)
        val gateway = FakeGateway(installed = true)
        connect(s, gateway)
        s.setup.enable()

        s.setup.forget()
        assertTrue(gateway.registered.isEmpty())
        assertNull(keys.pinnedGatewayUrl())
        assertTrue(keys.retired("https://a.example").isEmpty())
        // The preference survives: the next gateway signs the phone up again with fresh keys.
        assertTrue(s.settings.settings.value!!.pushAnywhere)
        assertEquals(PushStatus.State.Unknown, s.setup.status.value.state)
    }

    @Test
    fun quickOnThenOffEndsOffAndUnregistered() = runTest {
        val keys = FakeKeys()
        val s = setup(keys)
        val gateway = FakeGateway(installed = true)
        connect(s, gateway)
        // The connection's own refresh holds the lock while both taps queue behind it.
        val gate = CompletableDeferred<Unit>()
        gateway.holdInfo = gate
        s.settings.update { it.copy(pushAnywhere = true) }
        until { gateway.actions().contains("info") }
        val on = backgroundScope.launch { s.setup.enable() }
        val off = backgroundScope.launch { s.setup.disable() }
        gate.complete(Unit)
        on.join()
        off.join()

        assertFalse(s.settings.settings.value!!.pushAnywhere)
        assertTrue(gateway.registered.isEmpty())
        assertNull(keys.pinnedGatewayUrl())
        assertEquals(PushStatus.State.Off, s.setup.status.value.state)
    }
}
