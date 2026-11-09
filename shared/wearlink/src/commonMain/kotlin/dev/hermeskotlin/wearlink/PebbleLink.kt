package dev.hermeskotlin.wearlink

/*
 * What Kyllene on the phone and its Pebble watchapp (pebbleApp/) say to each other. A Pebble talks in AppMessage
 * dictionaries, small maps of numbered keys to integers or text, through the Pebble phone app (PebbleKit Android 2).
 * The phone keeps speaking WearLink's JSON to WatchRelay; the Pebble side of it decodes that JSON per path and
 * writes these dictionaries instead, so the watch never parses JSON. Each dictionary says what it is in [PebbleKeys.TYPE].
 */

/** One value in a Pebble dictionary: every number arrives on the phone as a 32-bit integer, whatever size the watch wrote. */
sealed interface PebbleValue {
    data class Num(val value: Int) : PebbleValue
    data class Text(val value: String) : PebbleValue
}

typealias PebbleDict = Map<Int, PebbleValue>

/** The dictionary keys. pebbleApp/package.json's `messageKeys` holds the same numbers; a test keeps them in step. */
object PebbleKeys {
    const val TYPE = 1
    const val ASK_ID = 2
    const val SEQ = 3
    const val PHASE = 4
    const val TEXT = 5
    /** Where [TEXT] goes in the watch's copy of the reply, in UTF-8 bytes: 0 replaces it, its length appends. */
    const val TEXT_OFFSET = 6
    /** How long the reply is on the phone so far, in UTF-8 bytes: more is coming while the watch has less. */
    const val TEXT_TOTAL = 7
    const val ACTIVITY = 8
    const val TITLE = 9
    const val ERROR = 10
    const val CHAT_ID = 11
    const val INDEX = 12
    const val COUNT = 13
    const val AGO = 14
    const val FLAGS = 15
    const val ACCENT_REV = 16
    const val ACCENT_FILL = 17
    const val ACCENT_TEXT = 18
    const val ACCENT_ON_FILL = 19
    const val ACCENT_DIM = 20
    const val REQUEST_ID = 21
    const val APPROVAL = 22
    const val AGENT = 23
    const val ANSWERED = 24
    const val VERSION = 25
    /** What was said for the turn on screen, when the watch didn't say it itself (a chat's last exchange). */
    const val SAID = 26
    /** A request's command or question. */
    const val DETAIL = 27
    /** A request's choices, one per line. */
    const val CHOICES = 28
    /** How the watch answers a request: 0 on the phone only, 1 by a choice, 2 by a choice or dictating. */
    const val ANSWERING = 29
    /** Which prompts the watch has, so the phone sends them only when they changed. */
    const val PROMPTS_REV = 30
    /** The prompts the watch's hold-Select menu offers, one per line. */
    const val PROMPTS = 31
    /**
     * The gateway and profile a chat belongs to ([PebbleLink.scopeText]): the watch keeps it with the chat it talks
     * in and sends it back with each ask, so one from another gateway or profile is refused, not sent there.
     */
    const val SCOPE = 32

    /** Every key by its name in package.json. */
    val byName: Map<String, Int> = mapOf(
        "TYPE" to TYPE, "ASK_ID" to ASK_ID, "SEQ" to SEQ, "PHASE" to PHASE, "TEXT" to TEXT,
        "TEXT_OFFSET" to TEXT_OFFSET, "TEXT_TOTAL" to TEXT_TOTAL, "ACTIVITY" to ACTIVITY, "TITLE" to TITLE,
        "ERROR" to ERROR, "CHAT_ID" to CHAT_ID, "INDEX" to INDEX, "COUNT" to COUNT, "AGO" to AGO, "FLAGS" to FLAGS,
        "ACCENT_REV" to ACCENT_REV, "ACCENT_FILL" to ACCENT_FILL, "ACCENT_TEXT" to ACCENT_TEXT,
        "ACCENT_ON_FILL" to ACCENT_ON_FILL, "ACCENT_DIM" to ACCENT_DIM, "REQUEST_ID" to REQUEST_ID,
        "APPROVAL" to APPROVAL, "AGENT" to AGENT, "ANSWERED" to ANSWERED, "VERSION" to VERSION, "SAID" to SAID,
        "DETAIL" to DETAIL, "CHOICES" to CHOICES, "ANSWERING" to ANSWERING, "PROMPTS_REV" to PROMPTS_REV,
        "PROMPTS" to PROMPTS, "SCOPE" to SCOPE,
    )
}

