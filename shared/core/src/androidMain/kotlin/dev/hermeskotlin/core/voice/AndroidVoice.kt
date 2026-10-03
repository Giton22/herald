package dev.hermeskotlin.core.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Records 16 kHz mono PCM with [AudioRecord] and hands it over as WAV, which every speech-to-text
 * provider takes. The level is Desktop's: RMS of the waveform, scaled so 1.0 is loud speech.
 */
class AndroidVoiceRecorder : VoiceRecorder {

    @Volatile private var finishRequested = false

    @SuppressLint("MissingPermission") // The UI asks for RECORD_AUDIO before starting.
    override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit): Recording = withContext(Dispatchers.IO) {
        finishRequested = false
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME_SAMPLES * 2 * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("The microphone isn't available.")
        }
        // Noise suppression only: automatic gain lifts the room's hum in every pause until it reads as
        // talking, and the end of speech is never found.
        val effects = listOfNotNull(
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null,
        )
        val pcm = ByteArrayOutputStream()
        val frame = ShortArray(FRAME_SAMPLES)
        val bytes = ByteBuffer.allocate(FRAME_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
        val endOfSpeech = EndOfSpeech(activity)
        var elapsed = 0L
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
                // Desktop measures 8-bit samples (±128) against 42; 16-bit samples are 256 times larger.
                val level = min(1.0, sqrt(sum / read) / 256.0 / 42.0).toFloat()
                onLevel(level)
                if (endOfSpeech.onFrame(level, elapsed)) break
            }
        } finally {
            runCatching { record.stop() }
            effects.forEach { it.release() }
            record.release()
            onLevel(0f)
        }
        Recording(wav(pcm.toByteArray()), "audio/wav", endOfSpeech.heardSpeech || finishRequested && pcm.size() > SAMPLE_RATE / 2)
    }

    override fun finish() {
        finishRequested = true
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + pcm.size)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(pcm.size)
        }
        return header.array() + pcm
    }

    private companion object {
        const val SAMPLE_RATE = 16_000

        /** 20 ms frames: fine-grained enough for the silence timer. */
        const val FRAME_SAMPLES = SAMPLE_RATE / 50
    }
}

/** Plays each reply clip from a cache file with [MediaPlayer], as an assistant voice. */
class AndroidSpeechPlayer(private val context: Context) : SpeechPlayer {

    override suspend fun play(audio: SpokenAudio) {
        val extension = when {
            "ogg" in audio.mimeType || "opus" in audio.mimeType -> "ogg"
            "wav" in audio.mimeType -> "wav"
            "flac" in audio.mimeType -> "flac"
            else -> "mp3"
        }
        val file = withContext(Dispatchers.IO) {
            File.createTempFile("hermes-speech-", ".$extension", context.cacheDir).apply { writeBytes(audio.bytes) }
        }
        val player = MediaPlayer()
        try {
            suspendCancellableCoroutine { continuation ->
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                player.setOnCompletionListener { if (continuation.isActive) continuation.resume(Unit) }
                player.setOnErrorListener { _, what, _ ->
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Couldn't play the reply ($what)."))
                    true
                }
                player.setDataSource(file.absolutePath)
                player.setOnPreparedListener { it.start() }
                player.prepareAsync()
                continuation.invokeOnCancellation { runCatching { player.stop() } }
            }
        } finally {
            player.release()
            file.delete()
        }
    }
}
