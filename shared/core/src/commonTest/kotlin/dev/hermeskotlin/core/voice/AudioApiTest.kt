package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
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
