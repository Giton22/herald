package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.json
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CallOriginTest {
    private val request = InputRequest.parse("srq-1", "clarify", Json.parseToJsonElement("""{"question":"Proceed?"}""").jsonObject)!!
    private val origin = CallOrigin(FakeGateway.URL, "work", "runtime", "srq-1", "clarify")
    private fun reply(id: String = "srq-1", method: String = "clarify", runtime: String = "runtime") =
        Json.parseToJsonElement("""{"session_id":"$runtime","open_requests":[{"id":"$id","method":"$method","params":{"session_id":"$runtime","question":"Proceed?"}}]}""").jsonObject

    @Test fun exactReplayRequired() {
        assertTrue(verifyCallRequest(origin, FakeGateway.URL, "stored", "srq-1", request, reply()))
        assertFalse(verifyCallRequest(origin.copy(profile = null), FakeGateway.URL, "stored", "srq-1", request, reply()))
        assertFalse(verifyCallRequest(origin.copy(gateway = GatewayUrl.parse("https://other.example")), FakeGateway.URL, "stored", "srq-1", request, reply()))
        assertFalse(verifyCallRequest(origin, FakeGateway.URL, "stored", "wrong", request, reply()))
        assertFalse(verifyCallRequest(origin, FakeGateway.URL, "stored", "srq-1", request, reply(id = "other")))
        assertFalse(verifyCallRequest(origin, FakeGateway.URL, "stored", "srq-1", request, reply(method = "approval")))
        assertFalse(verifyCallRequest(origin, FakeGateway.URL, "stored", "srq-1", request, reply(runtime = "other")))
        assertFalse(verifyCallRequest(origin, FakeGateway.URL, "stored", "srq-1", request, null))
        val changedQuestion = Json.parseToJsonElement("""{"session_id":"runtime","open_requests":[{"id":"srq-1","method":"clarify","params":{"session_id":"runtime","question":"Different?"}}]}""").jsonObject
        val wrongParamsRuntime = Json.parseToJsonElement("""{"session_id":"runtime","open_requests":[{"id":"srq-1","method":"clarify","params":{"session_id":"other","question":"Proceed?"}}]}""").jsonObject
        val duplicates = Json.parseToJsonElement("""{"session_id":"runtime","open_requests":[{"id":"srq-1","method":"clarify","params":{"session_id":"runtime","question":"Proceed?"}},{"id":"srq-1","method":"clarify","params":{"session_id":"runtime","question":"Proceed?"}}]}""").jsonObject
        assertFalse(verifyCallRequest(origin, FakeGateway.URL, "stored", "srq-1", request, changedQuestion))
        assertFalse(verifyCallRequest(origin, FakeGateway.URL, "stored", "srq-1", request, wrongParamsRuntime))
        assertFalse(verifyCallRequest(origin, FakeGateway.URL, "stored", "srq-1", request, duplicates))
    }

    @Test fun incomingUsesVerifiedProfileAndNormalCallUsesSelection() = runTest {
        val gateway = FakeGateway(backgroundScope).apply {
            ready = { FakeGateway.QUIET_READY }
            http = { json("""{"messages":[]}""") }
            answer = { call -> if (call.method == "session.resume") {
                if (call.param("profile") == "work" && call.param("session_id") == "stored") reply().toString() else "error:4007"
            } else "{}" }
        }
        val connection = gateway.start()
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        cookies.addCookie(Url(FakeGateway.URL.value), Cookie("test", "test"))
        val auth = AuthApi(gateway.client, cookies)
        val gateways = GatewayRepository(InMemoryKeyValueStore())
        gateways.save(SavedGateway(FakeGateway.URL.value))
        val profiles = ProfileStore(InMemoryKeyValueStore())
        profiles.set(FakeGateway.URL, "other-selected")
        val sessions = SessionsApi(gateway.client)
        val bots = BotsApi(connection)
        val relay = WatchRelay(gateways, auth, profiles, connection, sessions, AudioApi(gateway.client), ChatHost(connection, sessions, backgroundScope), backgroundScope, object : WatchOutbox {
            override suspend fun send(node: String, path: String, data: ByteArray) = true
            override suspend fun stream(node: String, path: String, data: ByteArray) = true
        }, bots, BotChats(bots), SettingsStore(InMemoryKeyValueStore(), backgroundScope))
        val result = assertIs<CallChat.Ready>(relay.chatForCall("stored", origin, true, request, "srq-1"))
        assertEquals("work", result.profile)
        assertEquals("work", result.session.profile)
        assertIs<CallChat.Failed>(relay.chatForCall("stored", origin.copy(profile = null), true, request, "srq-1"))
        assertIs<CallChat.Failed>(relay.chatForCall("stored", origin.copy(profile = "wrong"), true, request, "srq-1"))
        assertIs<CallChat.Failed>(relay.chatForCall("stored", origin.copy(gateway = GatewayUrl.parse("https://other.example")), true, request, "srq-1"))
        assertIs<CallChat.Failed>(relay.chatForCall("stored", origin, true, request, "wrong"))
        val apiCall = assertIs<CallChat.Ready>(relay.chatForCall("stored", origin, requireOrigin = false))
        assertEquals("other-selected", apiCall.profile)
        relay.close()
    }
}