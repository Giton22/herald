package dev.hermeskotlin.core.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxVoiceRecorderTest {
    @Test fun finishKeepsPcmInTheExistingWavContract() = checkRecording {
        val run = begin()
        run.microphone.ready.await()
        run.feed(400)
        run.recorder.finish()
        val result = run.job.await()
        assertEquals("audio/wav", result.mimeType)
        assertEquals("RIFF", String(result.bytes.copyOfRange(0, 4)))
        val header = ByteBuffer.wrap(result.bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16_000, header.getInt(24))
        assertEquals(1, header.getShort(22).toInt())
        assertContentEquals(pcm(400), result.bytes.copyOfRange(44, result.bytes.size))
        assertTrue(run.microphone.closed)
    }

    @Test fun cancellingDiscardsTheRecordingAndClosesTheDevice() = checkRecording {
        val run = begin()
        run.microphone.ready.await()
        run.feed(400)
        run.job.cancelAndJoin()
        assertTrue(run.job.isCancelled)
        assertTrue(run.microphone.closed)
    }

    @Test fun anotherTapCannotTakeOverAnActiveRecorder() = checkRecording(6_000) {
        val run = begin()
        run.microphone.ready.await()
        assertFailsWith<IllegalStateException> { run.recorder.record(VoiceActivity()) }
        assertFalse(run.microphone.closed)
        run.recorder.finish()
        run.job.await()
        assertTrue(run.microphone.closed)
    }

    @Test fun nativeFailureAbortsAndReleasesTheDevice() = checkRecording {
        val microphone = FakeMicrophone()
        val recorder = LinuxVoiceRecorder { microphone }
        val job = async { runCatching { recorder.record(VoiceActivity()) } }
        microphone.ready.await()
        microphone.fail(IllegalStateException("Synthetic device removed"))
        assertTrue(job.await().isFailure)
        assertTrue(microphone.closed)
    }

    @Test fun speechAndSilenceUseTheSharedActivityDetector() = checkRecording {
        var speechEvents = 0
        val run = begin(VoiceActivity(silenceMs = 20)) { speechEvents++ }
        run.microphone.ready.await()
        repeat(30) { run.feed(0) }
        repeat(3) { run.feed(5_000) }
        repeat(3) { run.feed(0) }
        assertTrue(run.job.await().heardSpeech)
        assertEquals(1, speechEvents)
        assertTrue(run.microphone.closed)
    }

    @Test fun callbackOverflowFailsInsteadOfGrowingAnUnboundedQueue() = checkRecording {
        val microphone = FakeMicrophone(burst = 300)
        val recorder = LinuxVoiceRecorder { microphone }
        assertFailsWith<IllegalStateException> { recorder.record(VoiceActivity()) }
        assertTrue(microphone.closed)
    }

    @Test fun overflowAtTheActivityLimitCannotReturnAPartialSuccess() = checkRecording {
        val microphone = FakeMicrophone(burst = 300)
        val recorder = LinuxVoiceRecorder { microphone }
        assertFailsWith<IllegalStateException> { recorder.record(VoiceActivity(maxMs = 2_570)) }
        assertTrue(microphone.closed)
    }

    @Test fun aShortStallOnTheCallersThreadDoesntEndTheRecording() = checkRecording {
        // A second of frames arriving while nothing reads them, as during a long layout or a GC pause.
        val microphone = FakeMicrophone(burst = 100)
        val recorder = LinuxVoiceRecorder { microphone }
        val job = async { recorder.record(VoiceActivity()) }
        microphone.ready.await()
        recorder.finish()
        assertEquals(44 + 100 * 320, job.await().bytes.size)
    }

    @Test fun theRecordingDoesntRunOnTheCallersThread() = checkRecording {
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "fixture-ui") }
        try {
            val threads = Channel<String>(Channel.UNLIMITED)
            val microphone = FakeMicrophone()
            val recorder = LinuxVoiceRecorder { microphone }
            val job = async(caller.asCoroutineDispatcher()) {
                recorder.record(VoiceActivity(), onLevel = { threads.trySend(Thread.currentThread().name) })
            }
            microphone.ready.await()
            microphone.frame(PcmFrame(pcm(400), 16_000, 1))
            assertTrue(threads.receive() != "fixture-ui")
            recorder.finish()
            job.await()
        } finally { caller.shutdown() }
    }

    @Test fun aDeviceThatWontStopDoesntCostTheFinishedRecording() = checkRecording {
        val microphone = FakeMicrophone(closeFailure = IllegalStateException("The microphone couldn't stop."))
        val recorder = LinuxVoiceRecorder { microphone }
        val levels = Channel<Float>(Channel.UNLIMITED)
        val job = async { recorder.record(VoiceActivity(), onLevel = { levels.trySend(it) }) }
        microphone.ready.await()
        microphone.frame(PcmFrame(pcm(400), 16_000, 1))
        levels.receive()
        recorder.finish()
        assertContentEquals(pcm(400), job.await().bytes.copyOfRange(44, 44 + 320))
        assertTrue(microphone.closed)
    }

    @Test fun startingRightAfterCancellingWaitsForTheDeviceToClose() = checkRecording {
        val slow = FakeMicrophone(closeDelayMillis = 500)
        val next = FakeMicrophone()
        val devices = ArrayDeque(listOf(slow, next))
        val recorder = LinuxVoiceRecorder { devices.removeFirst() }
        val old = async { recorder.record(VoiceActivity()) }
        slow.ready.await()
        old.cancel()
        // As the voice screen does: cancel the old job without waiting, then start again.
        val again = async { recorder.record(VoiceActivity()) }
        next.ready.await()
        assertTrue(slow.closed)
        recorder.finish()
        again.await()
    }

    @Test fun cleanupFailurePreservesTheOriginalStartupFailure() = checkRecording {
        val original = IllegalStateException("Synthetic startup failure")
        val microphone = FakeMicrophone(startFailure = original, closeFailure = IllegalStateException("Synthetic close failure"))
        val recorder = LinuxVoiceRecorder { microphone }
        assertEquals(original.message, assertFailsWith<IllegalStateException> { recorder.record(VoiceActivity()) }.message)
        assertTrue(microphone.closed)
    }

    @Test fun lateFramesCannotEnterTheNextRecording() = checkRecording {
        val first = FakeMicrophone()
        val second = FakeMicrophone()
        val devices = ArrayDeque(listOf(first, second))
        val recorder = LinuxVoiceRecorder { devices.removeFirst() }
        val old = async { recorder.record(VoiceActivity()) }
        first.ready.await()
        old.cancelAndJoin()
        val levels = Channel<Float>(Channel.UNLIMITED)
        val next = async { recorder.record(VoiceActivity(), onLevel = { levels.trySend(it) }) }
        second.ready.await()
        first.frame(PcmFrame(pcm(9_000), 16_000, 1))
        second.frame(PcmFrame(pcm(400), 16_000, 1))
        levels.receive()
        recorder.finish()
        assertContentEquals(pcm(400), next.await().bytes.drop(44).toByteArray())
        assertTrue(first.closed && second.closed)
    }

    @Test fun aStalledDeviceFailsAndCloses() = checkRecording(8_000) {
        val microphone = FakeMicrophone()
        val recorder = LinuxVoiceRecorder { microphone }
        val result = async { runCatching { recorder.record(VoiceActivity()) } }
        microphone.ready.await()
        assertEquals("The microphone stopped delivering audio.", result.await().exceptionOrNull()?.message)
        assertTrue(microphone.closed)
    }

    @Test fun aFormatChangeCannotProduceAMixedWav() = checkRecording {
        val microphone = FakeMicrophone()
        val levels = Channel<Float>(Channel.UNLIMITED)
        val recorder = LinuxVoiceRecorder { microphone }
        val result = async { runCatching { recorder.record(VoiceActivity(), onLevel = { levels.trySend(it) }) } }
        microphone.ready.await()
        microphone.frame(PcmFrame(pcm(400), 16_000, 1))
        levels.receive()
        microphone.frame(PcmFrame(pcm(400), 48_000, 1))
        assertEquals("The microphone changed audio format.", result.await().exceptionOrNull()?.message)
        assertTrue(microphone.closed)
    }

    @Test fun cancellingDuringStartupInterruptsAndClosesTheDevice() = checkRecording {
        val entered = CompletableDeferred<Unit>()
        val gate = java.util.concurrent.CountDownLatch(1)
        var closed = false
        val microphone = object : Microphone {
            override fun start(onFrame: (PcmFrame) -> Unit, onFailure: (Throwable) -> Unit) {
                entered.complete(Unit)
                gate.await()
            }
            override fun close() { closed = true }
        }
        val recorder = LinuxVoiceRecorder { microphone }
        val job = async { recorder.record(VoiceActivity()) }
        entered.await()
        try { withTimeout(1_000) { job.cancelAndJoin() } } finally { gate.countDown() }
        assertTrue(closed)
    }

    private fun checkRecording(timeoutMillis: Long = 3_000, block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(timeoutMillis) { block() }
    }

    private fun CoroutineScope.begin(activity: VoiceActivity = VoiceActivity(), onSpeech: () -> Unit = {}): Run {
        val microphone = FakeMicrophone()
        val recorder = LinuxVoiceRecorder { microphone }
        val levels = Channel<Float>(Channel.UNLIMITED)
        val job = async { recorder.record(activity, onLevel = { levels.trySend(it) }, onSpeech = onSpeech) }
        return Run(recorder, microphone, levels, job)
    }

    private class Run(
        val recorder: LinuxVoiceRecorder,
        val microphone: FakeMicrophone,
        val levels: Channel<Float>,
        val job: Deferred<Recording>,
    ) {
        suspend fun feed(value: Int) {
            microphone.frame(PcmFrame(pcm(value), 16_000, 1))
            assertTrue(levels.receive() in 0f..1f)
        }
    }

    private class FakeMicrophone(
        private val burst: Int = 0,
        private val startFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
        private val closeDelayMillis: Long = 0,
    ) : Microphone {
        val ready = CompletableDeferred<Unit>()
        lateinit var frame: (PcmFrame) -> Unit
        lateinit var fail: (Throwable) -> Unit
        @Volatile var closed = false
        override fun start(onFrame: (PcmFrame) -> Unit, onFailure: (Throwable) -> Unit) {
            startFailure?.let { throw it }
            frame = onFrame
            fail = onFailure
            repeat(burst) { onFrame(PcmFrame(pcm(0), 16_000, 1)) }
            ready.complete(Unit)
        }
        override fun close() {
            Thread.sleep(closeDelayMillis)
            closed = true
            closeFailure?.let { throw it }
        }
    }

    companion object {
        private fun pcm(value: Int) = ByteBuffer.allocate(320).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(160) { putShort(value.toShort()) }
        }.array()
    }
}
