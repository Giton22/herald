package dev.hermeskotlin.wearlink

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Data Layer paths Herald's phone and watch apps talk on. The watch asks and the phone answers: the phone
 * holds the gateway connection; the watch remembers routing scope but never a gateway sign-in.
 *
 * Messages carry JSON ([WearJson]). Audio, too large for a message, goes over a channel whose path says what
 * it holds ([recordingPath], [speechPath]).
 */
object WearPaths {
    /** Watch → phone: a [WatchAsk]. */
    const val ASK = "/herald/ask"
    /** Dedicated bot paths keep an older phone from interpreting these as ordinary-chat requests. */
    const val BOT_ASK = "/herald/bot/ask"

    /** Watch → phone: a [WatchStop], to end the running turn. */
    const val STOP = "/herald/stop"

    /** Watch → phone: an optional [ChatListRequest] for the recent chats. Phone → watch: a [ChatList]. */
    const val CHATS = "/herald/chats"

    /** Watch → phone: an optional [PhoneStatusRequest] asking how the phone stands. Phone → watch: a [PhoneStatus]. */
    const val STATUS = "/herald/status"

    /** Phone → watch: pull the current browsing context again. */
    const val PROFILE_CHANGED = "/herald/profile/changed"

    /** Phone → watch: a [ReplyUpdate], again each time the turn moves on. */
    const val REPLY = "/herald/reply"

    /** Watch → phone: a request ID; phone → watch: its resolved [DefaultCallTarget]. */
    const val DEFAULT_CALL = "/herald/call/default"
    /** Phone-owned display snapshot and optional image Asset, synchronized by Data Layer. */
    const val CALL_DISPLAY = "/herald/call/display"
    /** A [CallStart] carrying a validated default request ID; older phones ignore this path. */
    const val DEFAULT_CALL_START = "/herald/call/default/start"
    /** Watch → phone: a [CallStart], the watch's WebRTC offer for a selected conversation. */
    const val CALL_START = "/herald/call/start"
    const val BOT_CALL_START = "/herald/bot/call/start"

    /** Watch → phone: [CallEvents], what the voice model sent on the call's event channel. */
    const val CALL_EVENTS = "/herald/call/events"

    /** Phone → watch: [CallEvents] for the watch to send on the call's event channel. */
    const val CALL_SEND = "/herald/call/send"

    /** Phone → watch: a [CallUpdate], again each time the call moves on. */
    const val CALL_UPDATE = "/herald/call/update"

    /** Watch → phone: a [CallEnd], hung up on the watch. */
    const val CALL_END = "/herald/call/end"

    /** Phone → watch: a [WatchRing], Hermes calling for an answer. */
    const val RING = "/herald/ring"

    /**
     * Phone → watch: a [RingEnd], the ring is over (answered or declined on the phone, rung out, or answered
     * elsewhere). Watch → phone: a [RingEnd], picked up ([RingEnd.answered]) or declined on the watch.
     */
    const val RING_END = "/herald/ring/end"

    /**
     * Watch → phone: a [RingOn], the watch rings. Without it (no Herald there, notifications off, on a call) the
     * phone's own call shows on the watch, as any notification does.
     */
    const val RING_ON = "/herald/ring/on"

    /** Every path above and below starts with this; the apps' listeners filter on it. */
    const val PREFIX = "/herald/"

    private const val RECORDING = "/herald/recording/"
    private const val BOT_RECORDING = "/herald/bot/recording/"
    private const val SPEECH = "/herald/speech/"

    /**
     * Watch → phone channel: a spoken ask, for the gateway to transcribe. Its stream is a [RecordedAsk]: the
     * ask and the audio in one piece, so neither can arrive without the other.
     */
    fun recordingPath(askId: String, bot: Boolean = false): String = (if (bot) BOT_RECORDING else RECORDING) + encode(askId)

    /** Phone → watch channel: clip [index] of [count] reading the reply to [askId] aloud, as [mimeType]. */
    fun speechPath(askId: String, index: Int, count: Int, mimeType: String): String =
        SPEECH + encode(askId) + "/" + index + "/" + count + "/" + encode(mimeType)

    /** Whether [path] is a [recordingPath]. */
    fun isRecording(path: String): Boolean = path.startsWith(RECORDING) && path.length > RECORDING.length || isBotRecording(path)
    fun isBotRecording(path: String): Boolean = path.startsWith(BOT_RECORDING) && path.length > BOT_RECORDING.length

    /** What a [speechPath] says; null for any other path. */
    fun parseSpeech(path: String): SpeechClip? {
        val parts = path.removePrefix(SPEECH).takeIf { it != path }?.split('/') ?: return null
        if (parts.size != 4 || parts.any { it.isEmpty() }) return null
        val index = parts[1].toIntOrNull() ?: return null
        val count = parts[2].toIntOrNull() ?: return null
        if (index !in 0 until count) return null
        return SpeechClip(decode(parts[0]), index, count, decode(parts[3]))
    }

