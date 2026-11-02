package dev.hermeskotlin.ui.screen

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pane, and the gateway it is waiting for.
 *
 * The gateway may take its time, and the user may not: a pane that is gone when the answer arrives must
 * not open a screen behind the user's back, and a bot the user has left must not go on asking the gateway
 * about its screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://one.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** A gateway socket that keeps every call, and answers only what the test tells it to. */
    private class ScreenGateway : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }
        val sent = mutableListOf<String>()

        override val incoming: Flow<String> = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            sent += text
        }

        override suspend fun close(code: Short, reason: String) {
            inbound.close()
        }

        /** The calls the app made to [method]. */
        fun calls(method: String) = sent.filter { it.contains("\"$method\"") }

        /** Answers the last call to [method] with [result]. */
        suspend fun answer(method: String, result: String) {
            val id = calls(method).last().let { Json.parseToJsonElement(it).jsonObject["id"] }
            inbound.send("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }

        /** The gateway says something about a screen. */
        suspend fun event(type: String, payload: String) {
            inbound.send(
                """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","payload":$payload}}""",
            )
        }
    }

    private suspend fun TestScope.viewModel(socket: ScreenGateway): ScreenViewModel {
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { request ->
            if (request.url.encodedPath == "/api/auth/ws-ticket") {
                respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
            } else {
                respond("""{}""", HttpStatusCode.OK, json)
            }
        }
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine(config), cookies)
        val connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> socket }, backgroundScope)
        connection.start(gateway.gatewayUrl)
        connection.state.first { it is ConnectionState.Connected }
        return ScreenViewModel(connection)
    }

    @Test
    fun aPaneThatClosedWhileTheGatewayThoughtOpensNothing() = runTest {
        val socket = ScreenGateway()
        val model = viewModel(socket)

        model.bind(gateway, "scribe")
        // The gateway has not answered yet: the pane is waiting for it.
        assertEquals(1, socket.calls("display.status").size)

        model.close()

        // The answer arrives late: the pane that asked for it is gone.
        socket.answer(
            "display.status",
            """{"profile":"scribe","supported":true,"installed":true,"running":true}""",
        )
        runCurrent()

        // Nothing was opened for nobody.
        assertEquals(emptyList(), socket.calls("display.observe"))
        assertNull(model.state.value.streamUrl)
    }

    @Test
    fun aBotTheUserHasLeftAsksNothingMore() = runTest {
        val socket = ScreenGateway()
        val model = viewModel(socket)

        model.bind(gateway, "scribe")
        model.close()

        // Another bot: the first one's session is done with, and its watchers stop with it.
        model.bind(gateway, "other")
        val asked = socket.calls("display.status").size

        // The gateway says the first bot's screen moved on: nobody is watching that one any more.
        socket.event("display.status", """{"profile_key":"scribe"}""")
        runCurrent()

        assertEquals(asked, socket.calls("display.status").size)
    }
}
