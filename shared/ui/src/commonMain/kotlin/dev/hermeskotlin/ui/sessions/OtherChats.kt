package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.Waiting

/**
 * A chat other than the one on screen, as the Sessions badge, the banner and Next see it. [waiting]: what it
 * waits on the user for; [unread]: it has activity the user hasn't seen; [running]: a turn is going.
 * [activityAt] (epoch seconds) orders replies; [bot] names the bot whose Bot Chat this is. [requestId]: the
 * open request's id when this phone heard it, so a second request of the same kind still counts as new.
 */
data class OtherChat(
    val id: String,
    val title: String,
    val waiting: Waiting? = null,
    val unread: Boolean = false,
    val running: Boolean = false,
    val activityAt: Double? = null,
    val bot: String? = null,
    val requestId: String? = null,
) {
    /** A turn finished with something the user hasn't read. Mid-turn activity isn't a reply yet. */
    val replied: Boolean get() = unread && !running && waiting == null

    val needsYou: Boolean get() = waiting != null || replied
}

/** What the Sessions button shows: how many other chats wait on the user, and how many have a reply. */
data class SessionsBadge(val waiting: Int = 0, val replies: Int = 0) {
    val count: Int get() = waiting + replies

    /** Nothing to show. */
    val none: Boolean get() = count == 0

    /** A chat waiting on the user outranks a reply, so the dot takes the warning colour. */
    val urgent: Boolean get() = waiting > 0

    /** What a screen reader says for the button. */
    val description: String get() = if (none) "Sessions" else "Sessions, $count need${if (count == 1) "s" else ""} you"
}

/** What the banner says happened in another chat. */
enum class OtherChatCall { Reply, Approval, Question, Input }

/**
 * One thing that newly happened in another chat, for the banner: [chatId] titled [title] did [call].
 * [more] is how many other chats did something in the same moment (several turns ending at once).
 * [atMillis] is when it was seen, so the banner's time runs from then and not from each recomposition.
 */
data class OtherChatEvent(
    val chatId: String,
    val title: String,
    val call: OtherChatCall,
    val bot: String? = null,
    val more: Int = 0,
    val atMillis: Long = 0,
    /** The start of the reply, or what is asked (the command, the question), as plain text; null until known. */
    val preview: String? = null,
) {
    val message: String get() {
        val said = when (call) {
            OtherChatCall.Reply -> "Reply in $title"
            OtherChatCall.Approval -> "$title needs your OK"
            OtherChatCall.Question -> "$title needs an answer"
            OtherChatCall.Input -> "$title needs your input"
        }
        return if (more > 0) "$said, and $more more" else said
    }

    /** The chat is waiting on the user: the phone buzzes for these, not for replies. */
    val waiting: Boolean get() = call != OtherChatCall.Reply
}

/** The other chats at one moment, keyed by id; the open chat is never in it. */
data class OtherChats(val chats: Map<String, OtherChat> = emptyMap()) {

    /** Whether [event] still describes this chat; a banner for something already dealt with goes. */
    fun stillTrue(event: OtherChatEvent): Boolean {
        val chat = chats[event.chatId] ?: return false
        return if (event.waiting) chat.waiting != null else chat.replied
    }

    companion object {
        /**
         * The other chats from [rows] (a chat list and Bot Chats), leaving out every id in [open]: the chat on
         * screen speaks for itself. A bot's chat may go by either of its ids, so each row lists its [OtherChatRow.ids].
         */
        fun of(rows: List<OtherChatRow>, open: Set<String>): OtherChats = OtherChats(
            buildMap {
                for (row in rows) {
                    if (row.ids.any { it in open }) continue
                    if (row.chat.id !in this) put(row.chat.id, row.chat)
                }
            },
        )
    }
}

/** A chat for [OtherChats.of], with every id it may be known by (a Bot Chat moves on after `/compress`). */
data class OtherChatRow(val chat: OtherChat, val ids: Set<String> = setOf(chat.id))

private fun Waiting.call(): OtherChatCall = when (this) {
    Waiting.Approval, Waiting.Unknown -> OtherChatCall.Approval
    Waiting.Question -> OtherChatCall.Question
    Waiting.Input -> OtherChatCall.Input
}

/**
 * Whether [now] is a new request compared with [before]. Learning what an unseen request was (Unknown to a kind,
 * or back) isn't new; one kind after another is (an approval answered, then a question), and so is another
 * request of the same kind when this phone heard both (an approval answered on Desktop, then the next one).
 */