    data class SpeechClip(val askId: String, val index: Int, val count: Int, val mimeType: String)

    // A MIME type has a slash in it, and ids come from the other side: neither may cut the path in the wrong place.
    private fun encode(part: String): String = buildString {
        for (c in part) if (c.isLetterOrDigit() || c in "-_.") append(c) else append('%').append(c.code.toString(16).padStart(4, '0'))
    }

    private fun decode(part: String): String = buildString {
        var i = 0
        while (i < part.length) {
            val c = part[i]
            if (c == '%' && i + 5 <= part.length) {
                val code = part.substring(i + 1, i + 5).toIntOrNull(16)
                if (code != null) {
                    append(code.toChar())
                    i += 5
                    continue
                }
            }
            append(c)
            i++
        }
    }
}

/** The capability names each app declares (res/values/wear.xml), so each finds the other among the paired nodes. */
object WearCapabilities {
    const val PHONE = "herald_phone"
    const val WATCH = "herald_watch"
}

/** A conversation's owner; a present null profile means the gateway's launch profile. */
@Serializable
data class WatchScope(val gateway: String, val profile: String? = null)

/**
 * Something said or typed on the watch for Hermes. [chatId] is the stored chat it goes to, null for a new one;
 * the phone names the chat in its [ReplyUpdate]s, and the watch sends the next ask there. A spoken ask comes as
 * a [RecordedAsk] instead, its [text] blank and [audioType] the recording's type. [speak]: read the reply aloud.
 */
@Serializable
data class WatchAsk(
    val id: String,
    val text: String = "",
    val chatId: String? = null,
    val speak: Boolean = true,
    val audioType: String? = null,
    /** A bot's permanent conversation, resolved by the phone rather than a possibly stale stored id. */
    val botProfile: String? = null,
    val scope: WatchScope? = null,
)

/** What a [WearPaths.recordingPath] channel carries: the ask's length (4 bytes, big-endian), the ask, then the audio. */
object RecordedAsk {
    fun write(ask: WatchAsk, audio: ByteArray): ByteArray {
        val header = WearJson.encode(ask)
        val size = header.size
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) + header + audio
    }

    /** The ask and its audio; null when [bytes] aren't one. */
    fun read(bytes: ByteArray): Pair<WatchAsk, ByteArray>? {
        if (bytes.size < 4) return null
        val size = (bytes[0].toInt() and 0xFF shl 24) or (bytes[1].toInt() and 0xFF shl 16) or
            (bytes[2].toInt() and 0xFF shl 8) or (bytes[3].toInt() and 0xFF)
        if (size <= 0 || size > bytes.size - 4) return null
        val ask = WearJson.decode<WatchAsk>(bytes.copyOfRange(4, 4 + size)) ?: return null
        return ask to bytes.copyOfRange(4 + size, bytes.size)
    }
}

/** Stop the turn the ask [askId] started. */
@Serializable
data class WatchStop(val askId: String)

/** Where a turn the watch asked for stands. */
@Serializable
enum class ReplyPhase {
    /** The gateway is writing down what was said. */
    Transcribing,

    /** The prompt is on its way and the turn hasn't started. */
    Sending,

    /** The turn runs; [ReplyUpdate.text] is the reply so far. */
    Working,

    /** The agent waits on an approval or a question, answered on the phone. */
    WaitingOnPhone,

    /** The turn ended; [ReplyUpdate.text] is the whole reply. */
    Done,

    /** It didn't go: [ReplyUpdate.error] says why. */
    Failed,
}

/** Whether the reply will be heard. */
@Serializable
enum class ReplySpeech {
    /** Not asked for, or nothing to read. */
    Off,

    /** The gateway's voice is coming in clips on [WearPaths.speechPath]. */
    Coming,

    /** The gateway couldn't voice it; the watch reads it with its own voice. */
    Unavailable,
}

/**
 * The turn for the ask [askId] as it stands now; each one replaces the last. [heard] is what the gateway
 * transcribed from a recording. [activity] is a short line on what the agent is doing ("Running terminal").
 */
@Serializable
data class ReplyUpdate(
    val askId: String,
    val phase: ReplyPhase,
    /** Counts up through one ask's updates, so one that arrives late is told from the newer it would undo. */
    val seq: Int = 0,
    val chatId: String? = null,
    val title: String? = null,
    val heard: String? = null,
    val text: String = "",
    val activity: String? = null,
    val error: String? = null,
    val speech: ReplySpeech = ReplySpeech.Off,
    /** The chat's model, as people call it ("Claude Sonnet 4.5"), once the gateway has said. */
    val model: String? = null,
    /** How full the model's context window is, 0–100, once the gateway has said. */
    val contextPercent: Int? = null,
    val scope: WatchScope? = null,
)

