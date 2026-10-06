package dev.hermeskotlin.core.voice

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/*
 * GPT-Live, Desktop's live voice mode (`voice.voice_chat_mode: gpt-live`): one OpenAI voice model listens
 * and talks at once over WebRTC and has no tools of its own. When the person asks for real work it sends
 * `session.delegation.created`; that becomes an ordinary Hermes turn in the open chat, and the reply goes
 * back as `session.commentary.append`, which the voice says in its own words. Hermes keeps its model,
 * tools, memory and approvals. The gateway makes the session with the OpenAI key, so the key stays there.
 *
 * Desktop: apps/desktop/src/lib/voice-live.ts. Vendor contract: https://developers.openai.com/api/docs/guides/live-delegation
 */

/** One piece of the call's running transcript. Times are milliseconds into the call. */
data class LiveFragment(val speaker: Speaker, val text: String, val startMs: Long, val endMs: Long) {
    enum class Speaker { User, Assistant }
}

/** A chat message the call opens with, so the voice knows what was said before it. */
@Serializable
data class LiveHistoryMessage(val role: String, val content: List<LiveText>, val type: String = "message")

@Serializable
data class LiveText(val type: String, val text: String)

/** What the voice model sends over the call's `oai-events` channel, the parts Herald acts on. */
sealed interface LiveEvent {
    data object Started : LiveEvent
    data class Transcript(val fragment: LiveFragment) : LiveEvent
    /** The voice handed the request to Hermes. The event carries no text; the transcript says what was asked. */
    data class Delegation(val id: String) : LiveEvent
    data class Failure(val message: String) : LiveEvent
    data class Closed(val reason: String, val usageSeconds: Double?) : LiveEvent
}

private val eventJson = Json { ignoreUnknownKeys = true }