/** What a dictionary is ([PebbleKeys.TYPE]). Below 64 the watch sends it, from 64 the phone does. */
object PebbleTypes {
    /** [PebbleKeys.ASK_ID], [PebbleKeys.TEXT] (dictated or picked), and [PebbleKeys.CHAT_ID] unless it's a new chat. */
    const val ASK = 1
    const val STOP = 2
    const val CHATS_REQ = 3
    /**
     * With [PebbleKeys.ACCENT_REV], the accent the watch has, and [PebbleKeys.PROMPTS_REV], its prompts; the phone
     * answers with [STATUS], and [PROMPTS] when the watch's are out of date.
     */
    const val STATUS_REQ = 4
    /** The watch rings for request [PebbleKeys.REQUEST_ID]. */
    const val RING_ON = 5
    /** Picked up on the watch ([PebbleKeys.ANSWERED] 1, only when the ring said it's answered there) or declined (0). */
    const val RING_END_FROM_WATCH = 6
    /** The watch was told to add text past what it has (it restarted unseen): the phone sends the reply again from 0. */
    const val RESYNC = 7
    /** [PebbleKeys.CHAT_ID]'s last exchange, for the watch opening that chat: it comes as a finished [REPLY]. */
    const val HISTORY_REQ = 8
    /** The answer to request [PebbleKeys.REQUEST_ID]: choice [PebbleKeys.INDEX] of those shown, else [PebbleKeys.TEXT] dictated. */
    const val ANSWER = 9

    const val REPLY = 64
    /** One of the recent chats, [PebbleKeys.INDEX] of [PebbleKeys.COUNT]; a count of 0 is an empty list or its [PebbleKeys.ERROR]. */
    const val CHAT_ITEM = 65
    const val STATUS = 66
    /** Hermes calling for request [PebbleKeys.REQUEST_ID], and how it's answered on the watch ([PebbleKeys.ANSWERING]). */
    const val RING = 67
    const val RING_END = 68
    /** An approval or a question to answer on the watch (answering on the Pebble is on). */
    const val REQUEST = 69
    /** Request [PebbleKeys.REQUEST_ID] is over: answered (here or elsewhere), or the watch's answer failed with [PebbleKeys.ERROR]. */
    const val REQUEST_END = 70
    /** The prompts to offer ([PebbleKeys.PROMPTS]) and their revision ([PebbleKeys.PROMPTS_REV]), which the watch keeps. */
    const val PROMPTS = 71
}

/** What the watch sent, read from its dictionary. */
sealed interface PebbleIncoming {
    data class Ask(val id: String, val text: String, val chatId: String?, val scope: WatchScope? = null) : PebbleIncoming
    data class Stop(val askId: String) : PebbleIncoming
    data object ChatsRequest : PebbleIncoming
    data class StatusRequest(val accentRevision: String?, val promptsRevision: String? = null) : PebbleIncoming
    data class RingOn(val requestId: String) : PebbleIncoming
    data class RingEnd(val requestId: String, val answered: Boolean) : PebbleIncoming
    data class Resync(val askId: String) : PebbleIncoming
    data class HistoryRequest(val chatId: String, val scope: WatchScope? = null) : PebbleIncoming
    /** [index] of the choices shown, or null with the [text] dictated. */
    data class Answer(val requestId: String, val index: Int?, val text: String?) : PebbleIncoming
}

object PebbleLink {
    /** The watchapp's UUID, as in pebbleApp/package.json. */
    const val APP_UUID = "5f539f5a-9395-4cf1-8601-c3882e672ccd"

    /** Goes up when a message changes in a way an older watchapp would misread. */
    const val VERSION = 1

    /** The most of a reply the watch keeps, in UTF-8 bytes; it has 128 KB for its code and all its memory. */
    const val MAX_REPLY_BYTES = 6_000

    /**
     * The most reply text one message carries, in UTF-8 bytes. With a reply's other fields at their longest the
     * message stays well inside the 2 KB inbox the watchapp asks for.
     */
    const val CHUNK_BYTES = 1_024

    /** The inbox the watchapp opens, in bytes; [size] of every message the phone sends must fit. */
    const val WATCH_INBOX_BYTES = 2_048

    private const val MAX_TITLE_BYTES = 60
    private const val MAX_ACTIVITY_BYTES = 60
    private const val MAX_ERROR_BYTES = 120
    private const val MAX_SAID_BYTES = 200
    private const val MAX_DETAIL_BYTES = 700
    private const val MAX_CHOICE_BYTES = 60
    /** As much of a scope as the watch keeps with its chat (its persistent storage takes 256 bytes a value). */
    private const val MAX_SCOPE_BYTES = 200
    /** Hermes offers up to 4 choices to a question; an approval 3. */
    const val MAX_CHOICES = 6
    /** As many prompts as the watch keeps. */
    const val MAX_PROMPTS = 6

