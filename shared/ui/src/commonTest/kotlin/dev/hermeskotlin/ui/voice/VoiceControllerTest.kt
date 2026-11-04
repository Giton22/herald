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
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import dev.hermeskotlin.core.voice.VoiceRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import dev.hermeskotlin.ui.JvmOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.hermeskotlin.ui.resources.Res
import dev.hermeskotlin.ui.resources.voice_error_call_on
import dev.hermeskotlin.ui.resources.voice_error_nothing_heard
import org.jetbrains.compose.resources.getString

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

    /** Like Android's: clears an earlier Finish as it starts, then listens 20 ms at a time until finished. */
    private class StartingRecorder : VoiceRecorder {
        var starting: () -> Unit = {}
        var recordings = 0
        var finished = false
        private var finishRequested = false

        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            recordings++
            starting()
            finishRequested = false
            // A minute of quiet frames, unless finished first.
            repeat(3_000) {
                onLevel(0f)
                if (finishRequested) {
                    finished = true
                    return Recording(byteArrayOf(), "audio/wav", false)
                }
                delay(20)
            }
            return Recording(byteArrayOf(), "audio/wav", false)
        }

        override fun finish() {
            finishRequested = true
        }
    }

    @Test
    @JvmOnly
    fun finishingBeforeTheMicrophoneOpensDoesntRecord() = runTest {
        val recorder = StartingRecorder()
        val voice = VoiceController(audio("unused", Requests()), recorder, FakePlayer(), backgroundScope, backgroundScope)

        voice.startDictation(gateway, null) { error("Nothing was said") }
        voice.finishDictation()

        assertEquals(getString(Res.string.voice_error_nothing_heard), voice.dictation.first { !it.active }.error)
        assertEquals(0, recorder.recordings)
    }

    @Test
    @JvmOnly
    fun finishingWhileTheMicrophoneStartsEndsTheRecording() = runTest {
        val recorder = StartingRecorder()
        val voice = VoiceController(audio("unused", Requests()), recorder, FakePlayer(), backgroundScope, backgroundScope)
        recorder.starting = { voice.finishDictation() }

        voice.startDictation(gateway, null) { error("Nothing was said") }

        assertEquals(getString(Res.string.voice_error_nothing_heard), voice.dictation.first { !it.active }.error)
        assertTrue(recorder.finished)
    }

    @Test
    @JvmOnly
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

        assertEquals(getString(Res.string.voice_error_nothing_heard), voice.dictation.first { it.error != null }.error)
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
    @JvmOnly
    fun aDeviceDictationThatHeardNothingSaysSo() = runTest {
        val voice = VoiceController(
            audio("unused", Requests()), FakeRecorder(), FakePlayer(), backgroundScope, backgroundScope,
            deviceDictation = FakeDevice(said = " "),
        )
        var called = false

        voice.startDictation(gateway, null, DictationEngine.Device) { called = true }

        assertEquals(getString(Res.string.voice_error_nothing_heard), voice.dictation.first { it.error != null }.error)
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
        assertEquals(VoiceHint.SendNow, hint(VoiceChatState(VoicePhase.Listening, hearing = true)))
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

    /** Plays each clip until told to finish it, or for good when [holds]; says which clips started and which were cut. */
    private class HeldPlayer(private val holds: Boolean = false) : SpeechPlayer {
        val started = Channel<String>(Channel.UNLIMITED)
        val played = mutableListOf<String>()
        var cut = 0
        override suspend fun play(audio: SpokenAudio) {
            val clip = audio.bytes.decodeToString()
            started.send(clip)
            try {
                if (holds) awaitCancellation()
                played += clip
            } catch (e: CancellationException) {
                cut++
                throw e
            }
        }
    }

    /**
     * The gateway's voice: each piece comes back as audio holding its own text, or fails as [failure] says.
     * [said] gets what each `/api/audio/speak` was asked to say.
     */
    private fun voiced(requests: MutableList<HttpRequestData>, failure: String? = null, said: MutableList<String> = mutableListOf()) = AudioApi(
        createHttpClient(
            MockEngine { request ->
                requests += request
                if (request.url.encodedPath != "/api/audio/speak") return@MockEngine respond("""{"ok":true}""", HttpStatusCode.OK, json)
                if (failure != null) return@MockEngine respond("""{"detail":"$failure"}""", HttpStatusCode.BadRequest, json)
                val text = request.body.toByteArray().decodeToString().substringAfter("\"text\":\"").substringBefore('"')
                said += text
                @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
                val data = kotlin.io.encoding.Base64.encode(text.encodeToByteArray())
                respond("""{"data_url":"data:audio/mpeg;base64,$data","mime_type":"audio/mpeg"}""", HttpStatusCode.OK, json)
            },
            PersistentCookiesStorage(InMemoryKeyValueStore()),
        ),
    )

    @Test
    fun readAloudVoicesTheReplyWithoutItsCodeThenEnds() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val said = mutableListOf<String>()
        val player = HeldPlayer()
        val voice = VoiceController(voiced(requests, said = said), FakeRecorder(), player, backgroundScope, backgroundScope)

        voice.readAloud("r1", "Built it.\n```\n./gradlew build\n```\nAll green.", gateway, "work")

        assertEquals(ReadAloudState("r1"), voice.reading.value)
        assertEquals(ReadAloudState(), voice.reading.first { it.key == null })
        assertEquals(listOf("Built it. All green."), player.played)
        val speak = requests.single { it.url.encodedPath == "/api/audio/speak" }
        assertEquals("work", speak.url.parameters["profile"])
        assertEquals(listOf("Built it. All green."), said)
    }

    /** The gateway's voice says the same text a little differently each time; read again, it's the same take. */
    @Test
    fun aReplyReadAgainPlaysTheSameVoiceWithoutAskingAgain() = runTest {
        val said = mutableListOf<String>()
        val player = HeldPlayer()
        val voice = VoiceController(voiced(mutableListOf(), said = said), FakeRecorder(), player, backgroundScope, backgroundScope)

        voice.readAloud("r1", "All green.", gateway, null)
        voice.reading.first { it.key == null }
        voice.readAloud("r1", "All green.", gateway, null)
        voice.reading.first { it.key == null }

        assertEquals(listOf("All green.", "All green."), player.played)
        assertEquals(listOf("All green."), said)
    }

    @Test
    fun aReplyStoppedAndReadAgainStartsOverWithTheSameVoice() = runTest {
        val said = mutableListOf<String>()
        val player = HeldPlayer(holds = true)
        val voice = VoiceController(voiced(mutableListOf(), said = said), FakeRecorder(), player, backgroundScope, backgroundScope)

        voice.readAloud("r1", "A long reply.", gateway, null)
        assertEquals("A long reply.", player.started.receive())
        voice.stopReading()
        voice.readAloud("r1", "A long reply.", gateway, null)

        assertEquals("A long reply.", player.started.receive())
        assertEquals(listOf("A long reply."), said)
        voice.stopReading()
    }

    @Test
    fun anotherReplyStopsTheOneBeingRead() = runTest {
        val player = HeldPlayer(holds = true)
        val voice = VoiceController(voiced(mutableListOf()), FakeRecorder(), player, backgroundScope, backgroundScope)

        voice.readAloud("r1", "The first reply.", gateway, null)
        assertEquals("The first reply.", player.started.receive())
        voice.readAloud("r2", "The second reply.", gateway, null)

        assertEquals("The second reply.", player.started.receive())
        assertEquals(1, player.cut)
        // The first one's end doesn't clear the second.
        assertEquals(ReadAloudState("r2"), voice.reading.value)
        voice.stopReading()
        assertEquals(ReadAloudState(), voice.reading.value)
    }

    @Test
    fun stopCutsTheReplyBeingRead() = runTest {
        val player = HeldPlayer(holds = true)
        val voice = VoiceController(voiced(mutableListOf()), FakeRecorder(), player, backgroundScope, backgroundScope)

        voice.readAloud("r1", "A long reply.", gateway, null)
        player.started.receive()
        voice.stopReading()

        assertNull(voice.reading.value.key)
        testScheduler.runCurrent()
        assertEquals(1, player.cut)
    }

    @Test
    fun aGatewayWithoutAVoiceSaysWhyAndShowsReadAloudAgain() = runTest {
        val player = HeldPlayer()
        val voice = VoiceController(voiced(mutableListOf(), failure = "No TTS provider is configured."), FakeRecorder(), player, backgroundScope, backgroundScope)

        voice.readAloud("r1", "Hello there.", gateway, null)

        val failed = voice.reading.first { it.key == null }
        assertEquals("No TTS provider is configured.", failed.error)
        assertTrue(player.played.isEmpty())
        voice.dismissReadAloudError()
        assertEquals(ReadAloudState(), voice.reading.value)
    }

    @Test
    fun aReplyWithNothingToSayIsntRead() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val voice = VoiceController(voiced(requests), FakeRecorder(), HeldPlayer(), backgroundScope, backgroundScope)

        voice.readAloud("r1", "```\nls -la\n```", gateway, null)

        assertEquals(ReadAloudState(), voice.reading.value)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun aVoiceChatOrDictationTakesTheSpeakerFromReadAloud() = runTest {
        val player = HeldPlayer(holds = true)
        val voice = VoiceController(voiced(mutableListOf()), ScriptedRecorder(), player, backgroundScope, backgroundScope)

        voice.readAloud("r1", "A long reply.", gateway, null)
        player.started.receive()
        voice.startDictation(gateway, null) {}
        assertNull(voice.reading.value.key)
        testScheduler.runCurrent()
        assertEquals(1, player.cut)
        // Not while the microphone is open.
        voice.readAloud("r2", "Another reply.", gateway, null)
        assertNull(voice.reading.value.key)
        voice.cancelDictation()

        voice.readAloud("r3", "A third reply.", gateway, null)
        player.started.receive()
        voice.startChat(offlineSession(), gateway, null)
        assertNull(voice.reading.value.key)
        voice.readAloud("r4", "Another reply.", gateway, null)
        assertNull(voice.reading.value.key)
        voice.stopChat()
    }

    @Test
    fun leavingTheChatOrTheAppStopsReadAloud() = runTest {
        val player = HeldPlayer(holds = true)
        val voice = VoiceController(voiced(mutableListOf()), FakeRecorder(), player, backgroundScope, backgroundScope)

        voice.readAloud("r1", "A long reply.", gateway, null)
        player.started.receive()
        voice.stopAll()
        assertNull(voice.reading.value.key)

        voice.readAloud("r2", "Another long reply.", gateway, null)
        player.started.receive()
        voice.onBackground()
        assertNull(voice.reading.value.key)
    }

    /** Another screen's chat has the microphone: dictation doesn't start under it, and says why. */
    @JvmOnly
    @Test
    fun dictationWaitsOutAnotherScreensChat() = runTest {
        val listening = ScriptedRecorder()
        val call = VoiceController(audio("unused", Requests()), listening, FakePlayer(), backgroundScope, backgroundScope)
        val recorder = FakeRecorder()
        val voice = VoiceController(audio("unused", Requests()), recorder, FakePlayer(), backgroundScope, backgroundScope)

        call.startChat(offlineSession(), gateway, null)
        listening.waiting.await()
        voice.startDictation(gateway, "work") { }

        val shown = voice.dictation.first { it.error != null || it.active }
        assertEquals(DictationState(error = getString(Res.string.voice_error_call_on)), shown)
        assertEquals(0, recorder.recordings)
        call.stopChat()
    }

    /** The one call service every screen's chat holds: up or not. */
    private class SharedKeeper : VoiceKeepAlive {
        var holding = false
        override fun hold(onEnd: () -> Unit, onLost: () -> Unit): Boolean {
            holding = true
            return true
        }

        override fun release() {
            holding = false
        }
    }

    /** A chat ended by another screen's leaves the one call service up for the newer chat, which lets it go at its end. */
    @Test
    fun aChatEndedByAnotherScreensLeavesItTheCallService() = runTest {
        val keeper = SharedKeeper()
        val first = ScriptedRecorder()
        val second = ScriptedRecorder()
        val one = VoiceController(audio("unused", Requests()), first, FakePlayer(), backgroundScope, backgroundScope, keepAlive = keeper)
        val two = VoiceController(audio("unused", Requests()), second, FakePlayer(), backgroundScope, backgroundScope, keepAlive = keeper)

        one.startChat(offlineSession(), gateway, null)
        first.waiting.await()
        two.startChat(offlineSession(), gateway, null)
        one.chat.first { it.phase == VoicePhase.Off }
        second.waiting.await()
        testScheduler.runCurrent()
        assertTrue(keeper.holding)

        two.stopChat()
        two.chat.first { it.phase == VoicePhase.Off }
        testScheduler.runCurrent()
        assertFalse(keeper.holding)
    }

    /** Listens until cancelled, then takes [stopMs] to let the microphone go. */
    private class SlowToStopRecorder(private val stopMs: Long) : VoiceRecorder {
        val listening = CompletableDeferred<Unit>()
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            listening.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { delay(stopMs) }
            }
        }

        override fun finish() = Unit
    }

    /** End while a new chat waits for the last one to let go: the chat is Off, not left on Connecting. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun endingAChatWhileItWaitsForTheLastOnePutsItOff() = runTest {
        val slow = SlowToStopRecorder(stopMs = 1_000)
        val one = VoiceController(audio("unused", Requests()), slow, FakePlayer(), backgroundScope, backgroundScope)
        val two = VoiceController(audio("unused", Requests()), ScriptedRecorder(), FakePlayer(), backgroundScope, backgroundScope)

        one.startChat(offlineSession(), gateway, null)
        slow.listening.await()
        two.startChat(offlineSession(), gateway, null)
        testScheduler.runCurrent()
        // Still waiting: the first one takes a second to let the microphone go.
        assertEquals(VoicePhase.Connecting, two.chat.value.phase)
        two.stopChat()
        testScheduler.runCurrent()

        assertEquals(VoicePhase.Off, two.chat.value.phase)
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        assertEquals(VoicePhase.Off, one.chat.value.phase)
        assertEquals(VoicePhase.Off, two.chat.value.phase)
    }

    /** The first listen holds on until cancelled and takes [stopMs] to let go; every one after it fails at once. */
    private class SlowThenBrokenRecorder(private val stopMs: Long) : VoiceRecorder {
        val listening = CompletableDeferred<Unit>()
        private var recordings = 0
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            if (recordings++ > 0) error("No microphone.")
            listening.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { delay(stopMs) }
            }
        }

        override fun finish() = Unit
    }

    /**
     * End, then Start again on the same screen while the first chat is slow to let go: the second one ending on its
     * own lets the call service go, as nothing is on any more, and the first one's end later doesn't change that.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun aChatStartedAgainThatEndsFirstStillLetsTheCallServiceGo() = runTest {
        val keeper = SharedKeeper()
        val recorder = SlowThenBrokenRecorder(stopMs = 10_000)
        val voice = VoiceController(audio("unused", Requests()), recorder, FakePlayer(), backgroundScope, backgroundScope, keepAlive = keeper)

        voice.startChat(offlineSession(), gateway, null)
        recorder.listening.await()
        voice.stopChat()
        voice.startChat(offlineSession(), gateway, null)
        assertTrue(keeper.holding)
        // Past the wait, its microphone fails and it ends, well before the first one lets go.
        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()
        assertEquals(VoicePhase.Off, voice.chat.value.phase)
        assertFalse(keeper.holding)

        testScheduler.advanceTimeBy(8_000)
        testScheduler.runCurrent()
        assertFalse(keeper.holding)
    }

    /** A chat stuck in ending doesn't keep dictation from starting once nothing is on. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun aChatStuckEndingDoesntHoldDictationUp() = runTest {
        val slow = SlowToStopRecorder(stopMs = 60_000)
        val call = VoiceController(audio("unused", Requests()), slow, FakePlayer(), backgroundScope, backgroundScope)
        val recorder = FakeRecorder()
        val voice = VoiceController(audio("deploy the site", Requests()), recorder, FakePlayer(), backgroundScope, backgroundScope)

        call.startChat(offlineSession(), gateway, null)
        slow.listening.await()
        call.stopChat()
        testScheduler.runCurrent()
        val text = CompletableDeferred<String>()
        voice.startDictation(gateway, "work") { text.complete(it) }

        assertEquals("deploy the site", text.await())
        assertEquals(1, recorder.recordings)
        // Let it end, so it isn't left over for the next test.
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertEquals(VoicePhase.Off, call.chat.value.phase)
    }

    /** The one call service every screen's chat holds, with each hold and release in order. */
    private class LoggingKeeper : VoiceKeepAlive {
        val log = mutableListOf<String>()
        override fun hold(onEnd: () -> Unit, onLost: () -> Unit): Boolean {
            log += "hold"
            return true
        }

        override fun release() {
            log += "release"
        }
    }

    /**
     * On the main thread (Android's immediate dispatch), another screen's chat can end inside the new one's start: the
     * call service stays up across the handover, rather than being let go and held again.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun theCallServiceStaysUpThroughAHandoverThatEndsTheOtherChatAtOnce() = runTest(UnconfinedTestDispatcher()) {
        val keeper = LoggingKeeper()
        val first = ScriptedRecorder()
        val one = VoiceController(audio("unused", Requests()), first, FakePlayer(), backgroundScope, backgroundScope, keepAlive = keeper)
        val two = VoiceController(audio("unused", Requests()), ScriptedRecorder(), FakePlayer(), backgroundScope, backgroundScope, keepAlive = keeper)

        one.startChat(offlineSession(), gateway, null)
        first.waiting.await()
        two.startChat(offlineSession(), gateway, null)
        one.chat.first { it.phase == VoicePhase.Off }

        assertEquals(listOf("hold", "hold"), keeper.log)
        two.stopChat()
    }
}
