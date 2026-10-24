package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.chat.extractReplyMedia
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeIntLe
import kotlinx.io.writeShortLe

/**
 * How a recording decides you're done talking. The timings are Desktop's (tools.voice_mode); the
 * level is lower, since a phone's speech-recognition mic is unboosted where the browser's isn't.
 */
data class VoiceActivity(
    /** The least normalized level (0..1) that counts as speech; noise around it raises the bar. */
    val speechLevel: Float = 0.025f,
    /** Quiet this long after speech ends the recording. */
    val silenceMs: Long = 1_250,
    /** With no speech at all, give up after this long; 0 waits for [VoiceRecorder.finish]. */
    val idleMs: Long = 12_000,
    /** Hard cap on one recording. */
    val maxMs: Long = 60_000,
)

/**
 * Decides, frame by frame, when someone has finished talking. Desktop's fixed level assumes a quiet
 * room; a phone is often somewhere noisier, so the bar also rises with the measured background
 * noise: speech must stand clearly above it, and quiet is anything back near it.
 */
class EndOfSpeech(private val activity: VoiceActivity) {
    var heardSpeech = false
        private set

    /** The measured background level, for diagnostics; negative until the first frame. */
    var noise = -1f
        private set
    private var silenceSince = -1L
    /** Since when the level has stood above quiet, in a pause after speech; -1 when it hasn't. */
    private var loudSince = -1L
    /** Where the last frame ended, which is where this one starts. */
    private var lastFrameEnd = 0L

    /** Feeds one frame's [level] at [elapsedMs] into the recording; true once the recording should end. */
    fun onFrame(level: Float, elapsedMs: Long): Boolean {
        val frameStart = lastFrameEnd
        lastFrameEnd = elapsedMs
        // The opening moment only measures the room; nobody starts talking that fast.
        if (elapsedMs <= CALIBRATION_MS) {
            noise = if (noise < 0) level else noise + (level - noise) * 0.2f
            return false
        }
        val speechBar = maxOf(activity.speechLevel, noise * SPEECH_OVER_NOISE)
        val quietBar = maxOf(activity.speechLevel * QUIET_FRACTION, noise * QUIET_OVER_NOISE)
        val speech = level >= speechBar
        if (speech) {
            heardSpeech = true
            // A steady loud room lifts the bar too, slowly enough that a long sentence doesn't.
            noise += (level - noise) * 0.002f
        } else {
            // The background noise follows the quiet frames: down quickly, up slowly.
            noise = when {
                noise < 0 -> level
                level < noise -> noise + (level - noise) * 0.3f
                else -> noise + (level - noise) * 0.02f
            }
        }
        if (heardSpeech && level < quietBar) {
            loudSince = -1
            if (silenceSince < 0) silenceSince = elapsedMs
            if (elapsedMs - silenceSince >= activity.silenceMs) return true
        } else if (heardSpeech) {
            // Road noise, wind or a fan poke above the quiet bar for a moment; only a run of it is someone
            // talking again, so a blip doesn't start the pause over (#185). A run counts from its first frame's
            // start. Speech needs only a syllable's worth, so clipped words still hold; a level short of speech,
            // which noise reaches far more often, needs longer.
            if (loudSince < 0) loudSince = frameStart
            if (elapsedMs - loudSince >= if (speech) SPEECH_RUN_MS else MURMUR_RUN_MS) silenceSince = -1
        }
        if (!heardSpeech && activity.idleMs > 0 && elapsedMs >= activity.idleMs) return true
        return elapsedMs >= activity.maxMs
    }

    private companion object {
        const val CALIBRATION_MS = 300L
        const val SPEECH_OVER_NOISE = 2.5f
        const val QUIET_OVER_NOISE = 1.6f
        const val QUIET_FRACTION = 0.8f
        /** How long the level has to stay at speech, in a pause, to count as talking again: a syllable's worth. */
        const val SPEECH_RUN_MS = 60L
        /** The same for a level between quiet and speech, which noise reaches more often. */
        const val MURMUR_RUN_MS = 200L
    }
}

/** One finished recording. [heardSpeech] is false when it never rose above the speech level. */
class Recording(val bytes: ByteArray, val mimeType: String, val heardSpeech: Boolean)