private fun newlyWaiting(before: OtherChat, now: OtherChat): Boolean = when {
    now.waiting == null -> false
    before.waiting == null -> true
    before.requestId != null && now.requestId != null -> before.requestId != now.requestId
    before.waiting == Waiting.Unknown || now.waiting == Waiting.Unknown -> false
    else -> before.waiting != now.waiting
}

/**
 * Watches the other chats for what newly happens in them while the app is in the foreground. The first look
 * (and the first after a reset: a reconnect, the app coming back) only takes stock, so what was already
 * pending never shows as news. A chat that wasn't among the other chats last time (just listed, or just left)
 * isn't news either. Remembers when each chat started waiting, so Next can go to the oldest request first.
 */
class OtherChatsWatch {
    private var previous: OtherChats? = null

    /** When each chat was first seen waiting (epoch ms), for [next]. */
    private val waitingSince = mutableMapOf<String, Long>()

    /**
     * The chats whose reply is news: it came while the chat screen was watched, or while Herald was away. Only
     * these count on the badge and for Next. A chat merely unread since this phone last opened it (one used on
     * Telegram or Desktop, a reply long known) isn't news, or the dot would never go.
     */
    private val news = mutableSetOf<String>()

    /**
     * When the chat screen was last left, on the gateway's clock (epoch seconds), so replies since then count on
     * the way back. The gateway's clock, not the phone's: [OtherChat.activityAt] is the gateway's, and the two
     * may disagree by minutes.
     */
    private var leftAt: Double? = null

    /** The newest [OtherChat.activityAt] seen, the gateway's "now" as far as this phone knows it. */
    private var newestActivity: Double? = null

    /** Forgets what was seen: the next [update] only takes stock. What is news stays. */
    fun reset() {
        previous = null
    }

    /**
     * The chat screen goes out of sight: replies after the newest activity seen so far are news when it comes
     * back. [nowSeconds] (the phone's clock) stands in only when no activity was seen yet.
     */
    fun left(nowSeconds: Double) {
        if (leftAt == null) leftAt = newestActivity ?: nowSeconds
        previous = null
    }

    /** Another gateway or profile: nothing about the last one's chats holds. */
    fun clear() {
        previous = null
        leftAt = null
        newestActivity = null
        news.clear()
        waitingSince.clear()
    }

    /** What the Sessions button shows: chats waiting on the user, and replies that are news. */
    fun badge(current: OtherChats): SessionsBadge = SessionsBadge(
        waiting = current.chats.values.count { it.waiting != null },
        replies = current.chats.values.count { it.replied && it.waiting == null && it.id in news },
    )

    /**
     * Compares [current] with the last look and returns what newly happened, or null. While not [settled]
     * (the gateway and the list are still catching up after a reconnect) it only takes stock.
     */
    fun update(current: OtherChats, nowMillis: Long, settled: Boolean = true): OtherChatEvent? {
        val before = previous
        // Unsettled, nothing counts as seen: the first settled look takes stock again rather than reporting
        // everything the catch-up brought in.
        previous = current.takeIf { settled }
        current.chats.values.mapNotNull { it.activityAt }.maxOrNull()?.let { newest ->
            newestActivity = maxOf(newestActivity ?: newest, newest)
        }
        waitingSince.keys.retainAll { current.chats[it]?.waiting != null }
        for (chat in current.chats.values) {
            if (chat.waiting != null && chat.id !in waitingSince) waitingSince[chat.id] = nowMillis
        }
        if (!settled) return null
        // Read, opened, archived or answered: no longer news.
        news.retainAll { current.chats[it]?.replied == true }
        if (before == null) {
            // Back after a while away: what replied meanwhile is news, though no banner tells of it.
            leftAt?.let { away -> current.chats.values.filter { it.replied && (it.activityAt ?: 0.0) > away }.mapTo(news) { it.id } }
            leftAt = null
            return null
        }

        val calls = mutableListOf<OtherChatEvent>()
        val replies = mutableListOf<OtherChat>()
        for (chat in current.chats.values) {
            val was = before.chats[chat.id] ?: continue
            if (newlyWaiting(was, chat)) {
                if (was.waiting != null) waitingSince[chat.id] = nowMillis
                calls += OtherChatEvent(chat.id, chat.title, chat.waiting!!.call(), chat.bot, atMillis = nowMillis)
            } else if (chat.replied && (!was.replied || (chat.activityAt ?: 0.0) > (was.activityAt ?: 0.0))) {
                // A new reply in a chat that was already unread: its activity moved on, though the short turn
                // before it may have come and gone between two looks. Merely listed again unchanged isn't news.
                replies += chat
                news += chat.id
            }
        }
        val count = calls.size + replies.size
        // A request asks something of the user, so it leads; otherwise the newest reply.
        val lead = calls.firstOrNull()
            ?: replies.maxByOrNull { it.activityAt ?: 0.0 }?.let { OtherChatEvent(it.id, it.title, OtherChatCall.Reply, it.bot, atMillis = nowMillis) }
            ?: return null
        return lead.copy(more = count - 1)
    }

