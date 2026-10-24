package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.push.PluginUpdate
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.core.push.PushGateway
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushMessage
import dev.hermeskotlin.core.push.PushRegistration
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val URL = "https://a.example"

/** The gateway's clock in these tests, epoch seconds: nowhere near the test's own (virtual) time. */
private const val GATEWAY_NOW = 1_800_000_000L

class PhoneToolsBridgeTest {

    /** A push identity, registered with [pinnedTo] when set. */
    private class Keys(var pinnedTo: String? = null) : PushKeys {
        override val deviceId = "0b6f2c1e-1111-4222-8333-944445555666"
        override fun registration(name: String) = PushRegistration(deviceId, name, "https://ntfy.sh", "hp-a", "hp-b", "E", "S")
        override fun pinned(gatewayUrl: String): PushGateway? = null
        override fun accepts(keys: PushGateway) = true
        override fun pin(gatewayUrl: String, keys: PushGateway) = Unit
        override fun retire(gatewayUrl: String) = Unit
        override fun retired(gatewayUrl: String) = emptySet<String>()
        override fun forgetRetired(gatewayUrl: String, deviceId: String) = Unit
        override fun pinnedGatewayUrl(): String? = pinnedTo
    }

    /** What the gateway's herald-push plugin answers, with its phone tool calls kept in memory. */
    private class Plugin(var installed: Boolean = true, var version: String = PushApi.PLUGIN_VERSION) {
        var botVersion: String? = null
        var botInstallFails = false
        val reports = mutableListOf<List<String>>()
        val reportBodies = mutableListOf<JsonObject>()
        val keys = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
        val waiting = mutableListOf<JsonObject>()
        val answers = mutableMapOf<String, JsonObject>()
        val installs = mutableListOf<JsonObject>()
        val heres = mutableListOf<String>()

        /** The phones it knows, in order: others the test adds, and each phone that reports. */
        val phones = mutableListOf<String>()

        /** How many `phone tools` reports to fail before taking one. */
        var failReports = 0

        /** The gateway's clock, epoch seconds, told with `pending`; null as a plugin before 0.3.3, which doesn't say it. */
        var now: Long? = GATEWAY_NOW

        fun call(id: String, tool: String, args: JsonObject = JsonObject(emptyMap()), expires: Long = GATEWAY_NOW + 120) {
            waiting += buildJsonObject {
                put("id", id)
                put("tool", tool)
                put("args", args)
                put("session", "chat-1")
                put("expires", expires)
            }
        }

        fun serve(scope: CoroutineScope, transport: FakeTransport) = scope.launch {
            var answered = 0
            transport.sent.collect { sent ->
                while (answered < sent.size) {
                    val request = sent[answered++]
                    val id = request["id"] ?: continue
                    val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
                    val reply = when ((request["method"] as JsonPrimitive).content) {
                        "profiles.list" -> if (botVersion == null) """{"profiles":[]}""" else """{"profiles":[{"name":"assistant"}]}"""
                        "plugins.manage" -> {
                            if (params["profile"] != null) {
                                if (params["action"]?.jsonPrimitive?.content == "list") {
                                    """{"plugins":[{"name":"herald-push","version":"$botVersion","source":"git","status":"enabled"}]}"""
                                } else if (botInstallFails) {
                                    "ERROR:install refused"
                                } else {
                                    botVersion = PushApi.PLUGIN_VERSION
                                    """{"ok":true}"""
                                }
                            } else {
                                installs += params
                                installed = true
                                version = PushApi.PLUGIN_VERSION
                                """{"ok":true}"""
                            }
                        }
                        "command.dispatch" -> if (!installed) null else command(params["arg"]!!.jsonPrimitive.content)
                        else -> "{}"
                    }
                    transport.push(
                        if (reply?.startsWith("ERROR:") == true) {
                            """{"jsonrpc":"2.0","id":$id,"error":{"code":5026,"message":"${reply.removePrefix("ERROR:")}"}}"""
                        } else if (reply == null) {
                            """{"jsonrpc":"2.0","id":$id,"error":{"code":4018,"message":"not a command"}}"""
                        } else {
                            """{"jsonrpc":"2.0","id":$id,"result":$reply}"""
                        },
                    )
                }
            }
        }

