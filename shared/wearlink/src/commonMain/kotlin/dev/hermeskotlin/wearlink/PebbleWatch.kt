package dev.hermeskotlin.wearlink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How a message to the watch went. */
enum class PebbleSent {
    /** The watch has it. */
    Sent,

    /** Kyllene isn't the app open on the watch, or the watch is away: nothing gets there until it opens again. */
    NotOpen,

    /** Lost on the way (a timeout, a NACK): worth another try. */
    Failed,
}

/**
 * What goes to one Pebble, in order and one message at a time, over [send]. The watch takes a message only while
 * Kyllene is the app open on it, and only about 2 KB at once, so:
 *
 * - The turn's updates ([reply]) aren't each sent whole. The newest one wins, and the watch gets only what it lacks:
 *   the reply's text from the end of what the watch acknowledged, a chunk at a time, or all of it again from 0 when
 *   it changed other than by growing. Updates while streaming go at most every [gapMs].
 * - Other messages ([message]: the status, the chats) go first, in order. While the app is closed they're dropped:
 *   the watch asks again when it opens.
 * - When the app opens ([opened]) or the watch finds it was told to add text past its own ([resync]), the reply
 *   goes again from the start, so a watch reopened mid-turn catches up.
 * - The turn's last update is tried again a few times, [finalRetryMs] apart, when it's lost: nothing comes after it
 *   to carry it, and the watch would show the turn running until it reopened.
 * - A turn that ends while the app is closed goes to [onUndelivered], once, for the phone to tell some other way;
 *   not one the watch stopped, which it knows ended.

 *
 * Every call only queues; one coroutine in [scope] does the sending, so nothing here is shared between threads.
 */
