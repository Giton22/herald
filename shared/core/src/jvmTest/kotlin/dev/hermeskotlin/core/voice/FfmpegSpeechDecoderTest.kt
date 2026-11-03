package dev.hermeskotlin.core.voice

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FfmpegSpeechDecoderTest {
    @Test fun pipeDecodePreservesInputAndUsesBoundedFramesWithoutInheritedEnvironment() = runBlocking {
        val pcm = ByteArray(1922) { (it % 127).toByte() }
        val process = FinishedProcess(pcm)
        val decoder = FfmpegSpeechDecoder { builder ->
            assertTrue(builder.environment().isEmpty())
            assertEquals("/usr/bin/ffmpeg", builder.command().first())
            assertEquals("pipe", builder.command()[builder.command().indexOf("-protocol_whitelist") + 1])
            assertEquals(listOf("-f", "s16le", "pipe:1"), builder.command().takeLast(3))
            process
        }
        val chunks = Channel<ByteArray>(2)
        chunks.send(byteArrayOf(1, 2, 3)); chunks.send(byteArrayOf(4, 5)); chunks.close()
        val frames = mutableListOf<ByteArray>()
        decoder.decode(chunks, null, frames::add)
        assertEquals(listOf(960, 960, 2), frames.map { it.size })
        assertContentEquals(pcm, frames.flatMap { it.toList() }.toByteArray())
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), process.input.toByteArray())
        assertTrue(process.destroyed)
    }

    @Test fun eachChunkReachesTheCodecWithoutWaitingForMore() = runBlocking {
        // cat stands in for ffmpeg over real pipes: a chunk left in the JVM's stdin buffer never comes back out.
        assumeTrue(System.getProperty("os.name") == "Linux" && File("/usr/bin/cat").canExecute())
        val chunks = Channel<ByteArray>(1)
        val frames = Channel<ByteArray>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) {
            FfmpegSpeechDecoder { ProcessBuilder("/usr/bin/cat").start() }.decode(chunks, PcmFormat(48_000, 1)) { frames.send(it) }
        }
        chunks.send(ByteArray(960) { 3 })
        assertContentEquals(ByteArray(960) { 3 }, withTimeout(2_000) { frames.receive() })
        chunks.close()
        job.join()
    }

    @Test fun inputRejectedByTheCodecIsADecodeErrorNotABrokenPipe() = runBlocking {
        val process = object : Process() {
            override fun getOutputStream() = object : java.io.OutputStream() {
                override fun write(b: Int) = throw java.io.IOException("Broken pipe")
            }
            override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun waitFor() = 1
            override fun waitFor(timeout: Long, unit: TimeUnit) = true
            override fun exitValue() = 1
            override fun destroy() {}
            override fun isAlive() = false
        }
        val chunks = Channel<ByteArray>(1)
        chunks.send(byteArrayOf(1, 2, 3)); chunks.close()
        val failure = assertFailsWith<IllegalStateException> { FfmpegSpeechDecoder { process }.decode(chunks, null) { } }
        assertEquals("The spoken reply couldn't be decoded.", failure.message)
    }

    @Test fun unsuccessfulCodecExitFailsAndReleasesOwnedProcess() = runBlocking {
        val process = FinishedProcess(byteArrayOf(1, 2), 7)
        val chunks = Channel<ByteArray>(1); chunks.close()
        val failure = assertFailsWith<IllegalStateException> {
            FfmpegSpeechDecoder { process }.decode(chunks, null) { }
        }
        assertEquals("The spoken reply couldn't be decoded.", failure.message)
        assertTrue(process.destroyed)
    }

    @Test fun cancellationKillsAChildWithBlockedInputAndOutput() = runBlocking {
        assumeTrue(System.getProperty("os.name") == "Linux" && File("/usr/bin/sleep").canExecute())
        val started = CompletableDeferred<Process>()
        val uncaught = ConcurrentLinkedQueue<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> uncaught.add(error) })
        var child: Process? = null
        try {
            val chunks = Channel<ByteArray>(1)
            chunks.send(ByteArray(8 * 1024 * 1024))
            val job = scope.launch {
                FfmpegSpeechDecoder {
                    ProcessBuilder("/usr/bin/sleep", "300").apply { environment().clear() }.start()
                        .also { child = it; started.complete(it) }
                }.decode(chunks, null) { }
            }
            val process = withTimeout(3_000) { started.await() }
            delay(100) // The child never reads stdin or writes stdout, leaving both pipe tasks blocked.
            assertTrue(!job.isCompleted)
            withTimeout(3_000) { job.cancelAndJoin() }
            assertTrue(!process.isAlive)
            assertTrue(uncaught.isEmpty(), uncaught.joinToString())
            chunks.cancel()
        } finally {
            child?.destroyForcibly()
            child?.waitFor(2, TimeUnit.SECONDS)
            scope.cancel()
        }
    }

    @Test fun codecFailureKeepsCleanupErrorsAndStillClosesEveryPipe() = runBlocking {
        val cleanup = IllegalStateException("Synthetic pipe close failure")
        val process = FinishedProcess(byteArrayOf(), 7, cleanup)
        val chunks = Channel<ByteArray>(1); chunks.close()
        val result = runCatching { withContext(Dispatchers.Default) { FfmpegSpeechDecoder { process }.decode(chunks, null) { } } }.exceptionOrNull()
        assertTrue(generateSequence(result) { it.cause }.any { it.message == "The spoken reply couldn't be decoded." })
        assertTrue(generateSequence(result) { it.cause }.flatMap { it.suppressed.asSequence() }
            .any { suppressed -> generateSequence(suppressed) { it.cause }.any { it === cleanup } })
        assertTrue(process.outputClosed && process.errorClosed && process.destroyed)
    }

    @Test fun repeatedCloseFailureDoesNotReplaceTheOriginalError() = runBlocking {
        class PipeFailure(val marker: Int) : IllegalStateException("Synthetic repeated close failure")
        val original = PipeFailure(1)
        val process = FinishedProcess(byteArrayOf(), 0, original)
        val chunks = Channel<ByteArray>(1); chunks.close()
        val result = runCatching { FfmpegSpeechDecoder { process }.decode(chunks, null) { } }.exceptionOrNull()
        assertEquals(original.message, result?.message)
        assertTrue(generateSequence(result) { it.cause }.any { it === original })
        assertTrue(process.outputClosed && process.errorClosed)
    }

    @Test fun exitedCodecWithUnfinishedInputFailsBeforeTheCallerDeadline() = runBlocking {
        val process = FinishedProcess(byteArrayOf())
        val chunks = Channel<ByteArray>()
        try {
            val failure = runCatching {
                withTimeout(4_000) { FfmpegSpeechDecoder { process }.decode(chunks, null) { } }
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException && failure !is CancellationException)
            assertTrue(process.destroyed && process.outputClosed && process.errorClosed)
        } finally { chunks.cancel() }
    }

    private class FinishedProcess(pcm: ByteArray, private val status: Int = 0, private val closeFailure: Throwable? = null) : Process() {
        val input = object : ByteArrayOutputStream() {
            override fun close() { closeFailure?.let { throw it } }
        }
        var outputClosed = false
        var errorClosed = false
        private val output = object : ByteArrayInputStream(pcm) {
            override fun close() { outputClosed = true }
        }
        private val error = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { errorClosed = true }
        }
        var destroyed = false
        override fun getOutputStream() = input
        override fun getInputStream() = output
        override fun getErrorStream() = error
        override fun waitFor() = status
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue() = status
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroy(); return this }
        override fun isAlive() = false
    }
}