    /** [dict]'s size on the watch, as `dict_calc_buffer_size` counts it: a 7-byte header per value, text with its NUL. */
    fun size(dict: PebbleDict): Int = 1 + dict.values.sumOf {
        7 + when (it) {
            is PebbleValue.Num -> 4
            is PebbleValue.Text -> utf8Size(it.value) + 1
        }
    }

    /** What the watch sent, or null for a dictionary this phone doesn't understand (a newer watchapp, a stray key). */
    fun decode(dict: PebbleDict): PebbleIncoming? {
        fun text(key: Int) = (dict[key] as? PebbleValue.Text)?.value
        fun num(key: Int) = (dict[key] as? PebbleValue.Num)?.value
        return when (num(PebbleKeys.TYPE)) {
            PebbleTypes.ASK -> PebbleIncoming.Ask(
                id = text(PebbleKeys.ASK_ID)?.takeIf { it.isNotEmpty() } ?: return null,
                text = text(PebbleKeys.TEXT).orEmpty(),
                chatId = text(PebbleKeys.CHAT_ID)?.takeIf { it.isNotEmpty() },
                scope = text(PebbleKeys.SCOPE)?.let(::scopeOf),
            )
            PebbleTypes.STOP -> PebbleIncoming.Stop(text(PebbleKeys.ASK_ID) ?: return null)
            PebbleTypes.CHATS_REQ -> PebbleIncoming.ChatsRequest
            PebbleTypes.STATUS_REQ -> PebbleIncoming.StatusRequest(
                text(PebbleKeys.ACCENT_REV)?.takeIf { it.isNotEmpty() },
                text(PebbleKeys.PROMPTS_REV)?.takeIf { it.isNotEmpty() },
            )
            PebbleTypes.RING_ON -> PebbleIncoming.RingOn(text(PebbleKeys.REQUEST_ID) ?: return null)
            PebbleTypes.RING_END_FROM_WATCH -> PebbleIncoming.RingEnd(text(PebbleKeys.REQUEST_ID) ?: return null, num(PebbleKeys.ANSWERED) == 1)
            PebbleTypes.RESYNC -> PebbleIncoming.Resync(text(PebbleKeys.ASK_ID) ?: return null)
            PebbleTypes.ANSWER -> PebbleIncoming.Answer(
                requestId = text(PebbleKeys.REQUEST_ID) ?: return null,
                index = num(PebbleKeys.INDEX)?.takeIf { it >= 0 },
                text = text(PebbleKeys.TEXT)?.takeIf { it.isNotBlank() },
            )
            PebbleTypes.HISTORY_REQ -> PebbleIncoming.HistoryRequest(
                text(PebbleKeys.CHAT_ID)?.takeIf { it.isNotEmpty() } ?: return null,
                text(PebbleKeys.SCOPE)?.let(::scopeOf),
            )
            else -> null
        }
    }

    /** The watch's ask, for the relay: never read aloud, since a Pebble has no voice to play it in. */
    fun PebbleIncoming.Ask.toWatchAsk(): WatchAsk = WatchAsk(id = id, text = text, chatId = chatId, speak = false, scope = scope)

    /**
     * [scope] as the watch keeps it: the gateway, then the profile on a line of its own when there is one. Null when
     * it's longer than the watch keeps; the watch then asks without one, in whatever the phone is on.
     */
    fun scopeText(scope: WatchScope): String? =
        (scope.gateway + (scope.profile?.let { "\n$it" } ?: "")).takeIf { utf8Size(it) <= MAX_SCOPE_BYTES }

    /** What [scopeText] wrote; null for nothing. */
    fun scopeOf(text: String): WatchScope? {
        if (text.isEmpty()) return null
        val gateway = text.substringBefore('\n')
        return WatchScope(gateway, if ('\n' in text) text.substringAfter('\n') else null)
    }

