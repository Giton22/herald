package dev.hermeskotlin.core.voice

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal class PcmFormat(val sampleRate: Int, val channels: Int) {
    init { require(sampleRate in 8_000..192_000 && channels in 1..2) { "Unsupported speech format." } }
}
internal interface SpeechDecoder {
    /** Emits owned 16-bit little-endian mono PCM at 48 kHz, in frames up to 960 bytes. */
    suspend fun decode(chunks: ReceiveChannel<ByteArray>, format: PcmFormat?, emit: suspend (ByteArray) -> Unit)
}
internal interface SpeechOutput : AutoCloseable {
    /**
     * Each pull supplies a frame of 16-bit PCM at the device's own rate and channel count (a PulseAudio server often
     * runs at 44.1 kHz, or in stereo); callbacks must not block the native audio thread.
     */
    fun start(fill: (bytes: ByteArray, sampleRate: Int, channels: Int) -> Unit, fail: (Throwable) -> Unit)
}

/** Every playback owns its decoder/device; cancellation cannot retire another caller's audio. */
internal class LinuxSpeechPlayer(private val decoder: SpeechDecoder, private val output: () -> SpeechOutput) : SpeechPlayer {
    private val holding = AtomicBoolean()

    override fun hold(on: Boolean): Boolean { holding.set(on); return true }

    override suspend fun play(audio: SpokenAudio) {
        if (audio.bytes.isEmpty()) return
        require(audio.bytes.size <= 32 * 1024 * 1024) { "The spoken reply is too large." }
        val chunks = Channel<ByteArray>(1)
        chunks.trySend(audio.bytes).getOrThrow()
        chunks.close()
        playStream(chunks, null) { }
    }

    override suspend fun playPcm(sampleRate: Int, channels: Int, chunks: ReceiveChannel<ByteArray>, onSound: (Boolean) -> Unit) {
        playStream(chunks, PcmFormat(sampleRate, channels), onSound)
    }

    private suspend fun playStream(chunks: ReceiveChannel<ByteArray>, format: PcmFormat?, onSound: (Boolean) -> Unit) = coroutineScope {
        val session = Session(holding)
        var device: SpeechOutput? = null
        var failure: Throwable? = null
        val decoding = launch {
            try { decoder.decode(chunks, format, session::frame); session.finish() }
            catch (caught: Throwable) { session.fail(caught) }
        }
        val sound = launch {
            var previous: Boolean? = null
            try {
                for (value in session.sound) if (previous != value) { onSound(value); previous = value }
            } catch (caught: Throwable) {
                if (caught !is CancellationException) session.fail(caught)
            }
        }
        var watchdog: kotlinx.coroutines.Job? = null
        try {
            if (session.first.await()) {
                runInterruptible(Dispatchers.IO) { output().also { device = it }.start(session::fill, session::fail) }
                session.lastPull.set(System.nanoTime())
                watchdog = launch {
                    while (isActive) {
                        delay(500)
                        if (System.nanoTime() - session.lastPull.get() > 2_000_000_000L) {
                            session.fail(IllegalStateException("The speaker stopped accepting audio.")); break
                        }
                    }
                }
            }
            session.done.await()
        } catch (caught: Throwable) {
            val primary = try { currentCoroutineContext().ensureActive(); session.failure ?: caught }
                catch (cancelled: CancellationException) { cancelled }
            // A fresh boundary retains cleanup suppression when coroutine debug recovery copies exceptions.
            failure = if (primary is CancellationException) primary else IllegalStateException(primary.message, primary)
            if (primary !== caught && caught !is CancellationException) failure.addSuppressed(caught)
            throw failure
        } finally {
            session.stop()
            decoding.cancel()
            sound.cancel()
            watchdog?.cancel()
            chunks.cancel()
            var cleanupFailure: Throwable? = null
            try { withContext(NonCancellable + Dispatchers.IO) { device?.close() } }
            catch (caught: Throwable) { cleanupFailure = caught }
            try { onSound(false) } catch (caught: Throwable) {
                if (cleanupFailure == null) cleanupFailure = caught else cleanupFailure.addSuppressed(caught)
            }
            cleanupFailure?.let { if (failure == null) throw it else failure.addSuppressed(it) }
        }
    }

