package dev.hermeskotlin.ui.inbox

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.cron.CronApi
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.inbox.Follow
import dev.hermeskotlin.core.inbox.InboxApi
import dev.hermeskotlin.core.inbox.InboxRun
import dev.hermeskotlin.core.inbox.InboxStore
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.core.push.PushGateway
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushRegistration
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val one = SavedGateway("https://one.example.ts.net")
    private val two = SavedGateway("https://two.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private object Keys : PushKeys {
        override val deviceId = "device-1"
        override fun registration(name: String): PushRegistration = error("not registering in tests")
        override fun pinned(gatewayUrl: String): PushGateway? = null
        override fun accepts(keys: PushGateway) = false
        override fun pin(gatewayUrl: String, keys: PushGateway) = Unit
        override fun retire(gatewayUrl: String) = Unit
        override fun retired(gatewayUrl: String) = emptySet<String>()
        override fun forgetRetired(gatewayUrl: String, deviceId: String) = Unit
        override fun pinnedGatewayUrl(): String? = null
    }

    /**
     * A gateway socket whose herald-push plugin prints what [plugin] says for each `cron` argument (`inbox {…}`,
     * `follow {…}` …). Every argument is kept in [sent]; each answer comes on its own, so one held doesn't hold
     * the rest.
     */
    private class PluginGateway(private val scope: CoroutineScope, private val plugin: suspend (String) -> String) : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }
        val sent = mutableListOf<String>()

        override val incoming: Flow<String> = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            val id = message["id"] ?: return
            val arg = message["params"]?.jsonObject?.get("arg")?.jsonPrimitive?.content.orEmpty().removePrefix("cron ")
            sent += arg
            scope.launch {
                val output = JsonPrimitive(plugin(arg)).toString()
                inbound.send("""{"jsonrpc":"2.0","id":$id,"result":{"type":"plugin","output":$output}}""")
            }
        }

        override suspend fun close(code: Short, reason: String) {
            inbound.close()
        }

        fun sent(op: String) = sent.filter { it.startsWith("$op ") }
    }

    private val disk = InMemoryKeyValueStore()
    private val store = InboxStore(disk, dispatcher)

    private suspend fun TestScope.viewModel(socket: PluginGateway): InboxViewModel {
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { request ->
            if (request.url.encodedPath == "/api/cron/jobs") respond("""[{"id":"a","name":"A"},{"id":"b","name":"B"}]""", HttpStatusCode.OK, json)
            else respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
        }
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine(config), cookies)
        val connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> socket }, backgroundScope)
        connection.start(one.gatewayUrl)
        connection.state.first { it is ConnectionState.Connected }
        val settings = SettingsStore(InMemoryKeyValueStore(), backgroundScope)
        settings.settings.first { it != null }
        settings.update { it.copy(pushAnywhere = true) }
        val pushApi = PushApi(connection)
        val push = PushSetup(pushApi, Keys, connection, GatewayRepository(InMemoryKeyValueStore()), settings, backgroundScope) { "Test" }
        return InboxViewModel(InboxApi(pushApi, Keys), store, CronApi(client), push, connection, settings)
    }

    private fun run(job: String, at: Long) =
        InboxRun(bot = "default", job = job, run = "2026-10-12_00-00-${(at % 60).toString().padStart(2, '0')}", at = at, text = "Report $at")

    private fun runsJson(vararg runs: InboxRun) = runs.joinToString(",", "[", "]") {
        """{"bot":"${it.bot}","job":"${it.job}","run":"${it.run}","label":"${it.job}","status":"ok","at":${it.at},"text":"${it.text}"}"""
    }

    private fun body(arg: String) = Json.parseToJsonElement(arg.substringAfter(' ')).jsonObject

    @Test
    fun anotherGatewayStartsCleanAndTheLastOnesAnswerIsDropped() = runTest(dispatcher) {
        val held = CompletableDeferred<Unit>()
        var lists = 0
        val socket = PluginGateway(CoroutineScope(dispatcher)) { arg ->
            when {
                // Only the first gateway's list is held; the second's comes back empty at once.
                arg.startsWith("inbox") && lists++ == 0 -> {
                    held.await()
                    """{"ok":true,"runs":${runsJson(run("a", 50))}}"""
                }
                arg.startsWith("inbox") -> """{"ok":true,"runs":[]}"""
                else -> """{"ok":true,"jobs":{"a":"notify"},"failures":false,"default":"off"}"""
            }
        }
        val vm = viewModel(socket)
        vm.bind(one)
        vm.setFilter(InboxFilter.Job("default", "a"))
        vm.chooseJobs()
        assertTrue(vm.state.value.followingLoaded)

        // The first gateway's list is still on its way when the app moves to another.
        vm.bind(two)
        assertEquals(InboxFilter.All, vm.state.value.filter)
        assertFalse(vm.state.value.choosing)
        assertFalse(vm.state.value.followingLoaded)
        assertEquals(emptyList(), vm.state.value.jobs)
        held.complete(Unit)
        runCurrent()
        assertEquals(emptyList(), vm.state.value.runs)
        assertEquals(two.gatewayUrl.value, store.bound)
        assertEquals(emptyList(), store.state.value.runs)
    }

    @Test
    fun nothingIsSavedUntilWhatThePhoneFollowsHasBeenRead() = runTest(dispatcher) {
        val socket = PluginGateway(CoroutineScope(dispatcher)) { arg ->
            when {
                arg.startsWith("inbox") -> """{"ok":true,"runs":[]}"""
                arg.startsWith("following") -> """{"ok":false,"error":"busy"}"""
                else -> """{"ok":true,"jobs":{},"failures":false,"default":"quiet"}"""
            }
        }
        val vm = viewModel(socket)
        vm.bind(one)
        vm.chooseJobs()
        assertFalse(vm.state.value.followingLoaded)
        assertNotNull(vm.state.value.followingError)
        // A save replaces the whole list: from a guess it would wipe what the phone follows.
        vm.setAll(Follow.Quiet)
        assertEquals(emptyList(), socket.sent("follow"))
    }

    @Test
    fun aReadThatStartedBeforeASaveDoesntUndoIt() = runTest(dispatcher) {
        var readHeld: CompletableDeferred<Unit>? = null
        val socket = PluginGateway(CoroutineScope(dispatcher)) { arg ->
            when {
                arg.startsWith("inbox") -> """{"ok":true,"runs":[]}"""
                arg.startsWith("following") -> {
                    readHeld?.await()
                    """{"ok":true,"jobs":{},"failures":false,"default":"off"}"""
                }
                else -> """{"ok":true,"jobs":{},"failures":false,"default":"notify"}"""
            }
        }
        val vm = viewModel(socket)
        vm.bind(one)
        vm.chooseJobs()
        assertTrue(vm.state.value.followingLoaded)

        // The page opens again, its read is slow, and the user saves meanwhile.
        readHeld = CompletableDeferred()
        vm.chooseJobs()
        vm.setAll(Follow.Notify)
        assertEquals(Follow.Notify, vm.state.value.following.default)
        readHeld!!.complete(Unit)
        runCurrent()
        assertEquals(Follow.Notify, vm.state.value.following.default)
    }

    @Test
    fun aFailedSaveTakesWhatTheGatewaySaysNowRatherThanGuessing() = runTest(dispatcher) {
        var server = """{"ok":true,"jobs":{},"failures":false,"default":"off"}"""
        val socket = PluginGateway(CoroutineScope(dispatcher)) { arg ->
            when {
                arg.startsWith("inbox") -> """{"ok":true,"runs":[]}"""
                arg.startsWith("following") -> server
                else -> {
                    // Applied on the gateway, but the answer is lost.
                    server = """{"ok":true,"jobs":{},"failures":true,"default":"off"}"""
                    """{"ok":false,"error":"timed out"}"""
                }
            }
        }
        val vm = viewModel(socket)
        vm.bind(one)
        vm.chooseJobs()
        vm.setFailures(true)
        assertTrue(vm.state.value.following.failures)
        assertTrue(vm.state.value.followingLoaded)
        assertFalse(vm.state.value.saving)
        assertTrue(vm.state.value.message!!.contains("timed out"))
    }

    @Test
    fun aFailedSaveWhoseOutcomeCantBeReadLeavesThePageWaiting() = runTest(dispatcher) {
        var reads = 0
        val socket = PluginGateway(CoroutineScope(dispatcher)) { arg ->
            when {
                arg.startsWith("inbox") -> """{"ok":true,"runs":[]}"""
                arg.startsWith("following") -> if (reads++ == 0) """{"ok":true,"jobs":{},"failures":false,"default":"off"}""" else """{"ok":false,"error":"down"}"""
                else -> """{"ok":false,"error":"timed out"}"""
            }
        }
        val vm = viewModel(socket)
        vm.bind(one)
        vm.chooseJobs()
        vm.setFailures(true)
        assertFalse(vm.state.value.followingLoaded)
        assertFalse(vm.state.value.canEditFollowing)
        assertTrue(vm.state.value.followingError!!.startsWith("Couldn't tell whether that was saved."))
    }

    @Test
    fun allOnAgainstAnOldPluginKeepsTheInbox() = runTest(dispatcher) {
        val socket = PluginGateway(CoroutineScope(dispatcher)) { arg ->
            when {
                arg.startsWith("inbox") -> """{"ok":true,"runs":${runsJson(run("a", 50))}}"""
                // Plugin 0.4.0: no default, and it ignores the one sent.
                else -> """{"ok":true,"jobs":{"a":"quiet"},"failures":false}"""
            }
        }
        val vm = viewModel(socket)
        vm.bind(one)
        vm.chooseJobs()
        vm.setAll(Follow.Quiet)
        assertEquals(listOf("a"), store.state.value.runs.map { it.job })
        assertTrue(vm.state.value.message!!.contains(InboxApi.ALL_JOBS_PLUGIN_VERSION))
        // What the gateway has is read again: still just "a".
        assertEquals(Follow.Off, vm.state.value.following.default)
    }

    @Test
    fun olderReportsPageFromTheOldestTimeAndCountEachRunOnce() = runTest(dispatcher) {
        val first = (0 until InboxApi.PAGE).map { run("j$it", 1_000L - it) }
        val socket = PluginGateway(CoroutineScope(dispatcher)) { arg ->
            when {
                arg.startsWith("inbox") && body(arg)["before"] == null -> """{"ok":true,"runs":${runsJson(*first.toTypedArray())}}"""
                // Plugin 0.4.2 ends a page with every run of its last second: the oldest run comes again.
                arg.startsWith("inbox") -> """{"ok":true,"runs":${runsJson(first.last(), run("older", 100))}}"""
                else -> """{"ok":true,"jobs":{},"failures":false,"default":"quiet"}"""
            }
        }
        val vm = viewModel(socket)
        vm.bind(one)
        assertTrue(vm.state.value.more)
        vm.loadMore()
        val page = socket.sent("inbox").last()
        assertEquals(first.last().at, body(page)["before"]!!.jsonPrimitive.long)
        assertEquals(InboxApi.PAGE + 1, vm.state.value.runs.size)
        assertEquals(InboxApi.PAGE + 1, vm.state.value.runs.map { it.key }.toSet().size)
        assertFalse(vm.state.value.more)
        // Nothing more to ask for: no second call.
        vm.loadMore()
        assertEquals(2, socket.sent("inbox").size)
    }

    @Test
    fun aListThatLandsWhileTheStoreHoldsAnotherGatewayShowsNoneOfItsRuns() = runTest(dispatcher) {
        val socket = PluginGateway(CoroutineScope(dispatcher)) { """{"ok":true,"runs":${runsJson(run("a", 50))}}""" }
        val vm = viewModel(socket)
        vm.bind(one)
        assertEquals(listOf("a"), vm.state.value.runs.map { it.job })
        // The shared store moves to another gateway, whose Inbox has a run of its own.
        store.bind(two.gatewayUrl.value, now = 0)
        store.add(two.gatewayUrl.value, run("other", 60))
        vm.refresh()
        assertEquals(listOf("a"), vm.state.value.runs.map { it.job })
    }

    @Test
    fun aFollowPageThatCouldntReadReadsAgainWhenItComesIntoSight() = runTest(dispatcher) {
        var reads = 0
        val socket = PluginGateway(CoroutineScope(dispatcher)) { arg ->
            when {
                arg.startsWith("inbox") -> """{"ok":true,"runs":[]}"""
                arg.startsWith("following") -> if (reads++ == 0) """{"ok":false,"error":"busy"}""" else """{"ok":true,"jobs":{},"failures":false,"default":"quiet"}"""
                else -> """{"ok":true,"jobs":{},"failures":false,"default":"quiet"}"""
            }
        }
        val vm = viewModel(socket)
        vm.bind(one)
        vm.chooseJobs()
        assertFalse(vm.state.value.followingLoaded)
        // The drawer closed with the page waiting, and opens again: no retry tap needed.
        vm.setVisible(true)
        assertTrue(vm.state.value.followingLoaded)
        assertEquals(Follow.Quiet, vm.state.value.following.default)
    }

    @Test
    fun comingIntoSightLooksAgainAndGoingOutOfItDoesnt() = runTest(dispatcher) {
        val socket = PluginGateway(CoroutineScope(dispatcher)) { """{"ok":true,"runs":[]}""" }
        val vm = viewModel(socket)
        vm.bind(one)
        val asked = socket.sent("inbox").size
        // Coming into sight is a look; going out of it isn't.
        vm.setVisible(true)
        assertEquals(asked + 1, socket.sent("inbox").size)
        vm.setVisible(false)
        vm.setVisible(false)
        assertEquals(asked + 1, socket.sent("inbox").size)
    }
}