class PebbleWatch(
    scope: CoroutineScope,
    private val send: suspend (PebbleDict) -> PebbleSent,
    private val gapMs: Long = REPLY_GAP_MS,
    private val retryMs: Long = RETRY_MS,
    private val finalRetryMs: Long = FINAL_RETRY_MS,
    private val onUndelivered: (ReplyUpdate) -> Unit = {},
) {
    private sealed interface Event {
        data class Reply(val update: ReplyUpdate) : Event
        data class Message(val dict: PebbleDict) : Event
        data object Opened : Event
        data object Closed : Event
        data class Resync(val askId: String) : Event
        data class Stopped(val askId: String) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    fun reply(update: ReplyUpdate) {
        events.trySend(Event.Reply(update))
    }

    fun message(dict: PebbleDict) {
        events.trySend(Event.Message(dict))
    }

    /** Kyllene opened on the watch, which starts with nothing: what it should show goes again. */
    fun opened() {
        events.trySend(Event.Opened)
    }

    fun closed() {
        events.trySend(Event.Closed)
    }

    fun resync(askId: String) {
        events.trySend(Event.Resync(askId))
    }

    /**
     * The watch stopped the turn for [askId]. The relay then tells it nothing more, so the reply kept for it ends
     * here, as the watch already shows it: reopened, it shouldn't show the turn running again.
     */
    fun stopped(askId: String) {
        events.trySend(Event.Stopped(askId))
    }

    // Only the sending coroutine touches these.
    private var open = true
    private val queue = ArrayDeque<PebbleDict>()
    private var latest: ReplyUpdate? = null

    /** How often the turn's last update was tried again after it was lost. */
    private var finalRounds = 0

    /** The asks whose end went to [onUndelivered] or that the watch stopped: neither is told again. */
    private var toldElsewhere: String? = null
    private var stoppedAsk: String? = null

    /** The ask the watch has, the part of its plain text the watch acknowledged, and the rest of the update it shows. */
    private var shownAsk: String? = null
    private var shownText = ""
    private var shownState: ReplyUpdate? = null

    init {
        scope.launch {
            for (event in events) {
                apply(event)
                pump()
            }
        }
    }

    private fun apply(event: Event) {
        when (event) {
            is Event.Reply -> {
                finalRounds = 0
                val last = latest
                // The newest wins; one that arrives late for the same ask would undo it.
                if (last == null || last.askId != event.update.askId || event.update.seq >= last.seq) latest = event.update
                if (!open) endedWhileClosed()
            }
            is Event.Message -> if (open) queue.addLast(event.dict)
            Event.Opened -> {
                open = true
                forgetShown()
            }
            Event.Closed -> {
                open = false
                queue.clear()
                endedWhileClosed()
            }
            is Event.Resync -> if (event.askId == shownAsk) forgetShown()
            is Event.Stopped -> {
                stoppedAsk = event.askId
                latest = latest?.takeIf { it.askId == event.askId && it.phase != ReplyPhase.Failed }
                    ?.copy(phase = ReplyPhase.Done, activity = null) ?: latest
            }
        }
    }

    /** The newest turn ended and the watch, closed, hasn't seen it end: told elsewhere, once. */
    private fun endedWhileClosed() {
        val update = latest ?: return
        if (update.phase != ReplyPhase.Done && update.phase != ReplyPhase.Failed) return
        if (update.askId == toldElsewhere || update.askId == stoppedAsk) return
        if (shownAsk == update.askId && shownState?.phase == update.phase) return
        toldElsewhere = update.askId
        onUndelivered(update)
    }

    private fun forgetShown() {
        shownAsk = null
        shownText = ""
        shownState = null
    }

    /** Sends while there's something the watch lacks, taking in what came meanwhile before each message. */
    private suspend fun pump() {
        while (true) {
            while (true) apply(events.tryReceive().getOrNull() ?: break)
            if (!open) return
            val message = queue.firstOrNull()
            if (message != null) {
                val sent = deliver(message)
                // Given up on: dropped, so one message that never goes can't hold the rest.
                if (open) queue.removeFirst()
                if (!sent) return
                continue
            }
            val next = nextReply() ?: return
            if (!deliver(next.dict)) {
                if (!open || !next.terminal || finalRounds >= FINAL_ROUNDS) return
                finalRounds++
                delay(finalRetryMs)
                continue
            }
            finalRounds = 0
            shownAsk = next.update.askId
            shownText = next.shownText
            shownState = next.update.withoutText()
            if (!next.more && !next.terminal) delay(gapMs)
        }
    }

    private class NextReply(val update: ReplyUpdate, val dict: PebbleDict, val shownText: String, val more: Boolean, val terminal: Boolean)

    /** The message that brings the watch closer to the newest update, or null when it shows that already. */
    private fun nextReply(): NextReply? {
        val update = latest ?: return null
        val plain = clipUtf8(plainText(update.text), PebbleLink.MAX_REPLY_BYTES)
        val sameAsk = update.askId == shownAsk
        val kept = if (sameAsk && plain.startsWith(shownText)) shownText else ""
        val chunk = takeUtf8(plain, PebbleLink.CHUNK_BYTES, from = kept.length)
        val replaces = kept.isEmpty() && shownText.isNotEmpty()
        val stateChanged = !sameAsk || shownState != update.withoutText()
        if (chunk.isEmpty() && !replaces && !stateChanged) return null
        val dict = PebbleLink.reply(update, offset = utf8Size(kept), chunk = chunk, total = utf8Size(plain))
        val shown = kept + chunk
        val terminal = update.phase == ReplyPhase.Done || update.phase == ReplyPhase.Failed
        return NextReply(update, dict, shown, more = shown.length < plain.length, terminal = terminal)
    }

    /** What the watch shows besides the text: an update that changes only its count doesn't need sending. */
    private fun ReplyUpdate.withoutText() = copy(text = "", seq = 0)

    /** True once the watch has [dict]; a few tries when it's lost on the way, none while the app is closed. */
    private suspend fun deliver(dict: PebbleDict): Boolean {
        repeat(ATTEMPTS) { attempt ->
            when (send(dict)) {
                PebbleSent.Sent -> return true
                PebbleSent.NotOpen -> {
                    open = false
                    queue.clear()
                    endedWhileClosed()
                    return false
                }
                PebbleSent.Failed -> if (attempt < ATTEMPTS - 1) delay(retryMs)
            }
        }
        return false
    }

    companion object {
        /** How often a streaming reply's updates go at most: each one is a Bluetooth round trip, and the watch redraws. */
        const val REPLY_GAP_MS = 800L
        const val RETRY_MS = 500L
        const val FINAL_RETRY_MS = 5_000L
        private const val ATTEMPTS = 3
        private const val FINAL_ROUNDS = 5
    }
}
