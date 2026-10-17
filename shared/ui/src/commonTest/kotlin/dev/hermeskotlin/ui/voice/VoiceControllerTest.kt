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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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

    /**
     * What the fake gateway was asked. Its engine adds to it on another thread (the `tts-lease` call isn't waited
     * on) while the test reads it, so each read sees a whole list rather than one being added to.
     */
    @OptIn(ExperimentalAtomicApi::class)
    private class Requests : AbstractList<HttpRequestData>() {
        private val seen = AtomicReference(emptyList<HttpRequestData>())

        fun add(request: HttpRequestData) {
            while (true) {
                val now = seen.load()
                if (seen.compareAndSet(now, now + request)) return
            }
        }

        override val size: Int get() = seen.load().size

        override fun get(index: Int): HttpRequestData = seen.load()[index]
    }

    private fun audio(
        transcript: String,
        requests: Requests,
        /** Completed by the first `tts-lease` call, which the controller fires off without waiting for. */
        leased: CompletableDeferred<Unit>? = null,
        /** How many transcriptions fail before they work again. */
        transcribeFailures: Int = 0,
    ) = AudioApi(
        createHttpClient(
            MockEngine { request ->
                requests.add(request)
                if (request.url.encodedPath == "/api/audio/tts-lease") leased?.complete(Unit)
                val transcribes = requests.count { it.url.encodedPath == "/api/audio/transcribe" }
                when (request.url.encodedPath) {
                    "/api/audio/transcribe" -> if (transcribes <= transcribeFailures) {
                        respond("""{"ok":false,"error":"Speech-to-text is down."}""", HttpStatusCode.InternalServerError, json)
                    } else {
                        respond("""{"ok":true,"transcript":"$transcript"}""", HttpStatusCode.OK, json)
                    }
                    else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                }
            },
            PersistentCookiesStorage(InMemoryKeyValueStore()),
        ),
    )

    @Test
    fun dictationPutsTheTranscriptInTheComposer() = runTest {
        val requests = Requests()
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
        val requests = Requests()
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
        val requests = Requests()
        val device = FakeDevice(available = false)
        val voice = VoiceController(audio("deploy the site", requests), FakeRecorder(), FakePlayer(), backgroundScope, backgroundScope, deviceDictation = device)
        val text = CompletableDeferred<String>()

        voice.startDictation(gateway, null, DictationEngine.Device) { text.complete(it) }

        assertEquals("deploy the site", text.await())
        assertEquals(0, device.listens)
    }

    @Test
    fun aRecognizerThatCantStartHandsTheDictationToTheGateway() = runTest {
        val requests = Requests()
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
        val voice = VoiceController(audio("unused", Requests()), recorder, FakePlayer(), backgroundScope, backgroundScope, deviceDictation = device)
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
        val voice = VoiceController(audio("unused", Requests()), FakeRecorder(), FakePlayer(), backgroundScope, backgroundScope, deviceDictation = device)
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
        val requests = Requests()
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
        val voice = VoiceController(audio("unused", Requests()), FakeRecorder(), FakePlayer(), backgroundScope, backgroundScope, deviceDictation = device)
        val text = CompletableDeferred<String>()

        voice.startDictation(gateway, null, DictationEngine.Device) { text.complete(it) }
        voice.dictation.first { it.level > 0f }
        voice.finishDictation()

        assertEquals("open the logs", text.await())
    }

    @Test
    fun aDeviceDictationThatHeardNothingSaysSo() = runTest {
        val voice = VoiceController(
            audio("unused", Requests()), FakeRecorder(), FakePlayer(), backgroundScope, backgroundScope,
            deviceDictation = FakeDevice(said = " "),
        )
        var called = false

        voice.startDictation(gateway, null, DictationEngine.Device) { called = true }

        assertEquals("Didn't catch anything.", voice.dictation.first { it.error != null }.error)
        assertTrue(!called)
    }

    @Test
    fun saying_stop_endsTheVoiceChatWithoutSendingIt() = runTest {
        val requests = Requests()
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

    /** Hears you, then never finds the pause (a car, wind) until it's told to finish. */
    private class NoPauseRecorder : VoiceRecorder {
        var finishes = 0
        private val finished = CompletableDeferred<Unit>()
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            onSpeech()
            finished.await()
            return Recording(byteArrayOf(1, 2, 3), "audio/wav", heardSpeech = true)
        }

        override fun finish() {
            finishes++
            finished.complete(Unit)
        }
    }

    /** #185: where the pause never comes, Send now ends the recording and what was said goes. */
    @Test
    fun sendNowEndsTheRecordingWithoutAPause() = runTest {
        val requests = Requests()
        val recorder = NoPauseRecorder()
        val voice = VoiceController(audio("Stop.", requests), recorder, FakePlayer(), backgroundScope, backgroundScope)
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val http = createHttpClient(MockEngine { error("no REST in this test") }, cookies)
        val connection = GatewayConnection(AuthApi(http, cookies), { _, _ -> error("not connecting") }, backgroundScope)
        val session = ChatSession(gateway, null, null, connection, SessionsApi(http), backgroundScope)

        // Not listening yet: nothing to send.
        voice.sendNow()
        assertEquals(0, recorder.finishes)

        voice.startChat(session, gateway, null)
        voice.chat.first { it.hearing }
        voice.sendNow()

        // It was transcribed ("stop"), which ends the chat.
        voice.chat.first { it.phase == VoicePhase.Off }
        assertEquals(1, recorder.finishes)
        assertTrue(requests.any { it.url.encodedPath == "/api/audio/transcribe" })
    }

    @Test
    fun sendNowShowsOnlyOnceTheChainedChatHearsYou() {
        assertTrue(canSendNow(VoiceChatState(VoicePhase.Listening, hearing = true)))
        // Nothing heard yet, a live call, or no longer listening: nothing to send.
        assertTrue(!canSendNow(VoiceChatState(VoicePhase.Listening)))
        assertTrue(!canSendNow(VoiceChatState(VoicePhase.Listening, hearing = true, live = true)))
        assertTrue(!canSendNow(VoiceChatState(VoicePhase.Transcribing, hearing = true)))
        assertEquals("Pause when you're done, or tap send", hint(VoiceChatState(VoicePhase.Listening, hearing = true)))
    }

    /** Hears speech or nothing, listen by listen as [heard] says, then waits for good. */
    private class ScriptedRecorder(private vararg val heard: Boolean) : VoiceRecorder {
        var recordings = 0
        /** Every scripted listen is done. */
        val waiting = CompletableDeferred<Unit>()
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            val speech = heard.getOrNull(recordings) ?: run {
                waiting.complete(Unit)
                awaitCancellation()
            }
            recordings++
            if (speech) onSpeech()
            return Recording(byteArrayOf(1, 2, 3), "audio/wav", speech)
        }

        override fun finish() = Unit
    }

    /** A chat that never connects: these voice chats end before anything is sent. */
    private fun TestScope.offlineSession(): ChatSession {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val http = createHttpClient(MockEngine { error("no REST in this test") }, cookies)
        val connection = GatewayConnection(AuthApi(http, cookies), { _, _ -> error("not connecting") }, backgroundScope)
        return ChatSession(gateway, null, null, connection, SessionsApi(http), backgroundScope)
    }

    /** #184: one failed turn, then a quiet room, used to end the chat after about 3 × 12 s of silence. */
    @Test
    fun quietListensAfterAnErrorDontEndTheChat() = runTest {
        val recorder = ScriptedRecorder(true, false, false, false, false)
        val voice = VoiceController(audio("unused", Requests(), transcribeFailures = 1), recorder, FakePlayer(), backgroundScope, backgroundScope)

        voice.startChat(offlineSession(), gateway, null)

        recorder.waiting.await()
        assertEquals(5, recorder.recordings)
        assertEquals(VoicePhase.Listening, voice.chat.value.phase)
        // The failure stays on show while the chat listens on.
        assertTrue(voice.chat.value.error != null)
        voice.stopChat()
    }

    @Test
    fun failedTurnsInARowStillEndTheChat() = runTest {
        // Quiet listens in between neither count nor reset the run of failures.
        val recorder = ScriptedRecorder(true, false, true, false, true, true)
        val voice = VoiceController(audio("unused", Requests(), transcribeFailures = 3), recorder, FakePlayer(), backgroundScope, backgroundScope)

        voice.startChat(offlineSession(), gateway, null)

        val ended = voice.chat.first { it.phase == VoicePhase.Off }
        assertEquals(5, recorder.recordings)
        // An ending forced by errors says why.
        assertTrue(ended.error != null)
    }
}