    /**
     * One message of [update]'s turn: its state, and [chunk] of its plain text placed at byte [offset] of the
     * [total] bytes the phone has so far.
     */
    fun reply(update: ReplyUpdate, offset: Int, chunk: String, total: Int): PebbleDict = buildMap {
        put(PebbleKeys.TYPE, num(PebbleTypes.REPLY))
        put(PebbleKeys.ASK_ID, text(update.askId))
        put(PebbleKeys.SEQ, num(update.seq))
        put(PebbleKeys.PHASE, num(update.phase.ordinal))
        put(PebbleKeys.TEXT_OFFSET, num(offset))
        put(PebbleKeys.TEXT_TOTAL, num(total))
        put(PebbleKeys.TEXT, text(chunk))
        update.chatId?.let { put(PebbleKeys.CHAT_ID, text(it)) }
        update.title?.takeIf { it.isNotBlank() }?.let { put(PebbleKeys.TITLE, text(clipUtf8(it, MAX_TITLE_BYTES))) }
        update.activity?.takeIf { it.isNotBlank() }?.let { put(PebbleKeys.ACTIVITY, text(clipUtf8(it, MAX_ACTIVITY_BYTES))) }
        update.error?.takeIf { it.isNotBlank() }?.let { put(PebbleKeys.ERROR, text(clipUtf8(it, MAX_ERROR_BYTES))) }
        update.heard?.takeIf { it.isNotBlank() }?.let { put(PebbleKeys.SAID, text(clipUtf8(plainText(it), MAX_SAID_BYTES))) }
        update.scope?.let(::scopeText)?.let { put(PebbleKeys.SCOPE, text(it)) }
    }

    /** The ask a chat's last exchange comes as: never one the watch made, so it's never taken for a running turn. */
    fun historyAskId(chatId: String): String = "h:$chatId"

    /** Whether [askId] is a chat's last exchange ([historyAskId]), not a turn the watch asked for. */
    fun isHistoryAsk(askId: String): Boolean = askId.startsWith("h:")

    /** [chatId]'s last exchange as a finished turn: what was [said], and the [reply]. */
    fun history(chatId: String, title: String?, said: String?, reply: String, scope: WatchScope? = null): ReplyUpdate =
        ReplyUpdate(historyAskId(chatId), ReplyPhase.Done, chatId = chatId, title = title, heard = said, text = reply, scope = scope)

    /** How the phone stands, and its accent when the watch's is out of date. */
    fun status(status: PhoneStatus): PebbleDict = buildMap {
        put(PebbleKeys.TYPE, num(PebbleTypes.STATUS))
        put(PebbleKeys.VERSION, num(VERSION))
        var flags = 0
        if (status.signedIn) flags = flags or FLAG_SIGNED_IN
        if (status.connected) flags = flags or FLAG_CONNECTED
        if (status.failed) flags = flags or FLAG_FAILED
        put(PebbleKeys.FLAGS, num(flags))
        status.accent?.let { accent ->
            put(PebbleKeys.ACCENT_REV, text(accent.revision))
            // The watch takes plain RGB (GColorFromHEX); alpha means nothing there.
            put(PebbleKeys.ACCENT_FILL, num(accent.fill and 0xFFFFFF))
            put(PebbleKeys.ACCENT_TEXT, num(accent.text and 0xFFFFFF))
            put(PebbleKeys.ACCENT_ON_FILL, num(accent.onFill and 0xFFFFFF))
            put(PebbleKeys.ACCENT_DIM, num(accent.dim and 0xFFFFFF))
        }
    }

    /** The recent chats, one message each; an empty list (or one that couldn't be read) is one message with a count of 0. */
    fun chats(list: ChatList, nowSeconds: Double): List<PebbleDict> {
        if (list.chats.isEmpty()) {
            return listOf(
                buildMap {
                    put(PebbleKeys.TYPE, num(PebbleTypes.CHAT_ITEM))
                    put(PebbleKeys.COUNT, num(0))
                    list.error?.let { put(PebbleKeys.ERROR, text(clipUtf8(it, MAX_ERROR_BYTES))) }
                },
            )
        }
        return list.chats.mapIndexed { index, chat ->
            buildMap {
                put(PebbleKeys.TYPE, num(PebbleTypes.CHAT_ITEM))
                put(PebbleKeys.INDEX, num(index))
                put(PebbleKeys.COUNT, num(list.chats.size))
                put(PebbleKeys.CHAT_ID, text(chat.id))
                put(PebbleKeys.TITLE, text(clipUtf8(chat.title, MAX_TITLE_BYTES)))
                ago(chat.activityAt, nowSeconds)?.let { put(PebbleKeys.AGO, text(it)) }
                list.scope?.let(::scopeText)?.let { put(PebbleKeys.SCOPE, text(it)) }
            }
        }
    }

    /**
     * Hermes calling for an answer in chat [WatchRing.chatId]: [answering] says how the watch answers it once picked up
     * ([PebbleKeys.ANSWERING]); on the phone only, Answer there just stops the watch ringing.
     */
    fun ring(ring: WatchRing, answering: Int): PebbleDict = mapOf(
        PebbleKeys.TYPE to num(PebbleTypes.RING),
        PebbleKeys.REQUEST_ID to text(ring.requestId),
        PebbleKeys.CHAT_ID to text(ring.chatId),
        PebbleKeys.TITLE to text(clipUtf8(ring.title, MAX_TITLE_BYTES)),
        PebbleKeys.APPROVAL to num(if (ring.approval) 1 else 0),
        PebbleKeys.AGENT to num(if (ring.agent) 1 else 0),
        PebbleKeys.ANSWERING to num(answering),
    )

