package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.network.ApiResult
import io.ktor.client.engine.mock.toByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AudioApiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")

    @Test
    fun subscriptionTransportKeepsHistoryAndNeverFallsBack() = runTest {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var valid = true
        val api = AudioApi(createHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals("alpha", request.url.parameters["profile"])
            bodies += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            val auth = if (valid) "subscription" else "api"
            respond("""{"ok":true,"auth":"$auth","source":"plugin","transport":{"sdp":"v=0 answer"}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        val history = liveHistory(listOf(LiveFragment.Speaker.User to "Hello", LiveFragment.Speaker.Assistant to "Hi"))
        assertTrue(api.startSubscriptionLive(url, "v=0 offer", history, "alpha") is ApiResult.Success)
        valid = false
        assertTrue(api.startSubscriptionLive(url, "v=0 offer", history, "alpha") is ApiResult.Failed)
        assertEquals(listOf("/api/plugins/kyllene_voice/gpt-live/session", "/api/plugins/kyllene_voice/gpt-live/session"), paths)
        assertEquals("subscription", bodies[0].getValue("billing").jsonPrimitive.content)
        assertEquals(listOf("user", "assistant"), bodies[0].getValue("history").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content })
    }

    @Test
    fun subscriptionStatusRequiresCompatibleContractAndNeverStartsCall() = runTest {
        var contract = 1
        val paths = mutableListOf<String>()
        val api = AudioApi(createHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals("alpha", request.url.parameters["profile"])
            respond("""{"ok":true,"source":"plugin","contract":$contract}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        assertTrue(api.subscriptionStatus(url, "alpha") is ApiResult.Success)
        contract = 0
        assertTrue(api.subscriptionStatus(url, "alpha") is ApiResult.Failed)
        assertEquals(listOf("/api/plugins/kyllene_voice/gpt-live/status", "/api/plugins/kyllene_voice/gpt-live/status"), paths)
    }

    @Test
    fun aSlowAnswerMayBeSilentAsLongAsItMayTake() = runTest {
        val timeouts = mutableMapOf<String, HttpTimeoutConfig?>()
        val api = AudioApi(createHttpClient(MockEngine { request ->
            timeouts[request.url.encodedPath] = request.getCapabilityOrNull(HttpTimeoutCapability)
            respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }))

        // Only the timeouts the calls ask for matter here, not what they make of the empty answer.
        runCatching { api.transcribe(url, byteArrayOf(1), "audio/wav", null) }
        runCatching { api.speak(url, "hi", null) }
        runCatching { api.startVoiceLive(url, "sdp", emptyList(), null) }
        runCatching { api.ttsLease(url, true, null) }

        val paths = listOf("/api/audio/transcribe", "/api/audio/speak", "/api/audio/voice-live/session", "/api/audio/tts-lease")
        assertEquals(paths.toSet(), timeouts.keys)
        for (path in paths) {
            val timeout = assertNotNull(timeouts[path], path)
            val request = assertNotNull(timeout.requestTimeoutMillis, path)
            val socket = assertNotNull(timeout.socketTimeoutMillis, path)
            assertTrue(socket >= request, "$path: silence cut off after $socket ms of $request ms")
        }
    }
}
