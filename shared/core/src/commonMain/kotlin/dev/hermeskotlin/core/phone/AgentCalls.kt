package dev.hermeskotlin.core.phone

import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Why the agent called ([PhoneTool.CallMe]): the voice opens the call with it. */
data class CallReason(val reason: String, val details: String? = null)

/** How a call the agent placed ended: what `phone_call_me` returns to it. */
enum class CallOutcome(val status: String) {
    Answered("answered"),
    Declined("declined"),
    NoAnswer("no_answer"),

    /** Already on a call with Hermes: it didn't ring. */
    Busy("busy"),

    /** Quiet hours on this phone: it didn't ring. */
    Quiet("quiet"),
}

/**
 * A call the agent placed, ringing or being picked up: [outcome] is settled once, by whichever comes first (on the
 * line, declined, rung out), and the tool returns it.
 */
class AgentCall(val reason: CallReason) {
    private val ended = CompletableDeferred<CallOutcome>()

    /** Settles the call as [outcome]; later ones are ignored. */
    fun end(outcome: CallOutcome) {
        ended.complete(outcome)
    }

    suspend fun outcome(): CallOutcome = ended.await()
}

/**
 * What a live voice is told to say first on a call the agent placed: why, in its own words. The details are only
 * there for it to answer from; the chat the call is in already has them for Hermes.
 */
fun CallReason.liveOpener(): String = buildString {
    append("Hermes called the user, and they just picked up. Say hello and tell them why, briefly: ")
    append(reason.trim())
    details?.trim()?.takeIf { it.isNotEmpty() }?.let {
        append("\n\nBackground, only to answer their questions from, not to read out: ")
        append(it)
    }
}

/** What the chained voice reads aloud first on a call the agent placed. */
fun CallReason.spokenOpener(): String = "Hi, it's Hermes. ${reason.trim()}"

/**
 * The agent's calls picked up on the watch, by the chat each rings for, until the call the watch starts there takes
 * its reason. A reason not taken within [KEEP_MS] is dropped: that call never started.
 */
class CallOpeners(private val now: () -> Long = ::getTimeMillis) {
    // Picked up on the watch's thread, taken on the call's.
    private val waiting = MutableStateFlow<Map<String, Pair<CallReason, Long>>>(emptyMap())

    fun put(chatId: String, reason: CallReason) = waiting.update { it + (chatId to (reason to now())) }

    /** The reason a call starting in [chatId] opens with, once. */
    fun take(chatId: String?): CallReason? {
        if (chatId == null) return null
        while (true) {
            val before = waiting.value
            val (reason, at) = before[chatId] ?: return null
            if (waiting.compareAndSet(before, before - chatId)) return reason.takeIf { now() - at < KEEP_MS }
        }
    }

    companion object {
        const val KEEP_MS = 60_000L
    }
}