    /** The other chats needing the user, in the order Next visits them: requests oldest first, then replies oldest first. */
    fun queue(current: OtherChats): List<OtherChat> {
        // A request on the first look has no time of its own yet; it counts from then.
        val waiting = current.chats.values.filter { it.waiting != null }
            .sortedWith(compareBy({ waitingSince[it.id] ?: Long.MAX_VALUE }, { it.activityAt ?: Double.MAX_VALUE }))
        val replies = current.chats.values.filter { it.replied && it.id in news }.sortedBy { it.activityAt ?: Double.MAX_VALUE }
        return waiting + replies
    }
}

/** At most this many characters of a reply go into the banner's preview; it shows two lines of them. */
private const val PREVIEW_CHARS = 280

/**
 * A reply's [text] as a banner preview: Markdown marks taken out (headings, emphasis, code fences, links, list
 * bullets, tables' pipes), lines joined, cut to [PREVIEW_CHARS]. Blank or only marks: null.
 */
fun previewOf(text: String?): String? {
    if (text.isNullOrBlank()) return null
    // A code block's lines are left out, fences and all: a preview says what the reply says, not its code.
    var inCode = false
    val plain = text.lineSequence()
        .filter { line ->
            val fence = line.trimStart().startsWith("```")
            if (fence) inCode = !inCode
            !fence && !inCode
        }
        // A table's rule row says nothing.
        .filterNot { it.trim().matches(TABLE_RULE) && it.contains('-') }
        .map { line ->
            line.trim()
                .replace(HEADING, "")
                .replace(QUOTE, "")
                .replace(BULLET, "")
                .replace(TABLE_EDGE, "")
                .replace(TABLE_PIPE, " · ")
        }
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .replace(IMAGE, "$1")
        .replace(LINK, "$1")
        .replace(STRONG, "$2")
        .replace(EMPHASIS, "$1")
        .replace(STRIKE, "$1")
        .replace(CODE, "$1")
        .replace(SPACES, " ")
        .trim()
    return cut(plain)
}

// Compiled once: a preview runs them over every line of a reply.
private val TABLE_RULE = Regex("""^\|?[\s:|-]+\|?$""")
private val HEADING = Regex("""^#{1,6}\s+""")
private val QUOTE = Regex("""^>\s?""")
private val BULLET = Regex("""^([-*+]|\d+[.)])\s+""")
private val TABLE_EDGE = Regex("""^\|\s*|\s*\|$""")
private val TABLE_PIPE = Regex("""\s*\|\s*""")
private val IMAGE = Regex("""!\[([^\]]*)]\([^)]*\)""")
private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
private val STRONG = Regex("""(\*\*|__)(.+?)\1""")
private val EMPHASIS = Regex("""(?<![\w*])[*_](\S(?:.*?\S)?)[*_](?![\w*])""")
private val STRIKE = Regex("""~~(.+?)~~""")
private val CODE = Regex("""`([^`]*)`""")
private val SPACES = Regex("""\s+""")

/** [text] cut to [PREVIEW_CHARS] with an ellipsis, never between the two halves of an emoji; empty: null. */
private fun cut(text: String): String? {
    if (text.isEmpty()) return null
    if (text.length <= PREVIEW_CHARS) return text
    val head = text.take(PREVIEW_CHARS).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
    return head.trimEnd() + "…"
}

/** A command as it will run, on one line: not Markdown, so `__init__.py` or `*.txt` keep their marks. */
private fun commandPreview(command: String): String? = cut(command.replace(SPACES, " ").trim())

/** What [request] asks, for the banner: the command to approve, the question, what a value is for. */
fun previewOf(request: InputRequest?): String? = when (request) {
    null -> null
    is InputRequest.Approval -> if (request.command.isNotBlank()) commandPreview(request.command) else previewOf(request.description)
    is InputRequest.Clarify -> request.questions.firstOrNull()?.question?.let { first ->
        previewOf(if (request.questions.size > 1) "$first (+${request.questions.size - 1} more)" else first)
    }
    is InputRequest.Secret -> if (request.prompt.isNotBlank()) previewOf(request.prompt) else (request.command ?: request.envVar)?.let(::commandPreview)
    is InputRequest.VaultSaveLogin -> previewOf(request.title)
}