/** The device microphone. One recording at a time. */
interface VoiceRecorder {
    /**
     * Records until [activity] says the speaker is done, [finish] is called, or the caller is
     * cancelled (which discards it). [onLevel] gets the live input level, 0..1; [onSpeech] fires
     * once, when speech is first heard.
     */
    suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit = {}, onSpeech: () -> Unit = {}): Recording

    /** Ends the recording in progress now and keeps what was said. */
    fun finish()

    /**
     * Listens while the agent works and speaks, for someone talking over it ([bargeIn] decides), with the echo
     * cancelling a call uses. [playing] says whether the voice is sounding right now; [onTrip] fires the moment
     * someone cuts in, and [onLevel] gets the input level from then on. [onListening] fires once the microphone is
     * actually listening. Returns what they said, from its first syllable, once they pause; null when the microphone
     * couldn't listen. Cancelling stops it. Its [Recording.heardSpeech] says whether they went on talking once the
     * voice held ([BargeIn.spoke]), which its own echo doesn't.
     *
     * A recorder that can't listen over the voice never returns, so nobody ever cuts in.
     */
    suspend fun listenOver(
        bargeIn: BargeIn,
        playing: () -> Boolean,
        onTrip: () -> Unit,
        onLevel: (Float) -> Unit = {},
        onListening: () -> Unit = {},
    ): Recording? = awaitCancellation()
}

/**
 * The device's own speech recognizer, for dictation that never goes to the gateway: it writes words
 * down as they are said and ends on its own when the speaker stops.
 */
interface DeviceDictation {
    /** Whether this device has a recognizer to use. */
    fun available(): Boolean

    /**
     * Listens until the speaker is done, [finish] is called, or the caller is cancelled (which discards
     * it). [onPartial] gets the words so far, [onLevel] the input level, 0..1. Returns what was said,
     * blank when nothing was heard; throws [DeviceDictationUnavailable] when no recognizer could start,
     * and otherwise with a readable message when the recognizer fails.
     */
    suspend fun listen(onPartial: (String) -> Unit = {}, onLevel: (Float) -> Unit = {}): String

    /** Ends the listening in progress now and keeps what was said. */
    fun finish()
}

/** No recognizer on the device could start listening; the gateway transcribes instead. */
class DeviceDictationUnavailable : Exception("No speech recognizer on this phone could start.")

/** A device without a speech recognizer: dictation always goes to the gateway. */
object NoDeviceDictation : DeviceDictation {
    override fun available() = false
    override suspend fun listen(onPartial: (String) -> Unit, onLevel: (Float) -> Unit): String = throw DeviceDictationUnavailable()
    override fun finish() = Unit
}

/** Plays synthesized speech through the device speaker. */
interface SpeechPlayer {
    /** Plays [audio] to the end; cancelling stops it. */
    suspend fun play(audio: SpokenAudio)

    /**
     * Holds the voice where it is, and what starts meanwhile, until it's let go, when it goes on from there.
     * False for a player that can't, whose voice goes on regardless.
     */
    fun hold(on: Boolean): Boolean = false

    /**
     * Plays 16-bit little-endian PCM at [sampleRate], [channels] interleaved, as [chunks] arrive, until the
     * channel is closed and all of it has played; cancelling stops it at once. [onSound] says when sound starts
     * coming out, and when it has stopped for a moment, as it does while the agent works between sentences.
     *
     * A player that can't stream plays it all at once when the channel closes.
     */
    suspend fun playPcm(sampleRate: Int, channels: Int, chunks: ReceiveChannel<ByteArray>, onSound: (Boolean) -> Unit = {}) {
        val pcm = Buffer()
        for (chunk in chunks) pcm.write(chunk)
        if (pcm.size == 0L) return
        onSound(true)
        try {
            play(SpokenAudio(wav(pcm.readByteArray(), sampleRate, channels), "audio/wav"))
        } finally {
            onSound(false)
        }
    }
}

/** [pcm] (16-bit, [channels] interleaved) as a WAV file. */
fun wav(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
    val header = Buffer().apply {
        write("RIFF".encodeToByteArray())
        writeIntLe(36 + pcm.size)
        write("WAVEfmt ".encodeToByteArray())
        writeIntLe(16)
        writeShortLe(1.toShort()) // PCM
        writeShortLe(channels.toShort())
        writeIntLe(sampleRate)
        writeIntLe(sampleRate * channels * 2)
        writeShortLe((channels * 2).toShort())
        writeShortLe(16.toShort())
        write("data".encodeToByteArray())
        writeIntLe(pcm.size)
    }
    return header.readByteArray() + pcm
}