    /**
     * An approval ([approval]) or a question waiting in chat [chatId] ([title]): its [detail], the [choices] the watch
     * offers, and how it's answered there ([answering], see [PebbleKeys.ANSWERING]).
     */
    fun request(
        requestId: String,
        chatId: String?,
        title: String?,
        approval: Boolean,
        heading: String,
        detail: String,
        choices: List<String>,
        answering: Int,
    ): PebbleDict = buildMap {
        put(PebbleKeys.TYPE, num(PebbleTypes.REQUEST))
        put(PebbleKeys.REQUEST_ID, text(requestId))
        chatId?.let { put(PebbleKeys.CHAT_ID, text(it)) }
        title?.takeIf { it.isNotBlank() }?.let { put(PebbleKeys.TITLE, text(clipUtf8(it, MAX_TITLE_BYTES))) }
        put(PebbleKeys.APPROVAL, num(if (approval) 1 else 0))
        put(PebbleKeys.ACTIVITY, text(clipUtf8(heading, MAX_ACTIVITY_BYTES)))
        put(PebbleKeys.DETAIL, text(clipUtf8(plainText(detail), MAX_DETAIL_BYTES)))
        // Each on a line of its own: the watch splits them there.
        val lines = choices.take(MAX_CHOICES).map { clipUtf8(it.replace('\n', ' '), MAX_CHOICE_BYTES) }
        put(PebbleKeys.CHOICES, text(lines.joinToString("\n")))
        put(PebbleKeys.ANSWERING, num(answering))
    }

    fun requestEnd(requestId: String, error: String? = null): PebbleDict = buildMap {
        put(PebbleKeys.TYPE, num(PebbleTypes.REQUEST_END))
        put(PebbleKeys.REQUEST_ID, text(requestId))
        error?.let { put(PebbleKeys.ERROR, text(clipUtf8(it, MAX_ERROR_BYTES))) }
    }

    /** [prompts] as the watch keeps them: blank ones left out, each on one line and clipped, no more than [MAX_PROMPTS]. */
    fun promptLines(prompts: List<String>): List<String> =
        prompts.map { it.replace('\n', ' ').trim() }.filter { it.isNotEmpty() }.take(MAX_PROMPTS).map { clipUtf8(it, MAX_CHOICE_BYTES) }

    /** Names [prompts] as the watch has them: the same lines, the same revision. */
    fun promptsRevision(prompts: List<String>): String = promptLines(prompts).joinToString("\n").hashCode().toUInt().toString(16)

    /** The prompts for the watch's menu, with their [promptsRevision]. */
    fun prompts(prompts: List<String>): PebbleDict = mapOf(
        PebbleKeys.TYPE to num(PebbleTypes.PROMPTS),
        PebbleKeys.PROMPTS_REV to text(promptsRevision(prompts)),
        PebbleKeys.PROMPTS to text(promptLines(prompts).joinToString("\n")),
    )

    /** The status while Kyllene's Pebble switch is off, whatever the watch sent. */
    fun off(): PebbleDict = mapOf(PebbleKeys.TYPE to num(PebbleTypes.STATUS), PebbleKeys.FLAGS to num(FLAG_OFF))

    fun ringEnd(end: RingEnd): PebbleDict = mapOf(
        PebbleKeys.TYPE to num(PebbleTypes.RING_END),
        PebbleKeys.REQUEST_ID to text(end.requestId),
    )

    /** How long ago [epochSeconds] was, the way a list says it: "now", "5m", "3h", "2d". */
    fun ago(epochSeconds: Double?, nowSeconds: Double): String? {
        val seconds = (nowSeconds - (epochSeconds ?: return null)).toLong()
        return when {
            seconds < 60 -> "now"
            seconds < 3_600 -> "${seconds / 60}m"
            seconds < 86_400 -> "${seconds / 3_600}h"
            else -> "${seconds / 86_400}d"
        }
    }

    const val FLAG_SIGNED_IN = 1
    const val FLAG_CONNECTED = 2
    const val FLAG_FAILED = 4
    /** Kyllene's Pebble switch is off: the phone takes nothing from the watch. */
    const val FLAG_OFF = 8

    private fun num(value: Int) = PebbleValue.Num(value)
    private fun text(value: String) = PebbleValue.Text(value)
}
