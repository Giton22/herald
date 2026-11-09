package dev.hermeskotlin.ui.voice

import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.*
import dev.hermeskotlin.ui.resources.Res
import dev.hermeskotlin.ui.resources.voice_error_nothing_heard
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, shadows = [AndroidVoiceRecorderTest.Microphone::class, AndroidVoiceRecorderTest.Suppression::class, AndroidVoiceRecorderTest.EchoCancelling::class])
class AndroidVoiceRecorderTest {
    @Before fun reset() {
        mode = "empty"
        entered = CountDownLatch(1)
        unblock = CountDownLatch(1)
        reads.set(0)
        opened.set(0)
        released.set(0)
    }

    @After fun unblockRead() { unblock.countDown() }

    @Test fun endReleasesAStarvedMicrophone() = controller { voice, session ->
        voice.startChat(session, gateway, null)
        awaitRead()
        voice.stopChat()
        withTimeout(2_000) { voice.chat.first { it.phase == VoicePhase.Off } }
        assertEquals(1, released.get())
        assertNull(voice.chat.value.error)
    }

    @Test fun finishEndsAnEmptyGatewayDictation() = controller { voice, _ ->
        voice.startDictation(gateway, null) { error("No speech was recorded") }
        awaitRead()
        voice.finishDictation()
        withTimeout(2_000) { voice.dictation.first { !it.active } }
        assertEquals(1, released.get())
        assertEquals(getString(Res.string.voice_error_nothing_heard), voice.dictation.value.error)
    }

    @Test fun muteDropsTheRecordingAndUnmuteCanListenAgain() = controller { voice, session ->
        voice.startChat(session, gateway, null)
        awaitRead()
        voice.toggleMute()
        withTimeout(2_000) { while (released.get() == 0) delay(10) }
        val previousReads = reads.get()
        voice.toggleMute()
        withTimeout(2_000) { while (opened.get() < 2 || reads.get() <= previousReads) delay(10) }
        voice.stopChat()
        withTimeout(2_000) { voice.chat.first { it.phase == VoicePhase.Off } }
        assertEquals(2, released.get())
    }

    @Test fun aNewCallKeepsItsStateWhileThePreviousRecordingEnds() = controller { voice, session ->
        voice.startChat(session, gateway, null)
        awaitRead()
        voice.stopChat()
        voice.startChat(session, gateway, "other-profile")
        withTimeout(2_000) { while (opened.get() < 2 || released.get() < 1) delay(10) }
        assertEquals(VoicePhase.Listening, voice.chat.value.phase)
        voice.stopChat()
        withTimeout(2_000) { voice.chat.first { it.phase == VoicePhase.Off } }
        assertEquals(2, released.get())
    }

    @Test fun readErrorsEndTheCallWithoutTranscribing() = controller { voice, session ->
        mode = "error"
        voice.startChat(session, gateway, null)
        withTimeout(2_000) { voice.chat.first { it.error != null } }
        withTimeout(2_000) { voice.chat.first { it.phase == VoicePhase.Off } }
        assertEquals("The microphone was disconnected.", voice.chat.value.error)
        assertEquals(1, reads.get())
        assertEquals(1, released.get())
    }

    @Test fun otherReadErrorsSayTheirCode() = runBlocking {
        mode = "invalid"
        val failure = withTimeout(2_000) { runCatching { AndroidVoiceRecorder().record(VoiceActivity()) } }.exceptionOrNull()
        assertEquals("Couldn't read from the microphone (error ${AudioRecord.ERROR_INVALID_OPERATION}).", failure?.message)
        assertEquals(1, released.get())
    }

    @Test fun noDataFailsWithoutSpinning() = runBlocking {
        mode = "zero"
        val result = withTimeout(2_000) { runCatching { AndroidVoiceRecorder(noAudioMs = 200).record(VoiceActivity()) } }
        assertEquals("The microphone stopped sending audio.", result.exceptionOrNull()?.message)
        assertTrue(reads.get() in 2..40)
        assertEquals(1, released.get())
    }

    @Test fun partialReadsAreMeasuredAsWholeFrames() = runBlocking {
        mode = "partial"
        var levels = 0
        withTimeout(2_000) { AndroidVoiceRecorder().record(VoiceActivity(idleMs = 0, maxMs = 400), onLevel = { levels++ }) }
        // 20 frames of 20 ms, then the 0 that closes the meter; not one per one-sample read.
        assertEquals(21, levels)
    }

    @Test fun cancellingListenOverReleasesAStarvedMicrophone() = runBlocking {
        val listening = async(Dispatchers.Default) { AndroidVoiceRecorder().listenOver(BargeIn(), { true }, {}) }
        try {
            awaitRead()
            withTimeout(2_000) { listening.cancelAndJoin() }
            assertEquals(1, released.get())
        } finally {
            unblock.countDown()
            listening.cancelAndJoin()
        }
    }

    @Test fun listenOverWithoutDataFailsWithoutSpinning() = runBlocking {
        mode = "zero"
        val result = withTimeout(2_000) { runCatching { AndroidVoiceRecorder(noAudioMs = 200).listenOver(BargeIn(), { true }, {}) } }
        assertEquals("The microphone stopped sending audio.", result.exceptionOrNull()?.message)
        assertTrue(reads.get() in 2..40)
        assertEquals(1, released.get())
    }

