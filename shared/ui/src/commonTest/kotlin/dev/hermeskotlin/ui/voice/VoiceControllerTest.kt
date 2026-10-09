package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.settings.DictationEngine
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.DeviceDictation
import dev.hermeskotlin.core.voice.DeviceDictationUnavailable
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceControllerTest {

    private val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class FakeRecorder(private val heard: Boolean = true) : VoiceRecorder {
        var recordings = 0
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            recordings++
            onLevel(0.5f)
            if (heard) onSpeech()
            return Recording(byteArrayOf(1, 2, 3), "audio/wav", heard)
        }

        override fun finish() = Unit
    }

    private class FakePlayer : SpeechPlayer {
        override suspend fun play(audio: SpokenAudio) = Unit
    }

    private fun audio(
        transcript: String,
        requests: MutableList<HttpRequestData>,
        /** Completed by the first `tts-lease` call, which the controller fires off without waiting for. */
        leased: CompletableDeferred<Unit>? = null,
    ) = AudioApi(
        createHttpClient(
            MockEngine { request ->
                requests += request
                if (request.url.encodedPath == "/api/audio/tts-lease") leased?.complete(Unit)
                when (request.url.encodedPath) {
                    "/api/audio/transcribe" -> respond("""{"ok":true,"transcript":"$transcript"}""", HttpStatusCode.OK, json)
                    else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                }
            },
            PersistentCookiesStorage(InMemoryKeyValueStore()),
        ),
    )

    @Test
    fun dictationPutsTheTranscriptInTheComposer() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val voice = VoiceController(audio("deploy the site", requests), FakeRecorder(), FakePlayer(), backgroundScope, backgroundScope)
        val text = CompletableDeferred<String>()

        voice.startDictation(gateway, "work") { text.complete(it) }

        assertEquals("deploy the site", text.await())
        val upload = requests.single { it.url.encodedPath == "/api/audio/transcribe" }
        assertEquals("work", upload.url.parameters["profile"])
        assertEquals(DictationState(), voice.dictation.first { !it.active })
    }

    private class FakeDevice(private val available: Boolean = true, private val said: String = "open the logs") : DeviceDictation {
        var listens = 0
        val finished = CompletableDeferred<Unit>()
        var waitForFinish = false
        override fun available() = available
        override suspend fun listen(onPartial: (String) -> Unit, onLevel: (Float) -> Unit): String {
            listens++
            onLevel(0.4f)
            onPartial("open the")
            if (waitForFinish) finished.await()
            return said
        }

        override fun finish() {
            finished.complete(Unit)
        }
    }

    @Test
    fun deviceDictationWritesAsYouTalkAndNeverUploads() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val recorder = FakeRecorder()
        val device = FakeDevice()
        val voice = VoiceController(audio("unused", requests), recorder, FakePlayer(), backgroundScope, backgroundScope, deviceDictation = device)
        val partials = mutableListOf<String>()
        val text = CompletableDeferred<String>()

        voice.startDictation(gateway, "work", DictationEngine.Device, onPartial = { partials += it }) { text.complete(it) }

        assertEquals("open the logs", text.await())
        assertEquals(listOf("open the"), partials)
        assertEquals(0, recorder.recordings)
        assertTrue(requests.none { it.url.encodedPath == "/api/audio/transcribe" })
        assertEquals(DictationState(), voice.dictation.first { !it.active })
    }

    @Test
    fun deviceDictationFallsBackToTheGatewayWithoutARecognizer() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val device = FakeDevice(available = false)
        val voice = VoiceController(audio("deploy the site", requests), FakeRecorder(), FakePlayer(), backgroundScope, backgroundScope, deviceDictation = device)
        val text = CompletableDeferred<String>()

        voice.startDictation(gateway, null, DictationEngine.Device) { text.complete(it) }

        assertEquals("deploy the site", text.await())
        assertEquals(0, device.listens)
    }

    @Test
    fun aRecognizerThatCantStartHandsTheDictationToTheGateway() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val recorder = FakeRecorder()
        val device = object : DeviceDictation {
            override fun available() = true
            override suspend fun listen(onPartial: (String) -> Unit, onLevel: (Float) -> Unit): String = throw DeviceDictationUnavailable()
            override fun finish() = Unit
        }
        val voice = VoiceController(audio("deploy the site", requests), recorder, FakePlayer(), backgroundScope, backgroundScope, deviceDictation = device)
        val text = CompletableDeferred<String>()

        voice.startDictation(gateway, null, DictationEngine.Device) { text.complete(it) }

        assertEquals("deploy the site", text.await())
        assertEquals(1, recorder.recordings)
    }

    @Test
    fun stoppingWhileTheRecognizersAreTriedDoesntStartAGatewayRecording() = runTest {
        val recorder = FakeRecorder()
        val trying = CompletableDeferred<Unit>()
        val giveUp = CompletableDeferred<Unit>()
        val device = object : DeviceDictation {
            override fun available() = true
            override suspend fun listen(onPartial: (String) -> Unit, onLevel: (Float) -> Unit): String {
                trying.complete(Unit)
                giveUp.await()
                throw DeviceDictationUnavailable()
            }
            override fun finish() = Unit
        }
        val voice = VoiceController(audio("unused", mutableListOf()), recorder, FakePlayer(), backgroundScope, backgroundScope, deviceDictation = device)
        var called = false

        voice.startDictation(gateway, null, DictationEngine.Device) { called = true }
        trying.await()
        voice.finishDictation()
        giveUp.complete(Unit)

        assertEquals("Didn't catch anything.", voice.dictation.first { it.error != null }.error)
        assertEquals(0, recorder.recordings)
        assertTrue(!called)
    }

    @Test
    fun aCancelledDeviceDictationSaysSoAndSendsNothing() = runTest {
        val device = FakeDevice().apply { waitForFinish = true }
        val voice = VoiceController(audio("unused", mutableListOf()), FakeRecorder(), FakePlayer(), backgroundScope, backgroundScope, deviceDictation = device)
        val cancelled = CompletableDeferred<Unit>()
        var called = false

        voice.startDictation(gateway, null, DictationEngine.Device, onCancelled = { cancelled.complete(Unit) }) { called = true }
        voice.dictation.first { it.level > 0f }
        voice.cancelDictation()

        cancelled.await()
        assertTrue(!called)
        assertEquals(DictationState(), voice.dictation.value)
    }

    @Test
    fun gatewayDictationIgnoresTheDeviceRecognizer() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val device = FakeDevice()
        val voice = VoiceController(audio("deploy the site", requests), FakeRecorder(), FakePlayer(), backgroundScope, backgroundScope, deviceDictation = device)
        val text = CompletableDeferred<String>()

        voice.startDictation(gateway, null, DictationEngine.Gateway) { text.complete(it) }

        assertEquals("deploy the site", text.await())
        assertEquals(0, device.listens)
    }

    @Test
    fun finishingADeviceDictationStopsTheRecognizer() = runTest {
        val device = FakeDevice().apply { waitForFinish = true }
        val voice = VoiceController(audio("unused", mutableListOf()), FakeRecorder(), FakePlayer(), backgroundScope, backgroundScope, deviceDictation = device)
        val text = CompletableDeferred<String>()

        voice.startDictation(gateway, null, DictationEngine.Device) { text.complete(it) }
        voice.dictation.first { it.level > 0f }
        voice.finishDictation()

        assertEquals("open the logs", text.await())
    }

    @Test
    fun aDeviceDictationThatHeardNothingSaysSo() = runTest {
        val voice = VoiceController(
            audio("unused", mutableListOf()), FakeRecorder(), FakePlayer(), backgroundScope, backgroundScope,
            deviceDictation = FakeDevice(said = " "),
        )
        var called = false

        voice.startDictation(gateway, null, DictationEngine.Device) { called = true }

        assertEquals("Didn't catch anything.", voice.dictation.first { it.error != null }.error)
        assertTrue(!called)
    }

    @Test
    fun saying_stop_endsTheVoiceChatWithoutSendingIt() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val leased = CompletableDeferred<Unit>()
        val recorder = FakeRecorder()
        val voice = VoiceController(audio("Stop.", requests, leased), recorder, FakePlayer(), backgroundScope, backgroundScope)
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val http = createHttpClient(MockEngine { error("no REST in this test") }, cookies)
        val connection = GatewayConnection(AuthApi(http, cookies), { _, _ -> error("not connecting") }, backgroundScope)
        val session = ChatSession(gateway, null, null, connection, SessionsApi(http), backgroundScope)

        voice.startChat(session, gateway, null)

        voice.chat.first { it.phase == VoicePhase.Off }
        assertEquals(1, recorder.recordings)
        assertTrue(session.state.value.messages.isEmpty())
        // The speech engine was warmed for the chat. The lease call isn't awaited and the mock answers on
        // another thread, so wait for it in real time rather than assume it has landed.
        withContext(Dispatchers.Default) { withTimeout(5_000) { leased.await() } }
    }
}