/** Reads one `oai-events` message; null for events Herald has no use for, or garbage. */
fun parseLiveEvent(raw: String): LiveEvent? {
    val event = runCatching { eventJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
    return when (val type = event.text("type")) {
        "session.started" -> LiveEvent.Started
        "session.input_transcript.delta", "session.output_transcript.delta" -> LiveEvent.Transcript(
            LiveFragment(
                if (type == "session.input_transcript.delta") LiveFragment.Speaker.User else LiveFragment.Speaker.Assistant,
                event.text("delta").orEmpty(),
                event.number("start_ms")?.toLong() ?: 0,
                event.number("end_ms")?.toLong() ?: 0,
            ),
        )
        "session.delegation.created" -> (event["delegation"] as? JsonObject)?.text("id")?.let(LiveEvent::Delegation)
        "error" -> {
            val error = event["error"] as? JsonObject
            // A late append after the call was closed is expected noise.
            if (error?.text("code") == "context_injection_incomplete") null
            else LiveEvent.Failure(error?.text("message") ?: "GPT-Live error")
        }
        "session.closed" -> LiveEvent.Closed(event.text("reason") ?: "closed", (event["usage"] as? JsonObject)?.number("seconds"))
        else -> null
    }
}

/** The events Herald sends the voice model. [delegationId] ties progress and results to the request. */
object LiveCommands {
    /** Quiet progress the voice may mention ("still checking"), never read out as is. */
    fun thinking(id: String, delegationId: String?, content: String) = append("session.thinking.append", id, delegationId, content)

    /** A result for the voice to say in its own words. */
    fun commentary(id: String, delegationId: String?, content: String) = append("session.commentary.append", id, delegationId, content)

    /** Steers the voice for the rest of the call. */
    fun instructions(id: String, content: String) = append("session.instructions.append", id, null, content)

    fun mute(id: String, muted: Boolean) = buildJsonObject {
        put("event_id", id)
        put("type", if (muted) "session.input_audio.mute" else "session.input_audio.unmute")
    }.toString()

    fun close() = buildJsonObject { put("type", "session.close") }.toString()

    private fun append(type: String, id: String, delegationId: String?, content: String) = buildJsonObject {
        put("type", type)
        put("event_id", id)
        put("delegation_id", delegationId)
        put("content", content)
    }.toString()
}

/** The vendor takes at most 500 tokens per append; about four characters a token, with room to spare. */
const val LIVE_APPEND_CHARS = 1_400

/** Cuts a reply into append-sized pieces at sentence ends; a sentence too long for one is split. */
fun commentaryChunks(text: String, limit: Int = LIVE_APPEND_CHARS): List<String> {
    val clean = text.replace(WHITESPACE, " ").trim()
    if (clean.isEmpty()) return emptyList()
    if (clean.length <= limit) return listOf(clean)
    val chunks = mutableListOf<String>()
    var current = ""
    for (sentence in clean.split(SENTENCE_END)) {
        if (sentence.length > limit) {
            if (current.isNotEmpty()) chunks += current
            current = ""
            chunks += sentence.chunked(limit)
            continue
        }
        val candidate = if (current.isEmpty()) sentence else "$current $sentence"
        if (candidate.length > limit) {
            chunks += current
            current = sentence
        } else {
            current = candidate
        }
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}

/**
 * How much of a reply still streaming can be spoken: up to the last sentence or line end with nothing left
 * open before it (a code fence, a link, a table row). Markdown only turns quiet once it's closed, so the
 * part before the cut reads the same when the reply is done. 0 when there is no such place yet.
 */
fun speakableCut(markdown: String): Int {
    var cut = 0
    var fenced = false
    var lineStart = 0
    while (lineStart < markdown.length) {
        val newline = markdown.indexOf('\n', lineStart)
        val lineEnd = if (newline < 0) markdown.length else newline + 1
        val line = markdown.substring(lineStart, lineEnd)
        // speakableText pairs fences wherever they sit, not only at a line's start.
        val fences = Regex("```").findAll(line).count()
        when {
            fences > 0 -> if (fences % 2 == 1) fenced = !fenced
            fenced || line.trimStart().startsWith("|") -> Unit
            else -> for (end in SENTENCE_BREAK.findAll(line)) {
                val before = line.substring(0, end.range.last + 1)
                val linkOpen = before.count { it == '[' } > before.count { it == ']' } ||
                    before.lastIndexOf("](") > before.lastIndexOf(')')
                if (!linkOpen) cut = lineStart + end.range.last + 1
            }
        }
        if (newline >= 0 && !fenced) cut = lineEnd
        lineStart = lineEnd
    }
    return cut
}

/** The chat so far as the call's opening history: the newest text turns that fit, oldest first. */
fun liveHistory(turns: List<Pair<LiveFragment.Speaker, String>>, maxMessages: Int = 24, maxChars: Int = 6_000): List<LiveHistoryMessage> {
    val out = ArrayDeque<LiveHistoryMessage>()
    var budget = maxChars
    for ((speaker, raw) in turns.asReversed()) {
        val text = raw.replace(WHITESPACE, " ").trim().take(1_200)
        if (text.isEmpty()) continue
        if (out.size >= maxMessages || budget - text.length < 0) break
        budget -= text.length
        val assistant = speaker == LiveFragment.Speaker.Assistant
        out.addFirst(LiveHistoryMessage(if (assistant) "assistant" else "user", listOf(LiveText(if (assistant) "output_text" else "input_text", text))))
    }
    return out.toList()
}

/** What a delegation asks Hermes: [prompt] is what the person said last, [context] the exchange around it. */
data class DelegationPrompt(val prompt: String, val context: String)

/** Builds the Hermes turn for a delegation from the recent transcript. */
fun delegationPrompt(fragments: List<LiveFragment>): DelegationPrompt {
    val turns = mutableListOf<Pair<LiveFragment.Speaker, StringBuilder>>()
    for (fragment in fragments) {
        val last = turns.lastOrNull()
        if (last != null && last.first == fragment.speaker) last.second.append(fragment.text)
        else turns += fragment.speaker to StringBuilder(fragment.text)
    }
    val prompt = turns.lastOrNull { it.first == LiveFragment.Speaker.User }?.second?.toString()?.replace(WHITESPACE, " ")?.trim().orEmpty()
    val context = turns.mapNotNull { (speaker, text) ->
        val line = text.toString().replace(WHITESPACE, " ").trim()
        if (line.isEmpty()) null else "${if (speaker == LiveFragment.Speaker.User) "User" else "Voice assistant"}: $line"
    }.joinToString("\n")
    return DelegationPrompt(prompt.ifEmpty { context.takeLast(400) }, context)
}

/**
 * The phone's side of a GPT-Live call: microphone in, the voice out of the speaker, and the event channel.
 * One call per instance.
 */
interface LiveCall {
    /**
     * Opens the microphone, makes a WebRTC offer and hands it to [answer], which returns the vendor's answer.
     * Returns once the answer is applied; the call is then connecting.
     */
    suspend fun connect(answer: suspend (offerSdp: String) -> String)

    /** Messages from the event channel. Closes when the call drops. */
    val events: ReceiveChannel<String>

    /** Sends an event; false when the channel isn't open. */
    fun send(event: String): Boolean

    /** The voice is making sound right now; polled. */
    fun speaking(): Boolean

    /** The microphone level, 0..1; polled. */
    fun micLevel(): Float

    fun setMuted(muted: Boolean)

    /** Hangs up and frees the microphone and speaker. Safe to call more than once. */
    fun close()
}

/** Makes [LiveCall]s; null where the platform has no WebRTC. */
fun interface LiveCalls {
    fun create(): LiveCall?
}

private val WHITESPACE = Regex("""\s+""")
private val SENTENCE_END = Regex("""(?<=[.!?])\s+""")
private val SENTENCE_BREAK = Regex("""[.!?] """)