    @Test fun cancellationReleasesAStarvedMicrophone() = runBlocking {
        val recording = async(Dispatchers.Default) { AndroidVoiceRecorder().record(VoiceActivity()) }
        try {
            awaitRead()
            withTimeout(2_000) { recording.cancelAndJoin() }
            assertEquals(1, released.get())
        } finally {
            unblock.countDown()
            recording.cancelAndJoin()
        }
    }

    @Test fun silentPcmKeepsTheExistingDuration() = checkPcm("pcm")
    @Test fun partialReadsKeepTheSameDuration() = checkPcm("partial")
    @Test fun emptyReadsBetweenPcmFramesRecover() = checkPcm("gapped")

    @Test fun aMicrophoneThatDidNotStartIsReleased() = runBlocking {
        mode = "stopped"
        val failure = runCatching { AndroidVoiceRecorder().record(VoiceActivity()) }.exceptionOrNull()
        assertEquals("The microphone isn't available.", failure?.message)
        assertEquals(0, reads.get())
        assertEquals(1, released.get())
    }

    private fun checkPcm(source: String) = runBlocking {
        mode = source
        val audio = withTimeout(2_000) { AndroidVoiceRecorder().record(VoiceActivity(idleMs = 0, maxMs = 400)) }
        assertFalse(audio.heardSpeech)
        assertEquals("audio/wav", audio.mimeType)
        assertEquals(44 + 12_800, audio.bytes.size)
        assertEquals(1, released.get())
    }

    private suspend fun awaitRead() {
        assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
    }

    private fun controller(test: suspend (VoiceController, ChatSession) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val requests = AtomicInteger()
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val http = createHttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/api/audio/transcribe") requests.incrementAndGet()
            respond("""{"ok":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }, cookies)
        val connection = GatewayConnection(AuthApi(http, cookies), { _, _ -> error("Not connecting") }, scope)
        val session = ChatSession(gateway, null, null, connection, SessionsApi(http), scope)
        val voice = VoiceController(AudioApi(http), AndroidVoiceRecorder(), object : SpeechPlayer {
            override suspend fun play(audio: SpokenAudio) = Unit
        }, scope, scope)
        try {
            test(voice, session)
            assertEquals(0, requests.get())
        } finally {
            unblock.countDown()
            voice.stopAll()
            scope.coroutineContext.job.cancelAndJoin()
            http.close()
        }
    }

    // The blocking overload waits for data; the nonblocking overload returns what's available.
    @Implements(AudioRecord::class)
    class Microphone {
        private val disposed = AtomicBoolean()
        @Implementation fun __constructor__(source: Int, rate: Int, channels: Int, format: Int, buffer: Int) { opened.incrementAndGet() }
        @Implementation fun getState() = AudioRecord.STATE_INITIALIZED
        @Implementation fun getAudioSessionId() = 1
        @Implementation fun startRecording() = Unit
        @Implementation fun getRecordingState() = if (mode == "stopped") AudioRecord.RECORDSTATE_STOPPED else AudioRecord.RECORDSTATE_RECORDING
        @Implementation fun read(data: ShortArray, offset: Int, count: Int): Int = read(data, offset, count, AudioRecord.READ_BLOCKING)
        @Implementation fun read(data: ShortArray, offset: Int, count: Int, readMode: Int): Int {
            val attempt = reads.incrementAndGet()
            entered.countDown()
            if (mode == "empty" && readMode == AudioRecord.READ_BLOCKING) {
                unblock.await(3, TimeUnit.SECONDS)
                return 0
            }
            if (mode == "error") return AudioRecord.ERROR_DEAD_OBJECT
            if (mode == "invalid") return AudioRecord.ERROR_INVALID_OPERATION
            if (mode == "empty" || mode == "zero" || mode == "gapped" && attempt % 2 == 1) return 0
            val size = if (mode == "partial") 1 else count
            data.fill(0, offset, offset + size)
            return size
        }
        @Implementation fun stop() = Unit
        // AudioRecord's finalizer also calls release; count disposed instances, not repeated calls.
        @Implementation fun release() { if (disposed.compareAndSet(false, true)) released.incrementAndGet() }
        companion object {
            @JvmStatic @Implementation fun getMinBufferSize(rate: Int, channels: Int, format: Int) = 2_560
        }
    }

    @Implements(NoiseSuppressor::class)
    class Suppression {
        companion object {
            @JvmStatic @Implementation fun isAvailable() = false
        }
    }

    @Implements(AcousticEchoCanceler::class)
    class EchoCancelling {
        companion object {
            @JvmStatic @Implementation fun isAvailable() = false
        }
    }

    companion object {
        private val gateway = GatewayUrl.parse("https://fixture.example")
        @Volatile private var mode = "empty"
        @Volatile private var entered = CountDownLatch(1)
        @Volatile private var unblock = CountDownLatch(1)
        private val opened = AtomicInteger()
        private val reads = AtomicInteger()
        private val released = AtomicInteger()
    }
}
