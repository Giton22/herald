package dev.hermeskotlin.wear

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.sqrt

/** The microphone couldn't be opened: the screen says so in the watch's language. */
class MicrophoneUnavailableException : IllegalStateException("The microphone isn't available.")

/**
 * Records what's said for the gateway to transcribe, on a watch without a speech recognizer of its own: 16 kHz
 * mono WAV, as the phone records, ending a moment after the speaker stops, or on [finish]. The end of speech is
 * the phone's rule, simplified: speech must stand clearly above the room's noise measured at the start.
 */
class WatchRecorder {

    @Volatile private var finishRequested = false

    /** The recording as WAV, or null when nothing was said. [onLevel] gets the input level, 0..1. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun record(onLevel: (Float) -> Unit): ByteArray? = withContext(Dispatchers.IO) {
        finishRequested = false
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME * 2 * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw MicrophoneUnavailableException()
        }
        val pcm = ByteArrayOutputStream()
        val frame = ShortArray(FRAME)
        val bytes = ByteBuffer.allocate(FRAME * 2).order(ByteOrder.LITTLE_ENDIAN)
        var elapsed = 0L
        var noise = -1f
        var heard = false
        var quietSince = -1L
        try {
            record.startRecording()
            while (!finishRequested) {
                ensureActive()
                val read = record.read(frame, 0, frame.size)
                if (read <= 0) continue
                bytes.clear()
                var sum = 0.0
                for (i in 0 until read) {
                    bytes.putShort(frame[i])
                    sum += frame[i].toDouble() * frame[i]
                }
                pcm.write(bytes.array(), 0, read * 2)
                elapsed += read * 1000L / SAMPLE_RATE
                val level = min(1.0, sqrt(sum / read) / 256.0 / 42.0).toFloat()
                onLevel(level)
                if (elapsed <= CALIBRATION_MS) {
                    noise = if (noise < 0) level else noise + (level - noise) * 0.2f
                    continue
                }
                val speechBar = maxOf(SPEECH_LEVEL, noise * 2.5f)
                if (level >= speechBar) {
                    heard = true
                    quietSince = -1
                } else if (heard && level < maxOf(SPEECH_LEVEL * 0.8f, noise * 1.6f)) {
                    if (quietSince < 0) quietSince = elapsed
                    if (elapsed - quietSince >= SILENCE_MS) break
                } else {
                    quietSince = -1
                }
                if (!heard && elapsed >= IDLE_MS) break
                if (elapsed >= MAX_MS) break
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            onLevel(0f)
        }
        if (heard || finishRequested && pcm.size() > SAMPLE_RATE) wav(pcm.toByteArray()) else null
    }

    fun finish() {
        finishRequested = true
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + pcm.size)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(pcm.size)
        }
        return header.array() + pcm
    }

    companion object {
        const val MIME_TYPE = "audio/wav"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME = SAMPLE_RATE / 50
        private const val CALIBRATION_MS = 300L
        private const val SPEECH_LEVEL = 0.025f
        private const val SILENCE_MS = 1_250L
        private const val IDLE_MS = 8_000L
        private const val MAX_MS = 60_000L
    }
}