    private class Session(private val holding: AtomicBoolean) {
        private val terminal = AtomicReference<Any?>()
        private val completed = Any()
        val failure: Throwable? get() = terminal.get() as? Throwable
        val first = CompletableDeferred<Boolean>()
        val done = CompletableDeferred<Unit>()
        val sound = Channel<Boolean>(Channel.CONFLATED)
        val lastPull = AtomicLong(System.nanoTime())
        private val stopped = AtomicBoolean()
        private val frames = Channel<ByteArray>(32, onUndeliveredElement = { it.fill(0) })
        private var submitted = 0
        // Device-thread state: the decoded frame being played and where in it (in 48 kHz samples), how long the
        // device has been fed only silence, and how long since the input ended.
        private var playing: ByteArray? = null
        private var next: ByteArray? = null
        private var position = 0.0
        private var quietMs = 0L
        private var tailMs = 0L
        private var lastPoll: kotlinx.coroutines.channels.ChannelResult<ByteArray>? = null

        suspend fun frame(bytes: ByteArray) {
            check(bytes.isNotEmpty() && bytes.size <= 960 && bytes.size % 2 == 0) { "Unsupported decoded speech format." }
            frames.send(bytes)
            submitted++
            first.complete(true)
        }
        fun finish() {
            frames.close()
            if (submitted == 0) { succeed(); first.complete(false) }
        }
        fun fail(caught: Throwable) {
            if (terminal.compareAndSet(null, caught)) {
                stopped.set(true)
                frames.cancel()
                first.completeExceptionally(caught)
                done.completeExceptionally(caught)
            }
        }
        fun fill(bytes: ByteArray, sampleRate: Int, channels: Int) {
            bytes.fill(0)
            if (stopped.get()) return
            lastPull.set(System.nanoTime())
            if (holding.get()) { sound.trySend(false); return }
            if (sampleRate !in 8_000..192_000 || channels !in 1..2 || bytes.size % (2 * channels) != 0) {
                fail(IllegalStateException("Unsupported speaker format.")); return
            }
            // Decoded speech is 48 kHz mono; step through it at the device's rate (linear interpolation) and copy
            // each sample to every channel.
            val count = bytes.size / (2 * channels)
            val step = DECODED_RATE.toDouble() / sampleRate
            var written = 0
            var audible = false
            while (written < count) {
                val sample = nextSample(step) ?: break
                if (sample != 0.toShort()) audible = true
                for (channel in 0 until channels) {
                    val at = (written * channels + channel) * 2
                    bytes[at] = sample.toByte()
                    bytes[at + 1] = (sample.toInt() shr 8).toByte()
                }
                written++
            }
            val ms = count * 1000L / sampleRate
            // A gap between sentences, or a late network chunk, isn't the end of speaking: as Android, report
            // silence only once it has lasted. Silent samples in the speech count as a gap too.
            if (audible) { quietMs = 0; sound.trySend(true) }
            else {
                quietMs += ms
                if (quietMs >= QUIET_MS) sound.trySend(false)
            }
            if (written > 0) return
            val poll = lastPoll
            if (poll != null && poll.isClosed) {
                poll.exceptionOrNull()?.let { fail(it); return }
                // Keep pulling silence while the last frames leave the backend's buffer, as long as Android waits.
                tailMs += ms
                if (tailMs >= TAIL_MS) succeed()
            }
        }

        private fun nextSample(step: Double): Short? {
            while (true) {
                val pcm = playing
                if (pcm != null) {
                    val size = pcm.size / 2
                    if (position < size) {
                        val index = position.toInt()
                        val a = sampleAt(pcm, index)
                        // The last sample leans toward the next frame's first, when it has arrived.
                        val b = if (index + 1 < size) sampleAt(pcm, index + 1) else upcoming()?.let { sampleAt(it, 0) } ?: a
                        val sample = a + (b - a) * (position - index)
                        position += step
                        return sample.roundToInt().toShort()
                    }
                    position -= size
                    pcm.fill(0)
                    playing = null
                }
                playing = upcoming() ?: return null
                next = null
            }
        }

        /** The frame after the playing one, received but not yet started. */
        private fun upcoming(): ByteArray? {
            next?.let { return it }
            val received = frames.tryReceive()
            lastPoll = received
            return received.getOrNull()?.also { next = it }
        }

        private fun sampleAt(pcm: ByteArray, index: Int): Int =
            ((pcm[index * 2].toInt() and 255) or (pcm[index * 2 + 1].toInt() shl 8)).toShort().toInt()
        private fun succeed() {
            if (terminal.compareAndSet(null, completed)) {
                stopped.set(true)
                done.complete(Unit)
            }
        }
        fun stop() { stopped.set(true); frames.cancel(); sound.cancel() }
    }

    private companion object {
        const val DECODED_RATE = 48_000
        const val QUIET_MS = 600L
        const val TAIL_MS = 400L
    }
}
