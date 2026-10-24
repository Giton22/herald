package dev.hermeskotlin.ui.assistant

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The bot the assistant panel names above its replies. */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantSpeakerTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val home = SavedGateway("https://hermes.home.example")
    private val away = SavedGateway("https://hermes.away.example")

    private val store = InMemoryKeyValueStore()
    private val cookies = PersistentCookiesStorage(store)
    private val gateways = GatewayRepository(store)
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    // Only the socket's ticket is served; every other HTTP call fails.
    private val client = createHttpClient(
        MockEngine(
            MockEngineConfig().apply {
                this.dispatcher = this@AssistantSpeakerTest.dispatcher
                addHandler { request ->
                    if (request.url.encodedPath.endsWith("ws-ticket")) respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                    else respond("", HttpStatusCode.ServiceUnavailable)
                }
            },
        ),
        cookies,
    )
    private val auth = AuthApi(client, cookies)

    private object Silent : VoiceRecorder, SpeechPlayer {
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit) =
            Recording(byteArrayOf(), "audio/wav", false)

        override fun finish() = Unit

        override suspend fun play(audio: SpokenAudio) = Unit
    }

    /** A gateway socket that says it's ready and answers each request with [answer]: its result, or an error when it starts with `!`. */
    private class BotsGateway(private val scope: CoroutineScope, private val answer: (String) -> String) : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }

        val methods = mutableListOf<String>()

        override val incoming: Flow<String> = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            val id = message["id"] ?: return
            val method = message["method"]?.jsonPrimitive?.content ?: return
            methods += method
            scope.launch {
                val reply = answer(method)
                inbound.send(
                    if (reply.startsWith("!")) """{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"${reply.drop(1)}"}}"""
                    else """{"jsonrpc":"2.0","id":$id,"result":$reply}""",
                )
            }
        }

        override suspend fun close(code: Short, reason: String) {
            inbound.close()
        }
    }

    private fun TestScope.model(socket: BotsGateway): AssistantPanelModel {
        val connection = GatewayConnection(auth, { _, _ -> socket }, backgroundScope)
        return AssistantPanelModel(
            gateways, auth, ProfileStore(store), connection, SessionsApi(client), AudioApi(client), Silent, Silent, backgroundScope,
            bots = BotsApi(connection),
        )
    }

    private suspend fun signedIn(gateway: SavedGateway) =
        cookies.addCookie(Url(gateway.url + "/"), Cookie("hermes_session_at", "token", path = "/"))

    private val writer = """{"profiles":[{"name":"writer","display_name":"Quill","has_avatar":true}]}"""

    @Test
    fun aPictureThatFailedToLoadIsAskedForAgainOnTheNextCallUp() = runTest(dispatcher) {
        gateways.save(home)
        signedIn(home)
        ProfileStore(store).set(home.gatewayUrl, "writer")
        var pictureAsks = 0
        val socket = BotsGateway(backgroundScope) { method ->
            when (method) {
                "profiles.list" -> writer
                "profiles.get_asset" -> if (++pictureAsks == 1) "!busy" else """{"found":true,"data":"data:image/png;base64,AQID"}"""
                else -> "{}"
            }
        }
        val model = model(socket)

        // The bot is named at once, drawn as its shape while its picture can't be had.
        val first = model.speaker.first { it != null }
        assertEquals("Quill", first?.name)
        assertNull(first?.picture)

        model.begin(expectText = false, expectScreenshot = false)
        assertContentEquals(byteArrayOf(1, 2, 3), model.speaker.first { it?.picture != null }?.picture)

        // Once it has everything, a call-up doesn't ask again.
        model.begin(expectText = false, expectScreenshot = false)
        assertEquals(2, pictureAsks)
        model.close()
    }

    @Test
    fun aSignedOutGatewayDropsTheLastGatewaysBot() = runTest(dispatcher) {
        gateways.save(home)
        signedIn(home)
        gateways.save(away)
        gateways.select(home.url)
        ProfileStore(store).set(home.gatewayUrl, "writer")
        val socket = BotsGateway(backgroundScope) { method ->
            when (method) {
                "profiles.list" -> writer
                "profiles.get_asset" -> """{"found":false}"""
                else -> "{}"
            }
        }
        val model = model(socket)
        model.speaker.first { it != null }

        // Herald moves to a gateway it isn't signed in to.
        gateways.select(away.url)
        model.begin(expectText = false, expectScreenshot = false)
        model.phase.first { it == AssistantPhase.SignedOut }

        assertNull(model.speaker.value)
        model.close()
    }
}
