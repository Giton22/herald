package dev.hermeskotlin.core.voice

import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Decode only pipe input; media cannot open URLs, files or inherited credential-bearing environment. */
internal class FfmpegSpeechDecoder(private val start: (ProcessBuilder) -> Process = { it.start() }) : SpeechDecoder {
    override suspend fun decode(chunks: ReceiveChannel<ByteArray>, format: PcmFormat?, emit: suspend (ByteArray) -> Unit) {
        var process: Process? = null
        var failure: Throwable? = null
        try {
            val args = mutableListOf("/usr/bin/ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-threads", "1",
                "-protocol_whitelist", "pipe", "-format_whitelist", "mp3,wav,ogg,flac,aac,mov,s16le")
            if (format != null) args += listOf("-f", "s16le", "-ar", format.sampleRate.toString(), "-ac", format.channels.toString())
            args += listOf("-i", "pipe:0", "-map", "0:a:0", "-vn", "-sn", "-dn", "-acodec", "pcm_s16le",
                "-ar", "48000", "-ac", "1", "-f", "s16le", "pipe:1")
            val child = runInterruptible(Dispatchers.IO) {
                start(ProcessBuilder(args).apply { environment().clear(); redirectError(ProcessBuilder.Redirect.DISCARD) })
                    .also { process = it }
            }
            coroutineScope {
                suspendCancellableCoroutine<Unit> { completion ->
                    // Kill before structured concurrency joins pipe IO: interrupts alone cannot unblock every Process stream.
                    completion.invokeOnCancellation { child.destroyForcibly() }
                    val writer = launch {
                        var remainder = 0
                        for (bytes in chunks) {
                            // The process's stdin is buffered (8 KB): without a flush a sentence's last chunk waits in
                            // it while the agent works on the next, and the reply goes quiet mid-word.
                            try {
                                runInterruptible(Dispatchers.IO) { child.outputStream.write(bytes); child.outputStream.flush() }
                            } catch (broken: java.io.IOException) {
                                // ffmpeg exits as soon as it rejects the input, which breaks the pipe under this write.
                                if (runInterruptible(Dispatchers.IO) { child.waitFor(2, TimeUnit.SECONDS) } && child.exitValue() != 0) {
                                    throw IllegalStateException("The spoken reply couldn't be decoded.", broken)
                                }
                                throw broken
                            }
                            if (format != null) remainder = (remainder + bytes.size % (2 * format.channels)) % (2 * format.channels)
                        }
                        check(remainder == 0) { "The speech stream ended inside a PCM sample." }
                        runInterruptible(Dispatchers.IO) { child.outputStream.close() }
                    }
                    val reader = launch {
                        while (true) {
                            val frame = runInterruptible(Dispatchers.IO) { child.inputStream.readNBytes(960) }
                            if (frame.isEmpty()) break
                            check(frame.size % 2 == 0) { "The codec returned an incomplete PCM sample." }
                            emit(frame)
                        }
                    }
                    launch {
                        val status = runInterruptible(Dispatchers.IO) { child.waitFor() }
                        check(status == 0) { "The spoken reply couldn't be decoded." }
                        try { withTimeout(2_000) { writer.join() } }
                        catch (expired: TimeoutCancellationException) {
                            currentCoroutineContext().ensureActive()
                            throw IllegalStateException("The speech input didn't finish.", expired)
                        }
                        reader.join()
                        completion.resume(Unit)
                    }
                }
            }
        } catch (caught: Throwable) {
            val primary = try { currentCoroutineContext().ensureActive(); caught }
                catch (cancelled: CancellationException) { cancelled }
            // Retiring blocked pipes can throw IO errors; cancellation still owns the operation's result.
            failure = if (primary is CancellationException) primary else IllegalStateException(primary.message, primary)
            throw failure
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                process?.let { child ->
                    var cleanup: Throwable? = null
                    fun attempt(action: () -> Unit) {
                        try { action() } catch (caught: Throwable) {
                            if (cleanup == null) cleanup = caught else if (cleanup !== caught) cleanup.addSuppressed(caught)
                        }
                    }
                    attempt { child.destroyForcibly() }
                    attempt { check(child.waitFor(2, TimeUnit.SECONDS)) { "The speech codec couldn't stop." } }
                    attempt { child.outputStream.close() }
                    attempt { child.inputStream.close() }
                    attempt { child.errorStream.close() }
                    cleanup?.let { if (failure == null) throw it else if (failure !== it) failure.addSuppressed(it) }
                }
            }
        }
    }
}
