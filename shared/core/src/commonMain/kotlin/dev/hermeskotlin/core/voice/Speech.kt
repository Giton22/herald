package dev.hermeskotlin.core.voice

/** How a recording decides you're done talking; Desktop's values, which mirror tools.voice_mode. */
data class VoiceActivity(
    /** Normalized level (0..1) that counts as speech. */
    val speechLevel: Float = 0.075f,
    /** Quiet this long after speech ends the recording. */
    val silenceMs: Long = 1_250,
    /** With no speech at all, give up after this long; 0 waits for [VoiceRecorder.finish]. */
    val idleMs: Long = 12_000,
    /** Hard cap on one recording. */
    val maxMs: Long = 60_000,
)

/** One finished recording. [heardSpeech] is false when it never rose above the speech level. */
class Recording(val bytes: ByteArray, val mimeType: String, val heardSpeech: Boolean)

/** The device microphone. One recording at a time. */
interface VoiceRecorder {
    /**
     * Records until [activity] says the speaker is done, [finish] is called, or the caller is
     * cancelled (which discards it). [onLevel] gets the live input level, 0..1.
     */
    suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit = {}): Recording

    /** Ends the recording in progress now and keeps what was said. */
    fun finish()
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
