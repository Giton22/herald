package dev.hermeskotlin.core.voice

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

    /** Feeds one frame's [level] at [elapsedMs] into the recording; true once the recording should end. */
    fun onFrame(level: Float, elapsedMs: Long): Boolean {
        // The opening moment only measures the room; nobody starts talking that fast.
        if (elapsedMs <= CALIBRATION_MS) {
            noise = if (noise < 0) level else noise + (level - noise) * 0.2f
            return false
        }
        val speechBar = maxOf(activity.speechLevel, noise * SPEECH_OVER_NOISE)
        val quietBar = maxOf(activity.speechLevel * QUIET_FRACTION, noise * QUIET_OVER_NOISE)
        if (level >= speechBar) {
            heardSpeech = true
            silenceSince = -1
            // A steady loud room lifts the bar too, slowly enough that a long sentence doesn't.
            noise += (level - noise) * 0.002f
        } else {
            // The background noise follows the quiet frames: down quickly, up slowly.
            noise = when {
                noise < 0 -> level
                level < noise -> noise + (level - noise) * 0.3f
                else -> noise + (level - noise) * 0.02f
            }
            if (heardSpeech && level < quietBar) {
                if (silenceSince < 0) silenceSince = elapsedMs
                if (elapsedMs - silenceSince >= activity.silenceMs) return true
            } else if (heardSpeech) {
                silenceSince = -1
            }
        }
        if (!heardSpeech && activity.idleMs > 0 && elapsedMs >= activity.idleMs) return true
        return elapsedMs >= activity.maxMs
    }

    private companion object {
        const val CALIBRATION_MS = 300L
        const val SPEECH_OVER_NOISE = 2.5f
        const val QUIET_OVER_NOISE = 1.6f
        const val QUIET_FRACTION = 0.8f
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
}

private val FENCED_CODE = Regex("```[\\s\\S]*?(?:```|$)")
private val INLINE_CODE = Regex("`([^`]+)`")
private val MARKDOWN_LINK = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
private val URL = Regex("""\bhttps?://\S+""", RegexOption.IGNORE_CASE)
private val MEDIA_PATH = Regex("""[ \t]*MEDIA:\S+?(?=[.,;:!?)\]]*(?:\s|$))""")
private val LINE_FINAL_COLON = Regex(""":\s*$""", RegexOption.MULTILINE)
private val HEADING = Regex("""^#{1,6}\s+""", RegexOption.MULTILINE)
private val BULLET = Regex("""^\s*[-+*]\s+""", RegexOption.MULTILINE)
private val TABLE_RULE = Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$""", RegexOption.MULTILINE)
private val TABLE_ROW = Regex("""^\s*\|.*\|\s*$""", RegexOption.MULTILINE)
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
    .withoutEmoji()
    .replace(HEADING, "")
    .replace(BULLET, "")
    .replace(MARKUP, "")
    .replace(WHITESPACE, " ")
    .trim()

/**
 * Each run of emoji (U+1F000–U+1FAFF, the U+2600–U+27BF symbols, and the joiners and variation selector that
 * build them) as one space. By hand: Kotlin/Native's regex has no `\x{…}` for code points beyond U+FFFF.
 */
private fun String.withoutEmoji(): String {
    val out = StringBuilder(length)
    var inRun = false
    var i = 0
    while (i < length) {
        val c = this[i]
        val pair = c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()
        val codePoint = if (pair) 0x10000 + ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00) else c.code
        val emoji = codePoint in 0x1F000..0x1FAFF || codePoint in 0x2600..0x27BF || codePoint == 0xFE0F || codePoint == 0x200D
        if (emoji) {
            if (!inRun) out.append(' ')
        } else {
            out.append(c)
            if (pair) out.append(this[i + 1])
        }
        inRun = emoji
        i += if (pair) 2 else 1
    }
    return out.toString()
}

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
/** Unicode punctuation (`\p{P}`), which Kotlin/Native's regex doesn't know. */
private val PUNCTUATION = setOf(
    CharCategory.CONNECTOR_PUNCTUATION, CharCategory.DASH_PUNCTUATION, CharCategory.START_PUNCTUATION, CharCategory.END_PUNCTUATION,
    CharCategory.INITIAL_QUOTE_PUNCTUATION, CharCategory.FINAL_QUOTE_PUNCTUATION, CharCategory.OTHER_PUNCTUATION,
)

/**
 * A whole utterance that only says "stop" (or "never mind", "goodbye", …), optionally addressed to
 * Hermes, ends the conversation instead of being sent. "Stop the container" is a real request.
 */
fun isVoiceStopCommand(transcript: String): Boolean {
    var text = transcript.lowercase().map { if (it.category in PUNCTUATION) ' ' else it }.joinToString("").replace(WHITESPACE, " ").trim()
    ADDRESS_PREFIXES.firstOrNull { text.startsWith("$it ") }?.let { text = text.removePrefix(it).trim() }
    return text in STOP_PHRASES
}
