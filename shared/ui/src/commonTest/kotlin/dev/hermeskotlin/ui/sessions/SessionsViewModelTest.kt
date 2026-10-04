package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.AttentionTracker
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.DraftStore
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.sessions.SeenStore
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
    ): SessionsViewModel {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/api/sessions" -> respond(
                    """{"sessions":[{"id":"a","title":"Alpha","pinned":true},{"id":"b","title":"Beta"}],"total":$total}""",
                    listStatus, json,
                )
                request.method == HttpMethod.Delete -> respond("""{"detail":"Store is busy"}""", deleteStatus, json)
                else -> respond("""{"display_name":"Me"}""", HttpStatusCode.OK, json)
            }
        }
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(engine, cookies)
        val auth = AuthApi(client, cookies)
        val connection = GatewayConnection(auth, { _, _ -> error("not connecting in tests") }, CoroutineScope(dispatcher))
        val scope = CoroutineScope(dispatcher)
        val attention = AttentionTracker(connection, ChatHost(connection, SessionsApi(client), scope), scope)
        return SessionsViewModel(
            SessionsApi(client), auth, connection, LastChatStore(InMemoryKeyValueStore()), ProfilesApi(client),
            attention, SeenStore(InMemoryKeyValueStore()) { 0.0 }, DraftStore(InMemoryKeyValueStore()),
        )
    }

    /** The mock engine completes on Ktor's own dispatcher, so wait on state rather than the test scheduler. */
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
}