/** One of the recent chats, for picking which one the watch talks in. [activityAt] is in epoch seconds. */
@Serializable
data class WatchChat(val id: String, val title: String, val activityAt: Double? = null)

/** A bot the watch can talk to; its one conversation is opened only when the user asks or calls. */
@Serializable
data class WatchBot(val profile: String, val title: String, val activityAt: Double? = null)

/** A correlated request for the current profile's recent conversations. */
@Serializable
data class ChatListRequest(val requestId: String)

/** The recent chats, newest first, or [error] when they couldn't be read. */
@Serializable
data class ChatList(
    val chats: List<WatchChat> = emptyList(), val error: String? = null,
    val bots: List<WatchBot> = emptyList(), val botError: String? = null,
    val requestId: String? = null,
    val scope: WatchScope? = null,
)

/** The phone's dark accent colors, stored with their revision so the watch can ask only for changes. */
@Serializable
data class WatchAccent(val revision: String, val fill: Int, val text: Int, val onFill: Int, val dim: Int) {
    fun updateFor(cachedRevision: String?): WatchAccent? = takeUnless { revision == cachedRevision }

    companion object {
        /** Its revision is the colors themselves, so any change to them reaches a watch that kept the old ones. */
        fun of(fill: Int, text: Int, onFill: Int, dim: Int): WatchAccent =
            WatchAccent(listOf(fill, text, onFill, dim).joinToString("-") { it.toUInt().toString(16) }, fill, text, onFill, dim)
    }
}

/**
 * Whether Herald on the phone is signed in to a gateway, and [connected]: connected to it, or ready to connect
 * when asked; false while it's (re)connecting or failing to. [failed]: it gave up, and won't connect on its own.
 */
@Serializable
data class PhoneStatusRequest(val botProfile: String? = null, val requestId: String? = null, val scope: WatchScope? = null, val accentRevision: String? = null)

@Serializable
data class PhoneStatus(
    val signedIn: Boolean,
    val connected: Boolean,
    val failed: Boolean = false,
    /**
     * The gateway talks live (GPT-Live): the watch can hold a voice call instead of sending what it heard. Null
     * when the phone hasn't asked the gateway yet (or is older): the watch keeps what it was last told.
     */
    val liveVoice: Boolean? = null,
    /** Older phones can't route a bot request; the watch must never send it as an ordinary chat. */
    val botChats: Boolean = false,
    val botProfile: String? = null,
    val defaultCalls: Boolean = false,
    val requestId: String? = null,
    val activeScope: WatchScope? = null,
    val profileSync: Boolean = false,
    val scope: WatchScope? = null,
    val accent: WatchAccent? = null,
)

/*
 * A live voice call from the watch. The watch holds the WebRTC call itself (its microphone and speaker, straight
 * to the voice model), but it has no gateway: the phone trades its offer for the model's answer through the
 * gateway, and runs the conversation. What the model sends on the event channel goes to the phone; when it hands
 * a request over, the phone runs it as a Hermes turn in the watch's chat and sends the reply back for the voice
 * to say, as Herald does for its own calls.
 */

/** Starts call [id] in [chatId] (null: a new chat), with the watch's WebRTC [offerSdp]. */
@Serializable
data class CallStart(val id: String, val chatId: String?, val offerSdp: String, val botProfile: String? = null, val defaultRequestId: String? = null, val scope: WatchScope? = null)

/** Event-channel messages of call [callId], in order; [seq] counts each side's batches, so a late one is told apart. */
@Serializable
data class CallEvents(val callId: String, val seq: Int, val events: List<String>)

@Serializable
data class CallEnd(val callId: String)

@Serializable
enum class CallPhase {
    /** The phone is getting the voice model's answer. */
    Connecting,

    /** [CallUpdate.answerSdp] is there to connect with; the call goes on. */
    Live,

    /** Over: hung up, "stop" said, or [CallUpdate.error]. */
    Ended,
}

/**
 * Where call [callId] stands, from the phone. [answerSdp] comes once, with the first [CallPhase.Live]. [caption]
 * is the latest words said (the voice's when [captionIsVoice]); [thinking] is true while Hermes works on a
 * request, with [working] the tool it runs.
 */
@Serializable
data class CallUpdate(
    val callId: String,
    val phase: CallPhase,
    val seq: Int = 0,
    val answerSdp: String? = null,
    val chatId: String? = null,
    val title: String? = null,
    val caption: String? = null,
    val captionIsVoice: Boolean = false,
    val thinking: Boolean = false,
    val working: String? = null,
    val error: String? = null,
    val model: String? = null,
    val contextPercent: Int? = null,
    val scope: WatchScope? = null,
)

