package dev.hermeskotlin.ui.calls

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.*
import dev.hermeskotlin.core.wear.WatchOutbox
import dev.hermeskotlin.core.wear.WatchRelay
import dev.hermeskotlin.ui.voice.VoicePhase
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneCallModelTest {
    @Test
    fun phoneVoiceResolvesOnlyVerifiedDefaultLaunchProfile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for ((active, roster, expected) in listOf(
                Triple("""{"current":"default"}""", """{"profiles":[{"name":"default","is_default":true}]}""", true),
                Triple("""{}""", """{"profiles":[{"name":"default","is_default":true}]}""", false),
                Triple("""{"current":"work"}""", """{"profiles":[{"name":"default","is_default":true}]}""", false),
                Triple("""{"current":"default"}""", """{"profiles":[]}""", false),
            )) {
                val paths = mutableListOf<String>()
                val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
                val client = createHttpClient(MockEngine { request ->
                    paths += request.url.encodedPath
                    val body = when (request.url.encodedPath) {
                        "/api/auth/ws-ticket" -> """{"ticket":"T","ttl_seconds":30}"""
                        "/api/profiles/active" -> active
                        "/api/profiles" -> roster
                        else -> error("Unexpected request: ${request.url}")
                    }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }, cookies)
                val socket = object : RpcTransport {
                    val events = Channel<String>(Channel.UNLIMITED).apply {
                        trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
                    }
                    override val incoming = events.receiveAsFlow()
                    override suspend fun send(text: String) {
                        val message = Json.parseToJsonElement(text).jsonObject
                        val id = message["id"] ?: return
                        events.trySend("""{"jsonrpc":"2.0","id":$id,"result":{"session_id":"rt9","stored_session_id":"stored-9","message_count":0,"messages":[],"info":{}}}""")
                    }
                    override suspend fun close(code: Short, reason: String) = Unit
                }
                val auth = AuthApi(client, cookies)
                val connection = GatewayConnection(auth, { _, _ -> socket }, backgroundScope)
                val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
                connection.start(gateway)
                connection.state.first { it is ConnectionState.Connected }
                val sessions = SessionsApi(client)
                val audio = AudioApi(client)
                val settings = SettingsStore(InMemoryKeyValueStore(), backgroundScope)
                val bots = BotsApi(connection)
                val relay = WatchRelay(GatewayRepository(InMemoryKeyValueStore()), auth, ProfileStore(InMemoryKeyValueStore()), connection, sessions, audio, ChatHost(connection, sessions, backgroundScope), backgroundScope, object : WatchOutbox {
                    override suspend fun send(node: String, path: String, data: ByteArray) = true
                    override suspend fun stream(node: String, path: String, data: ByteArray) = true
                }, bots, BotChats(bots), settings)
                var created = 0
                val model = PhoneCallModel(relay, settings, audio, object : VoiceRecorder {
                    override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit) = Recording(ByteArray(0), "audio/wav", false)
                    override fun finish() = Unit
                }, object : SpeechPlayer { override suspend fun play(audio: SpokenAudio) = Unit }, backgroundScope,
                    object : VoiceKeepAlive {
                        override fun hold(onEnd: () -> Unit, onLost: () -> Unit) = true
                        override fun release() = Unit
                    }, LiveCalls { created++; null }, DeviceLock.None, ProfilesApi(client))
                val session = ChatSession(gateway, null, null, connection, sessions, backgroundScope)
                session.start()
                model.voice.startChat(session, gateway, null, subscriptionPreview = true)
                val result = model.voice.chat.first { it.phase == VoicePhase.Off && it.error != null }
                assertEquals(if (expected) 1 else 0, created, active)
                assertTrue(result.error!!.contains(if (expected) "WebRTC is unavailable" else "could not verify the launch profile"), result.error)
                assertTrue(paths.none { it.contains("kyllene") || it.contains("/api/audio/") })
                model.voice.stopChat()
                relay.close()
                client.close()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
