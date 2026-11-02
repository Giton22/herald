package dev.hermeskotlin.core.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import dev.hermeskotlin.core.di.platformModule
import org.koin.dsl.koinApplication
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxSpeechPlayerTest {
    @Test fun platformGraphProvidesPlaybackOnlyForSupportedLinuxArchitectures() {
        val app = koinApplication { modules(platformModule) }
        try {
            val player = app.koin.get<SpeechPlayer>()
            if (System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64", "aarch64") &&
                java.io.File("/usr/bin/ffmpeg").canExecute()) {
                assertIs<LinuxSpeechPlayer>(player)
            } else assertTrue(player === NoSpeechPlayer)
        } finally { app.close() }
    }

    @Test fun decodedAudioReachesItsDeviceAndCompletionReleasesIt() = checkPlayback {
        val speaker = FakeOutput()
        val decoder = decoder { emit -> emit(ByteArray(960) { 7 }) }
        val player = LinuxSpeechPlayer(decoder) { speaker }
        val input = byteArrayOf(1, 2, 3)
        val job = async { player.play(SpokenAudio(input, "audio/mpeg")) }
        speaker.ready.await()
        assertContentEquals(ByteArray(960) { 7 }, speaker.render())
        repeat(45) { speaker.render() }
        speaker.fail(IllegalStateException("Retired device callback"))
        job.await()
        assertTrue(speaker.closed)
        assertContentEquals(byteArrayOf(1, 2, 3), input)
    }

    @Test fun cancellingClosesTheDecoderAndDeviceAndRetiresOldCallbacks() = checkPlayback {
        var cancelled = false
        val speaker = FakeOutput()
        val decoder = decoder { emit ->
            emit(ByteArray(960) { 9 })
            try { awaitCancellation() } finally { cancelled = true }
        }
        val player = LinuxSpeechPlayer(decoder) { speaker }
        val job = async { player.play(SpokenAudio(byteArrayOf(1), "audio/wav")) }
        speaker.ready.await()
        speaker.render()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertTrue(speaker.closed)
        assertContentEquals(ByteArray(960), speaker.render())
    }

    @Test fun holdPreservesReadyAudioUntilReleased() = checkPlayback {
        val speaker = FakeOutput()
        val player = LinuxSpeechPlayer(decoder { it(ByteArray(960) { 5 }) }) { speaker }
        assertTrue(player.hold(true))
        val job = async { player.play(SpokenAudio(byteArrayOf(1), "audio/wav")) }
        speaker.ready.await()
        repeat(3) { assertContentEquals(ByteArray(960), speaker.render()) }
        assertFalse(job.isCompleted)
        assertTrue(player.hold(false))
        assertContentEquals(ByteArray(960) { 5 }, speaker.render())
        repeat(45) { speaker.render() }
        job.await()
        assertTrue(speaker.closed)
    }

    @Test fun deviceFailureSilencesQueuedAudioBeforeTheCallerCanFinishCleanup() = checkPlayback {
        val speaker = FakeOutput()
        val original = IllegalStateException("Synthetic speaker disconnected")
        val player = LinuxSpeechPlayer(decoder { emit ->
            emit(ByteArray(960) { 7 }); emit(ByteArray(960) { 8 })
        }) { speaker }
        val job = async { runCatching { player.play(SpokenAudio(byteArrayOf(1), "audio/wav")) } }
        speaker.ready.await()
        assertContentEquals(ByteArray(960) { 7 }, speaker.pullNow())
        speaker.fail(original)
        assertContentEquals(ByteArray(960), speaker.pullNow())
        assertTrue(generateSequence(job.await().exceptionOrNull()) { it.cause }.any { it === original })
        assertTrue(speaker.closed)
    }

    @Test fun failedStartupStillClosesItsDeviceAndPreservesThePrimaryError() = checkPlayback {
        val original = IllegalStateException("Synthetic startup failure")
        val cleanup = IllegalStateException("Synthetic cleanup failure")
        var closed = false
        val player = LinuxSpeechPlayer(decoder { it(ByteArray(960)) }) {
            object : SpeechOutput {
                override fun start(fill: (ByteArray, Int, Int) -> Unit, fail: (Throwable) -> Unit) { throw original }
                override fun close() { closed = true; throw cleanup }
            }
        }
        val result = runCatching { player.play(SpokenAudio(byteArrayOf(1), "audio/wav")) }.exceptionOrNull()
        assertTrue(closed)
        assertTrue(generateSequence(result) { it.cause }.any { it === original })
        assertTrue(generateSequence(result) { it.cause }.flatMap { it.suppressed.asSequence() }
            .any { suppressed -> generateSequence(suppressed) { it.cause }.any { it === cleanup } }, result?.stackTraceToString())
    }

    @Test fun repeatedCleanupFailureCannotMaskThePlaybackError() = checkPlayback {
        class SharedFailure(val marker: Int) : IllegalStateException("Synthetic shared failure")
        val original = SharedFailure(1)
        var closed = false
        val player = LinuxSpeechPlayer(decoder { it(ByteArray(960)) }) {
            object : SpeechOutput {
                override fun start(fill: (ByteArray, Int, Int) -> Unit, fail: (Throwable) -> Unit) { throw original }
                override fun close() { closed = true; throw original }
            }
        }
        val chunks = Channel<ByteArray>(); chunks.close()
        val result = runCatching { player.playPcm(48_000, 1, chunks) { throw original } }.exceptionOrNull()
        assertEquals(original.message, result?.message)
        assertFalse(result?.stackTraceToString().orEmpty().contains("Self-suppression"))
        assertTrue(generateSequence(result) { it.cause }.any { it === original })
        assertTrue(closed)
    }

    @Test fun aDeviceThatNeverPullsTimesOutAndReleasesTheInput() = checkPlayback {
        val speaker = FakeOutput()
        val chunks = Channel<ByteArray>(1)
        val player = LinuxSpeechPlayer(decoder { emit -> emit(ByteArray(960)); awaitCancellation() }) { speaker }
        val failure = assertFailsWith<IllegalStateException> { player.playPcm(48_000, 1, chunks) { } }
        assertEquals("The speaker stopped accepting audio.", failure.message)
        assertTrue(speaker.closed)
        assertTrue(chunks.receiveCatching().isClosed)
    }

    @Test fun waitingForTheFirstAudioDoesNotConsumeTheSpeakerStartupTimeout() = checkPlayback {
        val speaker = FakeOutput()
        val player = LinuxSpeechPlayer(decoder { emit -> delay(2_100); emit(ByteArray(960) { 6 }) }) { speaker }
        val job = async { runCatching { player.play(SpokenAudio(byteArrayOf(1), "audio/wav")) } }
        speaker.ready.await()
        delay(650)
        assertFalse(job.isCompleted)
        assertContentEquals(ByteArray(960) { 6 }, speaker.render())
        repeat(45) { speaker.render() }
        assertTrue(job.await().isSuccess)
    }

    @Test fun cancellationWinsOverAConcurrentDeviceFailure() = checkPlayback {
        val uncaught = ConcurrentLinkedQueue<Throwable>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, error -> uncaught.add(error) })
        val speaker = FakeOutput()
        try {
            val player = LinuxSpeechPlayer(decoder { emit -> emit(ByteArray(960)); awaitCancellation() }) { speaker }
            val job = scope.launch { player.play(SpokenAudio(byteArrayOf(1), "audio/wav")) }
            speaker.ready.await()
            job.cancel()
            speaker.fail(IllegalStateException("Synthetic disconnected during cancellation"))
            job.join()
            assertTrue(uncaught.isEmpty(), uncaught.joinToString())
            assertTrue(speaker.closed)
        } finally { scope.cancel() }
    }

    @Test fun speakersAtOtherRatesPlayTheDecodedSpeech() {
        // 44.1 kHz stereo is a PulseAudio server's usual default; WebRTC asks at the server's rate and channels.
        // 96 kHz lands between a frame's last sample and the next frame's first.
        speakerPlaysARamp(44_100, 2)
        speakerPlaysARamp(96_000, 1)
    }

    private fun speakerPlaysARamp(rate: Int, channels: Int) = checkPlayback {
        // A ramp, ten per 48 kHz sample, across three frames: linear interpolation reproduces it exactly, including
        // across frame edges and from one pull to the next.
        fun ramp(frame: Int) = ByteArray(960).also { pcm ->
            for (i in 0 until 480) {
                val value = (frame * 480 + i) * 10
                pcm[i * 2] = value.toByte(); pcm[i * 2 + 1] = (value shr 8).toByte()
            }
        }
        val emitted = CompletableDeferred<Unit>()
        val speaker = FakeOutput(rate, channels)
        val player = LinuxSpeechPlayer(decoder { emit -> repeat(3) { emit(ramp(it)) }; emitted.complete(Unit) }) { speaker }
        val job = async { player.play(SpokenAudio(byteArrayOf(1), "audio/wav")) }
        speaker.ready.await(); emitted.await()
        val pulled = speaker.render() + speaker.render()
        val frames = 2 * rate / 100
        assertEquals(frames * channels * 2, pulled.size)
        for (frame in 0 until frames) {
            val expected = Math.round(frame * 48_000.0 / rate * 10).toInt()
            for (channel in 0 until channels) {
                val at = (frame * channels + channel) * 2
                val actual = ((pulled[at].toInt() and 255) or (pulled[at + 1].toInt() shl 8)).toShort().toInt()
                assertEquals(expected, actual, "$rate Hz frame $frame channel $channel")
            }
        }
        repeat(50) { speaker.render() }
        job.await()
        assertTrue(speaker.closed)
    }

    @Test fun leadingSilenceIsntSpeaking() = checkPlayback {
        val speaker = FakeOutput()
        val sounds = Channel<Boolean>(Channel.UNLIMITED)
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        val player = LinuxSpeechPlayer(object : SpeechDecoder {
            override suspend fun decode(chunks: ReceiveChannel<ByteArray>, format: PcmFormat?, emit: suspend (ByteArray) -> Unit) {
                for (chunk in chunks) emit(chunk)
            }
        }) { speaker }
        val job = async { player.playPcm(48_000, 1, chunks) { sounds.trySend(it) } }
        repeat(3) { chunks.send(ByteArray(960)) }
        chunks.send(ByteArray(960) { 1 })
        speaker.ready.await()
        delay(100) // Lets the decoder hand over all four frames.
        repeat(3) { speaker.render() }
        delay(100)
        assertTrue(sounds.tryReceive().isFailure, "Silence reported as speaking")
        speaker.render()
        assertEquals(true, sounds.receive())
        chunks.close()
        repeat(70) { speaker.render() }
        job.await()
    }

    @Test fun aShortGapBetweenSentencesIsntTheEndOfSpeaking() = checkPlayback {
        val speaker = FakeOutput()
        val sounds = Channel<Boolean>(Channel.UNLIMITED)
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        val player = LinuxSpeechPlayer(object : SpeechDecoder {
            override suspend fun decode(chunks: ReceiveChannel<ByteArray>, format: PcmFormat?, emit: suspend (ByteArray) -> Unit) {
                for (chunk in chunks) emit(chunk)
            }
        }) { speaker }
        val job = async { player.playPcm(48_000, 1, chunks) { sounds.trySend(it) } }
        chunks.send(ByteArray(960) { 1 })
        speaker.ready.await()
        speaker.render()
        assertEquals(true, sounds.receive())
        // 300 ms with nothing to play: a late chunk, not silence yet.
        repeat(30) { speaker.render() }
        delay(100)
        assertTrue(sounds.tryReceive().isFailure)
        repeat(31) { speaker.render() }
        assertEquals(false, sounds.receive())
        chunks.close()
        repeat(45) { speaker.render() }
        job.await()
    }

    @Test fun unsupportedPcmDoesNotOpenCodecOrDevice() = checkPlayback {
        var opened = false
        val player = LinuxSpeechPlayer(decoder { opened = true }) { opened = true; FakeOutput() }
        val chunks = Channel<ByteArray>()
        try {
            assertFailsWith<IllegalArgumentException> { player.playPcm(48_000, 3, chunks) { } }
            assertFalse(opened)
        } finally { chunks.cancel() }
    }

    private fun decoder(block: suspend (suspend (ByteArray) -> Unit) -> Unit) = object : SpeechDecoder {
        override suspend fun decode(chunks: ReceiveChannel<ByteArray>, format: PcmFormat?, emit: suspend (ByteArray) -> Unit) = block(emit)
    }
    private class FakeOutput(private val rate: Int = 48_000, private val channels: Int = 1) : SpeechOutput {
        val ready = CompletableDeferred<Unit>()
        var closed = false
        private lateinit var fill: (ByteArray, Int, Int) -> Unit
        private lateinit var failure: (Throwable) -> Unit
        override fun start(fill: (ByteArray, Int, Int) -> Unit, fail: (Throwable) -> Unit) { this.fill = fill; failure = fail; ready.complete(Unit) }
        fun fail(cause: Throwable) = failure(cause)
        /** One 10 ms pull, as WebRTC asks. */
        fun pullNow(): ByteArray = ByteArray(rate / 100 * 2 * channels).also { fill(it, rate, channels) }
        suspend fun render(): ByteArray = pullNow().also { yield() }
        override fun close() { closed = true }
    }
    private fun checkPlayback(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(4_000, block) }
}
