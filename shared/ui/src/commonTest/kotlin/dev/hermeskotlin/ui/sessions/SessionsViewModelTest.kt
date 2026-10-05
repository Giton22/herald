package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.AttentionTracker
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.DraftStore
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.sessions.ActiveSessions
import dev.hermeskotlin.core.sessions.SeenStore
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        deleteStatus: HttpStatusCode = HttpStatusCode.OK,
        listStatus: HttpStatusCode = HttpStatusCode.OK,
        total: Int = 2,
        socket: RpcTransport? = null,
    ): SessionsViewModel {
        // On the test dispatcher, so no request is still finishing on another thread when a test ends
        // (and resuming onto Dispatchers.Main while the next test sets it).
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { request ->
            when {
                request.url.encodedPath == "/api/sessions" -> respond(
                    """{"sessions":[{"id":"a","title":"Alpha","pinned":true},{"id":"b","title":"Beta"}],"total":$total}""",
                    listStatus, json,
                )
                request.url.encodedPath == "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                request.method == HttpMethod.Delete -> respond("""{"detail":"Store is busy"}""", deleteStatus, json)
                else -> respond("""{"display_name":"Me"}""", HttpStatusCode.OK, json)
            }
        }
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine(config), cookies)
        val auth = AuthApi(client, cookies)
        val connection = GatewayConnection(auth, { _, _ -> socket ?: error("not connecting in tests") }, CoroutineScope(dispatcher))
        if (socket != null) connection.start(gateway.gatewayUrl)
        val scope = CoroutineScope(dispatcher)
        val attention = AttentionTracker(connection, ChatHost(connection, SessionsApi(client), scope), ActiveSessions(connection, scope), scope)
        return SessionsViewModel(
            SessionsApi(client), auth, connection, LastChatStore(InMemoryKeyValueStore()), ProfilesApi(client),
            attention, SeenStore(InMemoryKeyValueStore()) { 0.0 }, DraftStore(InMemoryKeyValueStore()),
        )
    }

    /** A gateway socket that says it's ready and lists [live] as live sessions (`stored id to status`). */
    private class LiveGateway(private val live: Map<String, String>) : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }

        override val incoming: Flow<String> = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            val id = message["id"] ?: return
            val result = if (message["method"]?.jsonPrimitive?.content == "session.active_list") {
                live.entries.joinToString(",", """{"sessions":[""", "]}") { (key, status) -> """{"id":"rt-$key","session_key":"$key","status":"$status"}""" }
            } else {
                "{}"
            }
            inbound.send("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }

        override suspend fun close(code: Short, reason: String) {
            inbound.close()
        }
    }

    /** Loading finishes through the HTTP client's own coroutines, so wait on state rather than the test scheduler. */
    private suspend fun SessionsViewModel.awaitLoaded() = state.first { !it.loading }

    @Test
    fun bindLoadsFirstPage() = runTest(dispatcher) {
        val vm = viewModel()
        vm.bind(gateway)
        val state = vm.awaitLoaded()
        assertEquals(listOf("a", "b"), state.sessions.map { it.id })
        assertEquals(false, state.canLoadMore)
    }

    @Test
    fun aTurnThisPhoneNeverOpenedShowsRunningButOnlyForListedChats() = runTest(dispatcher) {
        // "b" runs on another client; "elsewhere" is live in another profile and isn't in this list.
        val vm = viewModel(socket = LiveGateway(mapOf("b" to "working", "a" to "idle", "elsewhere" to "waiting")))
        vm.bind(gateway)
        vm.awaitLoaded()
        val collector = backgroundScope.launch { vm.statuses.collect {} }
        val statuses = vm.statuses.first { it.isNotEmpty() }
        assertEquals(mapOf("b" to RowStatus(running = true)), statuses)
        collector.cancel()
    }

    @Test
    fun aPageWithNothingNewStopsLoadingMore() = runTest(dispatcher) {
        val vm = viewModel(total = 5)
        vm.bind(gateway)
        assertTrue(vm.awaitLoaded().canLoadMore)

        vm.loadMore()

        val state = vm.state.first { !it.loadingMore }
        assertEquals(listOf("a", "b"), state.sessions.map { it.id })
        assertFalse(state.canLoadMore)
    }

    @Test
    fun aRefreshDuringLoadMoreClearsItsSpinner() = runTest(dispatcher) {
        val vm = viewModel(total = 5)
        vm.bind(gateway)
        vm.awaitLoaded()

        vm.loadMore()
        vm.refresh()

        assertFalse(vm.state.first { !it.refreshing }.loadingMore)
    }

    @Test
    fun failedDeleteRestoresTheRowAndExplains() = runTest(dispatcher) {
        val vm = viewModel(deleteStatus = HttpStatusCode.ServiceUnavailable)
        vm.bind(gateway)
        val beta = vm.awaitLoaded().sessions.first { it.id == "b" }

        vm.delete(beta)

        val state = vm.state.first { it.message != null }
        assertEquals(listOf("a", "b"), state.sessions.map { it.id })
        assertTrue("Store is busy" in state.message!!)
    }

    @Test
    fun successfulDeleteRemovesTheRow() = runTest(dispatcher) {
        val vm = viewModel()
        vm.bind(gateway)
        vm.delete(vm.awaitLoaded().sessions.first { it.id == "a" })
        assertEquals(listOf("b"), vm.state.value.sessions.map { it.id })
    }

    @Test
    fun unauthorizedListFlagsSessionExpired() = runTest(dispatcher) {
        val vm = viewModel(listStatus = HttpStatusCode.Unauthorized)
        vm.bind(gateway)
        assertTrue(vm.state.first { it.sessionExpired }.sessionExpired)
    }

    @Test
    fun consumingTheExpiryFlagClearsItForTheNextMount() = runTest(dispatcher) {
        val vm = viewModel(listStatus = HttpStatusCode.Unauthorized)
        vm.bind(gateway)
        assertTrue(vm.state.first { it.sessionExpired }.sessionExpired)

        vm.consumeSessionExpired()

        assertFalse(vm.state.value.sessionExpired)
    }
}