/**
 * Hermes calling for an answer: approval or question [requestId] waits in chat [chatId] ([title]). Answering on the
 * watch starts a live call there, which asks it aloud. It never carries the question itself, only that there is one.
 * [agent]: the agent called on its own, and the call opens with why; nor does that reason travel here.
 */
@Serializable
data class WatchRing(
    val requestId: String,
    val chatId: String,
    val title: String,
    val approval: Boolean = false,
    val agent: Boolean = false,
)

/** The ring for [requestId] is over; from the watch, [answered]: picked up there, else declined. */
@Serializable
data class RingEnd(val requestId: String, val answered: Boolean = false)

/** The watch rings for [requestId]. */
@Serializable
data class RingOn(val requestId: String)

/** The event-channel message types the phone acts on; the watch keeps the rest (audio deltas and the like) to itself. */
val CALL_EVENT_TYPES = setOf(
    "session.started",
    "session.input_transcript.delta",
    "session.output_transcript.delta",
    "session.delegation.created",
    "session.closed",
    "error",
)

/** How every message on [WearPaths] is written. Unknown fields are skipped, so either app can be the newer. */
object WearJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    inline fun <reified T> encode(value: T): ByteArray = json.encodeToString(value).encodeToByteArray()

    /** Null when [bytes] aren't a [T]: a message from a much older or newer app shouldn't crash this one. */
    inline fun <reified T> decode(bytes: ByteArray): T? = try {
        json.decodeFromString<T>(bytes.decodeToString())
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * Puts a call's numbered batches ([CallEvents.seq], from 1) back in the order they were sent, since messages may
 * overtake each other: each goes to [deliver] once all before it have. A batch that never comes (its message
 * failed) holds the rest only until [skipGap], which [skipGaps] runs a moment after [waiting] turns true.
 * Not thread-safe: one thread uses it, [skipGaps] too.
 */
class InOrder<T>(private val deliver: (T) -> Unit) {
    private var last = 0
    private val early = mutableMapOf<Int, T>()

    /** Some came early, and wait for one missing. */
    val waiting: Boolean get() = early.isNotEmpty()

    /** The batch they wait for, null when none wait: the same after [CALL_GAP_MS], it's time to [skipGap]. */
    val missing: Int? get() = if (early.isEmpty()) null else last + 1

    fun add(seq: Int, item: T) {
        if (seq <= last || seq in early) return
        early[seq] = item
        drain()
    }

    /** Gives up on what's missing: the batches waiting go on without it. */
    fun skipGap() {
        if (early.isEmpty()) return
        last = early.keys.min() - 1
        drain()
    }

    /**
     * Skips each gap still there [gapMs] after it opened, until none wait: started when [waiting] turns true.
     * Past one gap may be another, and nothing more may come to start a new wait. Each gap gets its full wait:
     * one that opened during the last is skipped only when it's still there.
     */
    suspend fun skipGaps(gapMs: Long = CALL_GAP_MS) {
        while (waiting) {
            val gap = missing
            delay(gapMs)
            if (missing == gap) skipGap()
        }
    }

    private fun drain() {
        while (true) {
            val next = early.remove(last + 1) ?: return
            last++
            deliver(next)
        }
    }
}

/** How long a call's batches wait for one that's missing before going on without it. */
const val CALL_GAP_MS = 1_500L

/** The most of a reply one message carries; the Data Layer refuses a message much over 100 KB. */
const val MAX_WATCH_TEXT = 24_000

/** [text] cut to fit in a message, at a word where it can be, with an ellipsis where it was cut. */
fun clipForWatch(text: String, max: Int = MAX_WATCH_TEXT): String {
    if (text.length <= max) return text
    val cut = text.lastIndexOf(' ', max - 1).takeIf { it > max * 3 / 4 } ?: (max - 1)
    return text.substring(0, cut).trimEnd() + "…"
}

/**
 * Resolved on demand by the phone; cached artwork never determines the call destination. [live]: the target
 * talks live; when it doesn't, the watch asks there by voice instead of calling.
 */
@Serializable
data class DefaultCallTarget(val requestId: String, val chatId: String? = null, val botProfile: String? = null,
    val title: String = "New session", val error: String? = null, val scope: WatchScope? = null, val live: Boolean = true)

/** Display only: revision covers the current target and rendered image. */
@Serializable
data class CallDisplay(val revision: String, val title: String, val hasPicture: Boolean = false, val version: Int = 1, val targetKey: String = revision) {
    val valid: Boolean get() = version == 1 && targetKey.length == 64 && revision.length == 64 && revision.all { it in '0'..'9' || it in 'a'..'f' } && title.length <= 256
    companion object { const val MAX_IMAGE_BYTES = 64 * 1024 }
}
