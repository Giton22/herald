package dev.hermeskotlin.core.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
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
    override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording = withContext(Dispatchers.IO) {
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
                val heardBefore = endOfSpeech.heardSpeech
                val done = endOfSpeech.onFrame(level, elapsed)
                if (endOfSpeech.heardSpeech && !heardBefore) onSpeech()
                if (done) break
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

    /**
     * The call microphone, with the phone's echo cancelling (and noise suppression) on it, so the voice coming out
     * of the speaker is mostly gone from what it hears. The last [BargeIn.PRE_ROLL_MS] are always kept; once
     * someone cuts in, the recording goes on from there until they pause.
     */
    @SuppressLint("MissingPermission") // Only during a voice chat, which asked for RECORD_AUDIO.
    override suspend fun listenOver(
        bargeIn: BargeIn,
        playing: () -> Boolean,
        onTrip: () -> Unit,
        onLevel: (Float) -> Unit,
        onListening: () -> Unit,
    ): Recording? =
        withContext(Dispatchers.IO) {
            finishRequested = false
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, FRAME_SAMPLES * 2 * 4),
                )
            } catch (_: Exception) {
                return@withContext null
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return@withContext null
            }
            val effects = ArrayList<AudioEffect>(2)
            val frame = ShortArray(FRAME_SAMPLES)
            val preRoll = ArrayDeque<ByteArray>()
            val preRollFrames = (BargeIn.PRE_ROLL_MS / FRAME_MS).toInt()
            val pcm = ByteArrayOutputStream()
            var elapsed = 0L
            var tripped = false
            try {
                // Inside the try, so the recorder is let go whatever they do; one that won't switch on is done without.
                if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId) }.getOrNull()?.let(effects::add)
                if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId) }.getOrNull()?.let(effects::add)
                effects.forEach { runCatching { it.enabled = true } }
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext null
                onListening()
                while (true) {
                    ensureActive()
                    val read = record.read(frame, 0, frame.size)
                    if (read < 0) return@withContext null
                    if (read == 0) continue
                    val bytes = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                    var sum = 0.0
                    for (i in 0 until read) {
                        bytes.putShort(frame[i])
                        sum += frame[i].toDouble() * frame[i]
                    }
                    elapsed += read * 1000L / SAMPLE_RATE
                    val level = min(1.0, sqrt(sum / read) / 256.0 / 42.0).toFloat()
                    if (tripped) {
                        pcm.write(bytes.array())
                        onLevel(level)
                    } else {
                        preRoll.addLast(bytes.array())
                        if (preRoll.size > preRollFrames) preRoll.removeFirst()
                    }
                    when (bargeIn.onFrame(level, elapsed, playing())) {
                        BargeIn.Step.Tripped -> {
                            tripped = true
                            // Send now counts from here: one asked before anyone cut in isn't about what they say.
                            finishRequested = false
                            preRoll.forEach(pcm::write)
                            preRoll.clear()
                            onTrip()
                        }
                        BargeIn.Step.Done -> break
                        BargeIn.Step.Waiting, BargeIn.Step.Hearing -> Unit
                    }
                    // Send now, once it has heard them.
                    if (tripped && finishRequested) break
                }
            } finally {
                runCatching { record.stop() }
                // One that throws mustn't keep the microphone from being let go.
                effects.forEach { runCatching { it.release() } }
                record.release()
                onLevel(0f)
            }
            Recording(wav(pcm.toByteArray()), "audio/wav", heardSpeech = bargeIn.spoke || finishRequested)
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
        const val FRAME_MS = 20L
    }
}

/**
 * Plays each reply clip from a cache file with [MediaPlayer], and streamed speech straight into an
 * [AudioTrack] as it arrives, both as an assistant voice.
 */
class AndroidSpeechPlayer(private val context: Context) : SpeechPlayer {
    override suspend fun playRingback() {
        val bytes = withContext(Dispatchers.IO) { context.assets.open("call_ringback.wav").use { it.readBytes() } }
        play(SpokenAudio(bytes, "audio/wav"))
    }

    private val lock = Any()
    /** Held ([hold]): what plays now is paused, and what's ready meanwhile waits to start. */
    private var holding = false
    private var track: AudioTrack? = null
    /** A clip once it's ready to play. */
    private var clip: MediaPlayer? = null