private val FENCED_CODE = Regex("```[\\s\\S]*?(?:```|$)")
private val INLINE_CODE = Regex("`([^`]+)`")
private val MARKDOWN_LINK = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
private val URL = Regex("""\b(?:https?|file)://\S+""", RegexOption.IGNORE_CASE)
private val MEDIA_PATH = Regex("""[ \t]*MEDIA:\S+?(?=[.,;:!?)\]]*(?:\s|$))""")
private val LINE_FINAL_COLON = Regex(""":\s*$""", RegexOption.MULTILINE)
private val HEADING = Regex("""^#{1,6}\s+""", RegexOption.MULTILINE)
private val BULLET = Regex("""^\s*[-+*]\s+""", RegexOption.MULTILINE)
private val TABLE_RULE = Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$""", RegexOption.MULTILINE)
private val TABLE_ROW = Regex("""^\s*\|.*\|\s*$""", RegexOption.MULTILINE)
private val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]+")
private val MARKUP = Regex("[*_~>#|]")
private val WHITESPACE = Regex("""\s+""")

/**
 * A reply as it should sound (Desktop's sanitizeTextForSpeech, trimmed): code, links, file chips,
 * tables and emoji are silence rather than read out symbol by symbol.
 */
fun speakableText(markdown: String): String = markdown
    .replace(FENCED_CODE, "")
    .replace(TABLE_RULE, "")
    .replace(TABLE_ROW, "")
    .replace(LINE_FINAL_COLON, ".")
    .replace(MARKDOWN_LINK, "$1")
    .replace(INLINE_CODE, "$1")
    .replace(URL, "")
    .replace(MEDIA_PATH, "")
    .replace(EMOJI, " ")
    .replace(HEADING, "")
    .replace(BULLET, "")
    .replace(MARKUP, "")
    .replace(WHITESPACE, " ")
    .trim()

/**
 * A finished reply as its Read aloud action says it: [speakableText] once the pictures and files it delivered
 * are taken out, since they show as themselves. Empty when there's nothing to say: a reply of only code, say,
 * or only punctuation, which the gateway would have no words for.
 */
fun readAloudText(reply: String): String =
    speakableText(extractReplyMedia(reply).first).takeIf { text -> text.any { it.isLetterOrDigit() } }.orEmpty()

/**
 * Cuts [text] into pieces of at most about [maxChars], at sentence ends where possible, so the first
 * piece can be spoken while the rest are still being synthesized. The first piece is kept short.
 */
fun speechChunks(text: String, maxChars: Int = 400, firstChars: Int = 160): List<String> {
    val sentences = Regex("""[^.!?]+(?:[.!?]+["')\]]*|$)\s*""").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    for (sentence in sentences) {
        val limit = if (chunks.isEmpty()) firstChars else maxChars
        if (current.isNotEmpty() && current.length + sentence.length + 1 > limit) {
            chunks += current.toString()
            current.clear()
        }
        if (current.isNotEmpty()) current.append(' ')
        current.append(sentence)
    }
    if (current.isNotEmpty()) chunks += current.toString()
    return chunks
}

private val STOP_PHRASES = setOf(
    "stop", "stop listening", "stop it", "stop please", "please stop", "stop stop", "that is all", "that's all",
    "never mind", "nevermind", "end conversation", "end the conversation", "goodbye", "good bye", "bye", "cancel",
)
private val ADDRESS_PREFIXES = listOf("hey hermes", "hermes", "okay", "ok", "hey")
private val PUNCTUATION = Regex("""[\p{P}]+""")

/**
 * A whole utterance that only says "stop" (or "never mind", "goodbye", …), optionally addressed to
 * Hermes, ends the conversation instead of being sent. "Stop the container" is a real request.
 */
fun isVoiceStopCommand(transcript: String): Boolean {
    var text = transcript.lowercase().replace(PUNCTUATION, " ").replace(WHITESPACE, " ").trim()
    ADDRESS_PREFIXES.firstOrNull { text.startsWith("$it ") }?.let { text = text.removePrefix(it).trim() }
    return text in STOP_PHRASES
}