        private fun command(arg: String): String {
            val words = arg.split(" ", limit = 3)
            val output: JsonObject = when {
                words[0] == "info" -> buildJsonObject {
                    put("ok", true)
                    put("version", version)
                }
                words[0] == "phone" -> {
                    val body = HermesJson.parseToJsonElement(words[2]).jsonObject
                    keys += body["key"]!!.jsonPrimitive.content
                    val me = body["device_id"]!!.jsonPrimitive.content
                    ids += me
                    val listing = {
                        buildJsonObject {
                            put("ok", true)
                            put(
                                "phones",
                                JsonArray(
                                    phones.map { id ->
                                        buildJsonObject {
                                            put("id", id)
                                            put("name", if (id == me) "Pixel" else "Phone $id")
                                            put("me", id == me)
                                        }
                                    },
                                ),
                            )
                        }
                    }
                    when (words[1]) {
                        "tools" -> if (failReports > 0) {
                            failReports--
                            buildJsonObject {
                                put("ok", false)
                                put("error", "busy")
                            }
                        } else {
                            val tools = body["tools"]!!.jsonArray.map { it.jsonPrimitive.content }
                            reports += tools
                            reportBodies += body
                            if (me !in phones) phones += me
                            buildJsonObject {
                                put("ok", true)
                                put("tools", JsonArray(tools.map(::JsonPrimitive)))
                            }
                        }
                        "here" -> {
                            heres += body["sid"]!!.jsonPrimitive.content
                            buildJsonObject { put("ok", true) }
                        }
                        "list" -> listing()
                        "order" -> {
                            val first = body["ids"]!!.jsonArray.map { it.jsonPrimitive.content }
                            val rest = phones - first.toSet()
                            phones.clear()
                            phones += first + rest
                            listing()
                        }
                        "remove" -> {
                            phones -= body["id"]!!.jsonPrimitive.content
                            listing()
                        }
                        "pending" -> buildJsonObject {
                            put("ok", true)
                            put("requests", JsonArray(waiting.filter { it["id"]!!.jsonPrimitive.content !in answers }))
                            now?.let { put("now", it) }
                        }
                        else -> {
                            answers[body["id"]!!.jsonPrimitive.content] = body
                            buildJsonObject { put("ok", true) }
                        }
                    }
                }
                else -> buildJsonObject { put("ok", false) }
            }
            return HermesJson.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("type", "plugin")
                put("output", output.toString())
            })
        }
    }

    /** Answers each tool with its own name, except what [block] holds until cancelled. */
    private class Runner(val block: Set<PhoneTool> = emptySet()) : PhoneToolRunner {
        val cancelled = CompletableDeferred<PhoneTool>()
        val runs = mutableListOf<PhoneTool>()

        override suspend fun run(tool: PhoneTool, args: JsonObject, session: String): JsonElement {
            runs += tool
            if (tool in block) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(tool)
                }
            }
            if (args["fail"] != null) throw PhoneToolException("Battery is unknown.")
            return buildJsonObject { put("tool", tool.id) }
        }
    }

    private class Setup(val bridge: PhoneToolsBridge, val connection: GatewayConnection, val settings: SettingsStore, val store: KeyValueStore, val api: PushApi)

    private val transports = ArrayDeque<FakeTransport>()

    private suspend fun TestScope.setup(
        runner: PhoneToolRunner,
        on: List<PhoneTool> = emptyList(),
        all: Boolean = on.isNotEmpty(),
        keys: PushKeys = Keys(),
        pushAnywhere: Boolean = false,
        store: KeyValueStore = InMemoryKeyValueStore(),
    ): Setup {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val engine = MockEngine { respond("""{"ticket":"T","ttl_seconds":30}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        val connection = GatewayConnection(AuthApi(createHttpClient(engine, cookies), cookies), { _, _ -> transports.removeFirst() }, backgroundScope)
        val settings = SettingsStore(InMemoryKeyValueStore(), backgroundScope)
        settings.settings.filterNotNull().first()
        settings.update { it.copy(phoneTools = all, phoneToolsOn = on.map { t -> t.id }, pushAnywhere = pushAnywhere) }
        val gateways = GatewayRepository(InMemoryKeyValueStore()).apply { save(SavedGateway(URL)) }
        val api = PushApi(connection)
        val bridge = PhoneToolsBridge(
            api, keys, store, connection, gateways, settings, runner, backgroundScope, { "Pixel" }, { currentTime },
        )
        bridge.start()
        return Setup(bridge, connection, settings, store, api)
    }

    private suspend fun TestScope.connect(s: Setup, plugin: Plugin): FakeTransport {
        val transport = FakeTransport()
        transports += transport
        plugin.serve(backgroundScope, transport)
        s.connection.start(GatewayUrl.parse(URL))
        transport.push(FakeTransport.READY)
        s.connection.state.first { it is ConnectionState.Connected }
        return transport
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(60_000) { while (!condition()) delay(10) }

    private fun Plugin.error(id: String) = answers[id]?.get("error")?.jsonPrimitive?.contentOrNull

    @Test
    fun aPartialUpdateKeepsPhoneToolsWorkingAndTheFailedBotVisible() = runTest {
        for (fromNotifications in listOf(false, true)) {
            val runner = Runner()
            val s = setup(runner, on = listOf(PhoneTool.Battery))
            val plugin = Plugin(version = if (fromNotifications) "0.2.0" else "0.3.0").apply { botVersion = "0.1.1"; botInstallFails = true }
            connect(s, plugin)
            until { s.bridge.status.value.state == if (fromNotifications) PhoneToolsStatus.State.Outdated else PhoneToolsStatus.State.On }
            if (fromNotifications) s.api.prepare(install = true, update = true) else s.bridge.updatePlugin()
            until { s.bridge.status.value.detail.orEmpty().contains("assistant") }
            delay(5_000)

            assertEquals(PhoneToolsStatus.State.On, s.bridge.status.value.state)
            assertTrue(s.bridge.status.value.detail.orEmpty().contains("assistant"))
            assertEquals("0.1.1", s.bridge.status.value.update?.installed)
            plugin.call("a".repeat(32), PhoneTool.Battery.id)
            until { plugin.answers.containsKey("a".repeat(32)) }
            assertEquals(listOf(PhoneTool.Battery), runner.runs)
            s.connection.stop()
        }
    }

    @Test
    fun aPhoneThatNeverTurnedToolsOnIsNotAnnounced() = runTest {
        val s = setup(Runner())
        val plugin = Plugin()
        val transport = connect(s, plugin)
        until { s.bridge.status.value.state == PhoneToolsStatus.State.Off }
        delay(PhoneToolsBridge.POLL_MS * 2)
        assertTrue(plugin.reports.isEmpty())
        assertTrue(transport.sent.value.none { it.toString().contains("phone pending") })
    }

    @Test
    fun onlySwitchedOnToolsAreReportedAndRun() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin()
        plugin.call("a".repeat(32), PhoneTool.Battery.id)
        plugin.call("b".repeat(32), PhoneTool.Network.id)
        connect(s, plugin)
        until { plugin.answers.size == 2 }
        assertEquals(listOf(listOf(PhoneTool.Battery.id)), plugin.reports)
        assertEquals("""{"tool":"phone_battery"}""", plugin.answers["a".repeat(32)]!!["result"].toString())
        assertEquals(PhoneToolsBridge.SWITCHED_OFF, plugin.error("b".repeat(32)))
        assertEquals(PhoneToolsStatus.State.On, s.bridge.status.value.state)
        assertEquals(listOf(PhoneTool.Battery), s.bridge.recent.value.map { it.tool })
        // One key, made once and sent with every call.
        assertEquals(1, plugin.keys.size)
        assertEquals(64, plugin.keys.single().length)
    }

    @Test
    fun theSwitchForAllOfThemWins() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery), all = false)
        val plugin = Plugin()
        plugin.call("a".repeat(32), PhoneTool.Battery.id)
        connect(s, plugin)
        delay(PhoneToolsBridge.POLL_MS * 2)
        assertTrue(plugin.answers.isEmpty())
        assertTrue(plugin.reports.isEmpty())
    }

    @Test
    fun aCallStopsBeforeTheGatewaysDeadline() = runTest {
        // A question still waiting when the gateway gives up must be gone: an Allow then would change the phone unheard.
        val runner = Runner(block = setOf(PhoneTool.Battery))
        val s = setup(runner, on = listOf(PhoneTool.Battery))
        val plugin = Plugin()
        plugin.call("a".repeat(32), PhoneTool.Battery.id, expires = GATEWAY_NOW + 30)
        connect(s, plugin)
        withTimeout(60_000) { runner.cancelled.await() }
        // The whole 30 s less the margin, from when the phone saw it: not cut short, not let run past it.
        val limit = 30_000 - PhoneToolsBridge.DEADLINE_MARGIN_MS
        assertTrue(currentTime in limit..limit + PhoneToolsBridge.POLL_MS * 2, "stopped at $currentTime ms")
        until { plugin.answers.isNotEmpty() }
        assertEquals(PhoneToolsBridge.TOO_LONG, plugin.error("a".repeat(32)))
    }

    @Test
    fun aCallPastItsDeadlineDoesNothing() = runTest {
        // Seen too late (the phone was asleep): no question is shown, nothing changes.
        val runner = Runner()
        val s = setup(runner, on = listOf(PhoneTool.Battery))
        val plugin = Plugin()
        plugin.call("a".repeat(32), PhoneTool.Battery.id, expires = GATEWAY_NOW + 5)
        connect(s, plugin)
        until { plugin.answers.isNotEmpty() }
        assertEquals(PhoneToolsBridge.TOO_LONG, plugin.error("a".repeat(32)))
        assertTrue(runner.runs.isEmpty())
    }

    @Test
    fun theDeadlineIsOnTheGatewaysClockNotThePhones() = runTest {
        // The phone's clock (test time, near 1970) is far behind the gateway's: the call still gets its time.
        val runner = Runner(block = setOf(PhoneTool.Battery))
        val s = setup(runner, on = listOf(PhoneTool.Battery))
        val plugin = Plugin()
        plugin.call("a".repeat(32), PhoneTool.Battery.id, expires = GATEWAY_NOW + 120)
        connect(s, plugin)
        withTimeout(200_000) { runner.cancelled.await() }
        assertTrue(currentTime >= 120_000 - PhoneToolsBridge.DEADLINE_MARGIN_MS, "stopped at $currentTime ms")
    }

    @Test
    fun anOlderPluginThatDoesntSayItsTimeGetsThePlainLimit() = runTest {
        val runner = Runner()
        val s = setup(runner, on = listOf(PhoneTool.Battery))
        val plugin = Plugin().apply { now = null }
        // Long past by the phone's clock too; without the gateway's time it isn't compared.
        plugin.call("a".repeat(32), PhoneTool.Battery.id, expires = 5)
        connect(s, plugin)
        until { plugin.answers.isNotEmpty() }
        assertEquals(null, plugin.error("a".repeat(32)))
    }

    @Test
    fun aToolThisVersionLacksIsSaidSo() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin()
        plugin.call("a".repeat(32), "phone_teleport")
        connect(s, plugin)
        until { plugin.answers.isNotEmpty() }
        assertEquals(PhoneToolsBridge.UNKNOWN_TOOL, plugin.error("a".repeat(32)))
    }

    @Test
    fun aToolsErrorReachesTheAgent() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin()
        plugin.call("a".repeat(32), PhoneTool.Battery.id, buildJsonObject { put("fail", true) })
        connect(s, plugin)
        until { plugin.answers.isNotEmpty() }
        assertEquals("Battery is unknown.", plugin.error("a".repeat(32)))
        assertEquals("Battery is unknown.", s.bridge.recent.value.single().error)
    }

    @Test
    fun aChatsToolEventFetchesTheCallAtOnce() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Network))
        val plugin = Plugin()
        val transport = connect(s, plugin)
        until { plugin.reports.isNotEmpty() }
        delay(100)
        val asked = currentTime
        plugin.call("c".repeat(32), PhoneTool.Network.id)
        transport.push("""{"jsonrpc":"2.0","method":"event","params":{"type":"tool.start","session_id":"chat-1","payload":{"tool_id":"t1","name":"phone_network"}}}""")
        until { plugin.answers.isNotEmpty() }
        assertTrue(currentTime - asked < PhoneToolsBridge.POLL_MS, "answered after ${currentTime - asked} ms")
    }

    @Test
    fun turningAToolOffStopsAndRefusesItsCall() = runTest {
        val runner = Runner(block = setOf(PhoneTool.Battery))
        val s = setup(runner, on = listOf(PhoneTool.Battery, PhoneTool.Network))
        val plugin = Plugin()
        plugin.call("a".repeat(32), PhoneTool.Battery.id)
        connect(s, plugin)
        until { plugin.reports.isNotEmpty() }
        delay(100)
        s.settings.update { it.copy(phoneToolsOn = listOf(PhoneTool.Network.id)) }
        withTimeout(10_000) { assertEquals(PhoneTool.Battery, runner.cancelled.await()) }
        until { plugin.answers.isNotEmpty() }
        assertEquals(PhoneToolsBridge.SWITCHED_OFF, plugin.error("a".repeat(32)))
        until { plugin.reports.size >= 2 }
        assertEquals(listOf(PhoneTool.Network.id), plugin.reports.last())
    }

    @Test
    fun turningEverythingOffTellsTheGateway() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin()
        connect(s, plugin)
        until { plugin.reports.isNotEmpty() }
        s.settings.update { it.copy(phoneTools = false) }
        until { plugin.reports.size == 2 }
        assertEquals(emptyList(), plugin.reports.last())
        until { s.bridge.status.value.state == PhoneToolsStatus.State.Off }
    }

    @Test
    fun setUpAskedBeforeAnyToolIsOnStillInstalls() = runTest {
        // The switch for all of them comes first; every tool is still off then.
        val s = setup(Runner(), all = true)
        val plugin = Plugin(installed = false)
        connect(s, plugin)
        s.bridge.setUp()
        delay(1_000)
        assertTrue(plugin.installs.isEmpty())
        s.settings.update { it.copy(phoneToolsOn = listOf(PhoneTool.Battery.id)) }
        until { s.bridge.status.value.state == PhoneToolsStatus.State.On }
        assertEquals(1, plugin.installs.size)
    }

    @Test
    fun anOldPluginWaitsForTheUserToUpdateIt() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin(version = "0.2.0")
        connect(s, plugin)
        until { s.bridge.status.value.state == PhoneToolsStatus.State.Outdated }
        delay(PhoneToolsBridge.POLL_MS * 2)
        // Nothing installed and nothing reported: the user hasn't asked for the update.
        assertTrue(plugin.installs.isEmpty())
        assertTrue(plugin.reports.isEmpty())
        assertEquals(PluginUpdate("0.2.0", PushApi.PLUGIN_VERSION, needed = true), s.bridge.status.value.update)
        s.bridge.updatePlugin()
        until { s.bridge.status.value.state == PhoneToolsStatus.State.On }
        assertEquals(true, plugin.installs.single()["force"]?.jsonPrimitive?.content?.toBoolean())
        assertNull(s.bridge.status.value.update)
    }

    @Test
    fun aPluginWithoutThePhonesListWorksAndOffersTheUpdate() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin(version = PushApi.MIN_PHONE_PLUGIN_VERSION)
        plugin.call("e".repeat(32), PhoneTool.Battery.id)
        connect(s, plugin)
        until { plugin.answers.isNotEmpty() }
        s.bridge.refreshPhones()
        delay(1_000)
        assertTrue(plugin.installs.isEmpty())
        assertEquals(PluginUpdate(PushApi.MIN_PHONE_PLUGIN_VERSION, PushApi.PLUGIN_VERSION, needed = false), s.bridge.status.value.update)
        // Not asked for a list it can't give, and no error for it.
        assertTrue(s.bridge.phones.value.isEmpty())
        assertNull(s.bridge.status.value.detail)
    }

    @Test
    fun turningEverythingOffReachesAPluginTooOldToUse() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin()
        connect(s, plugin)
        until { plugin.reports.isNotEmpty() }
        plugin.version = "0.2.0"
        s.settings.update { it.copy(phoneToolsOn = emptyList()) }
        until { plugin.reports.last().isEmpty() }
        until { s.bridge.status.value.state == PhoneToolsStatus.State.Off }
    }

    @Test
    fun aPluginNewerThanTheReviewedOneIsLeftAlone() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin(version = "9.0.0")
        connect(s, plugin)
        until { s.bridge.status.value.state == PhoneToolsStatus.State.On }
        s.bridge.updatePlugin()
        delay(1_000)
        assertTrue(plugin.installs.isEmpty())
        assertNull(s.bridge.status.value.update)
    }

    @Test
    fun aFailedReportIsTriedAgain() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin().apply { failReports = 2 }
        plugin.call("a".repeat(32), PhoneTool.Battery.id)
        connect(s, plugin)
        until { plugin.answers.isNotEmpty() }
        assertEquals(PhoneToolsStatus.State.On, s.bridge.status.value.state)
    }

    private fun ringPush(id: String, reason: String? = "The deploy failed", expires: Long = GATEWAY_NOW + 75, ts: Long = GATEWAY_NOW) = PushMessage(
        id = "p-$id",
        ts = ts,
        type = PushMessage.PHONE_CALL,
        session = "chat-1",
        requestId = id,
        expires = expires,
        reason = reason,
        details = "Build 42",
    )

    /** Records each call's args; each rings until [ended]. */
    private class CallRunner : PhoneToolRunner {
        val calls = mutableListOf<Pair<PhoneTool, JsonObject>>()
        val ringing = CompletableDeferred<Unit>()

        override suspend fun run(tool: PhoneTool, args: JsonObject, session: String): JsonElement {
            calls += tool to args
            ringing.await()
            return buildJsonObject { put("status", "declined") }
        }
    }

    @Test
    fun aRingInAPushRingsWithoutTheGateway() = runTest {
        val runner = CallRunner()
        val s = setup(runner, on = listOf(PhoneTool.CallMe))
        s.bridge.ring(ringPush("e".repeat(32)))
        until { runner.calls.isNotEmpty() }
        assertTrue(s.connection.state.value !is ConnectionState.Connected)
        assertEquals(PhoneTool.CallMe, runner.calls.single().first)
        assertEquals("""{"reason":"The deploy failed","details":"Build 42"}""", runner.calls.single().second.toString())
    }

    @Test
    fun aCallDeclinedOffTheNetworkGetsBackOnceTheGatewayIsReachedInTime() = runTest {
        val runner = CallRunner().apply { ringing.complete(Unit) }
        val s = setup(runner, on = listOf(PhoneTool.CallMe))
        val id = "e".repeat(32)
        s.bridge.ring(ringPush(id))
        until { runner.calls.isNotEmpty() }
        // Back on the network well past the least wait, still before the gateway gives up: the answer goes through.
        delay(40_000)
        val plugin = Plugin()
        plugin.call(id, PhoneTool.CallMe.id, expires = GATEWAY_NOW + 75)
        connect(s, plugin)
        until { plugin.answers.containsKey(id) }
        assertEquals("""{"status":"declined"}""", plugin.answers[id]!!["result"].toString())
        assertEquals(1, runner.calls.size)
    }

    @Test
    fun aRingInAPushAndOnTheGatewayRingsOnceAndItsEndGoesBack() = runTest {
        val runner = CallRunner()
        val s = setup(runner, on = listOf(PhoneTool.CallMe))
        val id = "e".repeat(32)
        val plugin = Plugin()
        connect(s, plugin)
        until { plugin.reports.isNotEmpty() }
        // The gateway lists it too: it doesn't ring a second time, and the answer gets there.
        s.bridge.ring(ringPush(id))
        plugin.call(id, PhoneTool.CallMe.id, expires = GATEWAY_NOW + 75)
        s.bridge.wake()
        until { runner.calls.isNotEmpty() }
        delay(PhoneToolsBridge.EVENT_DRAINS_MS.last())
        runner.ringing.complete(Unit)
        until { plugin.answers.containsKey(id) }
        assertEquals("""{"status":"declined"}""", plugin.answers[id]!!["result"].toString())
        delay(PhoneToolsBridge.POLL_MS * 2)
        assertEquals(1, runner.calls.size)
    }

    @Test
    fun aRingPastItsTimeOrWithoutAReasonDoesntRing() = runTest {
        val runner = CallRunner()
        val s = setup(runner, on = listOf(PhoneTool.CallMe))
        s.bridge.ring(ringPush("e".repeat(32), expires = GATEWAY_NOW + 5))
        s.bridge.ring(ringPush("f".repeat(32), reason = " "))
        s.bridge.ring(ringPush("not-an-id"))
        // Read an hour after it was sent (this phone's clock is at 0): the agent gave up on it long ago.
        s.bridge.ring(ringPush("d".repeat(32), ts = -3_600, expires = -3_600 + 75))
        delay(1_000)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun aRingWithCallsOffDoesntRing() = runTest {
        val runner = CallRunner()
        val s = setup(runner, on = listOf(PhoneTool.Battery))
        s.bridge.ring(ringPush("e".repeat(32)))
        delay(1_000)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun aPushNudgeFetchesTheCall() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin()
        connect(s, plugin)
        until { plugin.reports.isNotEmpty() }
        delay(100)
        val asked = currentTime
        plugin.call("d".repeat(32), PhoneTool.Battery.id)
        s.bridge.wake()
        until { plugin.answers.isNotEmpty() }
        assertTrue(currentTime - asked < PhoneToolsBridge.POLL_MS)
    }

    @Test
    fun theIdentityIsKeptAndThePushRegistrationNamedOnlyWhenItsThisGateways() = runTest {
        val store = InMemoryKeyValueStore()
        val s = setup(Runner(), on = listOf(PhoneTool.Battery), keys = Keys(pinnedTo = URL), pushAnywhere = true, store = store)
        val plugin = Plugin()
        connect(s, plugin)
        until { plugin.reports.isNotEmpty() }
        assertEquals(Keys().deviceId, plugin.reportBodies.single()["push_id"]?.jsonPrimitive?.content)
        // Its own id, not the push one.
        assertTrue(Keys().deviceId !in plugin.ids)

        val other = setup(Runner(), on = listOf(PhoneTool.Battery), keys = Keys(pinnedTo = "https://b.example"), pushAnywhere = true)
        val second = Plugin()
        connect(other, second)
        until { second.reports.isNotEmpty() }
        assertNull(second.reportBodies.single()["push_id"])
    }

    @Test
    fun notificationsAnywhereTurnedOnLaterTellTheGatewayWhereACallCanRing() = runTest {
        val keys = Keys()
        val s = setup(Runner(), on = listOf(PhoneTool.CallMe), keys = keys)
        val plugin = Plugin()
        connect(s, plugin)
        until { plugin.reports.isNotEmpty() }
        assertNull(plugin.reportBodies.last()["push_id"])
        // Registered for push afterwards, on the same connection: the gateway hears of it without a reconnect.
        s.settings.update { it.copy(pushAnywhere = true) }
        keys.pinnedTo = URL
        s.api.registrationChanged()
        until { plugin.reportBodies.lastOrNull()?.get("push_id") != null }
        assertEquals(keys.deviceId, plugin.reportBodies.last()["push_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun everyTurnThisPhoneSendsIsToldWithItsChatConnection() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin()
        connect(s, plugin)
        val client = (s.connection.state.value as ConnectionState.Connected).client
        // Sent before the report is taken: still told, as it's the turn about to call a tool.
        client.request("prompt.submit", buildJsonObject { put("session_id", "s1") })
        until { plugin.heres == listOf("s1") }
        until { plugin.reports.isNotEmpty() }
        // Every turn: another phone may have sent on the same chat in between.
        client.request("prompt.submit", buildJsonObject { put("session_id", "s1") })
        client.request("session.resume", buildJsonObject { put("session_id", "s2") })
        client.request("prompt.btw", buildJsonObject { put("session_id", "s3") })
        until { plugin.heres.size == 3 }
        delay(1_000)
        assertEquals(listOf("s1", "s1", "s3"), plugin.heres)
    }

    @Test
    fun thePhonesAreListedReorderedAndRemoved() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin().apply { phones += listOf("old", "tablet") }
        connect(s, plugin)
        until { s.bridge.phones.value.size == 3 }
        val me = s.bridge.phones.value.single { it.me }.id
        assertEquals(listOf("old", "tablet", me), s.bridge.phones.value.map { it.id })

        s.bridge.movePhone(me, -1)
        until { plugin.phones == listOf("old", me, "tablet") }
        s.bridge.movePhone(me, -1)
        until { s.bridge.phones.value.first().id == me }
        // Already first: nothing to move, nothing sent.
        s.bridge.movePhone(me, -1)
        s.bridge.removePhone("old")
        until { s.bridge.phones.value.map { it.id } == listOf(me, "tablet") }
        assertEquals(listOf(me, "tablet"), plugin.phones)
        assertNull(s.bridge.status.value.detail)
    }

    @Test
    fun withoutThePluginItAsksToSetUpThenInstalls() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin(installed = false)
        connect(s, plugin)
        until { s.bridge.status.value.state == PhoneToolsStatus.State.NotInstalled }
        assertTrue(plugin.reports.isEmpty())
        s.bridge.setUp()
        until { s.bridge.status.value.state == PhoneToolsStatus.State.On }
        assertEquals(listOf(listOf(PhoneTool.Battery.id)), plugin.reports)
        assertNull(s.bridge.status.value.detail)
    }
}