    override fun hold(on: Boolean): Boolean {
        synchronized(lock) {
            holding = on
            track?.let { runCatching { if (on) it.pause() else it.play() } }
            clip?.let { runCatching { if (on) { if (it.isPlaying) it.pause() } else if (!it.isPlaying) it.start() } }
        }
        return true
    }

    override suspend fun playPcm(sampleRate: Int, channels: Int, chunks: ReceiveChannel<ByteArray>, onSound: (Boolean) -> Unit) = coroutineScope {
        val mask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> error("Can't play $channels-channel speech.")
        }
        // A frame is a 16-bit sample for each channel.
        val frameBytes = 2 * channels
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build(),
            )
            // Half a second of room: enough to ride out the network's hiccups, little enough to stop at once.
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT), sampleRate * channels))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // Frames handed to the track; the head position counts the ones already heard.
        val written = AtomicLong()
        fun played(): Long = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        val sound = launch(Dispatchers.Default) {
            var on = false
            var quietSince = 0L
            while (true) {
                val now = System.currentTimeMillis()
                val pending = played() < written.get()
                if (pending && !on) {
                    on = true
                    onSound(true)
                } else if (!pending && on) {
                    if (quietSince == 0L) quietSince = now
                    // The short gaps between sentences aren't the voice stopping.
                    if (now - quietSince >= QUIET_MS) {
                        on = false
                        onSound(false)
                    }
                }
                if (pending) quietSince = 0L
                delay(POLL_MS)
            }
        }
        try {
            withContext(Dispatchers.IO) {
                synchronized(lock) {
                    this@AndroidSpeechPlayer.track = track
                    // Held, it fills its buffer and waits to be let go.
                    if (!holding) track.play()
                }
                // Provider chunks needn't end on a whole frame; what's left over waits for the next chunk.
                var carry = ByteArray(0)
                for (chunk in chunks) {
                    val bytes = if (carry.isEmpty()) chunk else carry + chunk
                    val usable = bytes.size - bytes.size % frameBytes
                    carry = bytes.copyOfRange(usable, bytes.size)
                    var offset = 0
                    while (offset < usable) {
                        ensureActive()
                        val n = track.write(bytes, offset, usable - offset, AudioTrack.WRITE_NON_BLOCKING)
                        if (n < 0) error("Couldn't play the reply ($n).")
                        offset += n
                        written.addAndGet(n.toLong() / frameBytes)
                        if (n == 0) delay(WRITE_WAIT_MS)
                    }
                }
                // What the track still holds, counted before stopping: a stopped track's position can read 0, so
                // waiting for it to catch up would sit through the whole reply's length again in silence.
                val left = (written.get() - played()).coerceAtLeast(0)
                // Nor can it tell sound from silence any more: what's showing now stays until the end.
                sound.cancelAndJoin()
                // Stopping a streaming track plays out what it holds first. Held meanwhile, it pauses where it is and
                // goes on when let go, so the wait does too.
                track.stop()
                var draining = left * 1000 / sampleRate + DRAIN_SLACK_MS
                while (draining > 0) {
                    val step = min(draining, DRAIN_STEP_MS)
                    delay(step)
                    if (!synchronized(lock) { holding }) draining -= step
                }
            }
        } finally {
            withContext(NonCancellable) { sound.cancelAndJoin() }
            onSound(false)
            synchronized(lock) { if (this@AndroidSpeechPlayer.track === track) this@AndroidSpeechPlayer.track = null }
            runCatching {
                track.pause()
                track.flush()
            }
            track.release()
        }
    }

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
                player.setOnCompletionListener {
                    // Done: letting go of a hold mustn't start it over.
                    synchronized(lock) { if (clip === player) clip = null }
                    if (continuation.isActive) continuation.resume(Unit)
                }
                player.setOnErrorListener { _, what, _ ->
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Couldn't play the reply ($what)."))
                    true
                }
                player.setDataSource(file.absolutePath)
                player.setOnPreparedListener {
                    synchronized(lock) {
                        clip = it
                        if (!holding) it.start()
                    }
                }
                player.prepareAsync()
                continuation.invokeOnCancellation { runCatching { player.stop() } }
            }
        } finally {
            synchronized(lock) { if (clip === player) clip = null }
            player.release()
            file.delete()
        }
    }

    private companion object {
        const val POLL_MS = 50L
        const val QUIET_MS = 600L
        const val WRITE_WAIT_MS = 10L
        const val DRAIN_SLACK_MS = 400L
        /** How often the drain looks whether it's held. */
        const val DRAIN_STEP_MS = 50L
    }
}
