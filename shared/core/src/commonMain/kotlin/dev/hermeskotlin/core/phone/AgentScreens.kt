package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.chat.A2uiEnvelope
import dev.hermeskotlin.core.chat.AgentScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement

/**
 * The agent's screens in the chat that a `phone_show_ui` call waits on. The tool [await]s the user's answer; the
 * chat draws the screen from the call's arguments, says it's on screen with [shown], and hands a tap to [answer].
 * A screen is matched by its chat (the stored session id, when the gateway said which) and its surface ids, so the
 * same screen in another chat, or in this chat's history, takes no taps.
 */
class AgentScreens(private val shownWithinMs: Long = SHOWN_WITHIN_MS) {

    /** A screen a call waits on: [chat] is null when the gateway didn't say which chat called. */
    data class Live(val chat: String?, val surfaceIds: Set<String>) {
        fun matches(chat: String?, surfaceId: String) =
            surfaceId in surfaceIds && (this.chat == null || chat == null || this.chat == chat)
    }

    private class Waiting(val live: Live) {
        val shown = CompletableDeferred<Unit>()
        private val answer = CompletableDeferred<Result<JsonElement>>()

        /** Ends the wait with [result]; an answer, or a failure, needs no more showing. */
        fun complete(result: Result<JsonElement>): Boolean = answer.complete(result).also { shown.complete(Unit) }

        suspend fun await(): JsonElement = answer.await().getOrThrow()
    }

    private val waiting = MutableStateFlow<List<Waiting>>(emptyList())

    /** The messages each surface was sent, by chat and surface id, since Kyllene started: what a later call updates. */
    private val sent = MutableStateFlow<Map<Pair<String?, String>, List<String>>>(emptyMap())

    /**
     * What [chat]'s surfaces got before [screen], for the surfaces [screen] changes without making them (an
     * update), in the order they came: [screen] is checked on top of them. Null when one of them isn't known here
     * (made before Kyllene started, or never).
     */
    fun earlier(screen: AgentScreen, chat: String?): List<String>? {
        val parsed = screen.messages.mapNotNull(A2uiEnvelope::of)
        val made = parsed.filter { it.creates }.mapTo(HashSet()) { it.surfaceId }
        val known = sent.value
        return parsed.map { it.surfaceId }.filterNot { it in made }.distinct().flatMap { known[chat to it] ?: return null }
    }

    /**
     * A card in [chat] draws [screen], every message its surfaces got. Kept only for a surface not known here (made
     * before Kyllene started): for the others [record] knows better, as a card may show a call the phone is still
     * checking, or one drawn before a later call.
     */
    fun drawn(screen: AgentScreen, chat: String) = sent.update { known ->
        val bySurface = screen.messages.groupBy { A2uiEnvelope.of(it)?.surfaceId }
        bySurface.entries.fold(known) { map, (id, messages) ->
            if (id == null || (chat to id) in map) map else map + ((chat to id) to messages)
        }
    }

    /** What each surface was sent, by chat and surface id: changes when the phone takes a call. */
    val got: StateFlow<Map<Pair<String?, String>, List<String>>> get() = sent.asStateFlow()

    /** [screen] was drawn in [chat]: its messages join what its surfaces got, and a `createSurface` starts over. */
    fun record(screen: AgentScreen, chat: String?) = sent.update { known ->
        screen.messages.fold(known) { map, message ->
            val envelope = A2uiEnvelope.of(message) ?: return@fold map
            val key = chat to envelope.surfaceId
            map + (key to if (envelope.creates) listOf(message) else map[key].orEmpty() + message)
        }
    }

    /** The screens that take a tap now. */
    val live: StateFlow<List<Live>> get() = _live.asStateFlow()
    private val _live = MutableStateFlow<List<Live>>(emptyList())

    /**
     * Waits until the user answers [screen] in [chat], and returns what they did, for the agent. Throws
     * [PhoneToolException] when the chat doesn't show it soon (Kyllene is closed, or on another chat), when it
     * failed to draw, when the call ended, or when a newer call took over its surface.
     */
    suspend fun await(screen: AgentScreen, chat: String?): JsonElement {
        val entry = Waiting(Live(chat, screen.surfaceIds))
        var replaced = emptyList<Waiting>()
        waiting.update { list ->
            val (old, kept) = list.partition { it.live.chat == chat && it.live.surfaceIds.any(screen.surfaceIds::contains) }
            replaced = old
            kept + entry
        }
        replaced.forEach { it.complete(Result.failure(PhoneToolException(REPLACED))) }
        publish()
        try {
            withTimeoutOrNull(shownWithinMs) { entry.shown.await() } ?: throw PhoneToolException(NOT_SHOWN)
            return entry.await()
        } finally {
            waiting.update { list -> list - entry }
            publish()
        }
    }

    /** The chat [chat] shows the screen with [surfaceIds] now, and can take its answer. */
    fun shown(chat: String?, surfaceIds: Set<String>) {
        waiting.value.filter { w -> surfaceIds.any { w.live.matches(chat, it) } }.forEach { it.shown.complete(Unit) }
    }

    /** Hands the user's [result] on [surfaceId] in [chat] to the call waiting on it; false when none waits. */
    fun answer(chat: String?, surfaceId: String, result: JsonElement): Boolean = complete(chat, surfaceId, Result.success(result))

    /** Tells the call waiting on [surfaceId] in [chat] that its screen failed or ended ([message], for the agent). */
    fun fail(chat: String?, surfaceId: String, message: String): Boolean =
        complete(chat, surfaceId, Result.failure(PhoneToolException(message)))

    private fun complete(chat: String?, surfaceId: String, result: Result<JsonElement>): Boolean {
        val entry = waiting.value.lastOrNull { it.live.matches(chat, surfaceId) } ?: return false
        return entry.complete(result)
    }

    private fun publish() = _live.update { waiting.value.map { it.live } }

    companion object {
        /**
         * Whether the phone took [screen]'s updates in [chat], with [got] what its surfaces got: each surface it
         * changes without making it ends with them. A screen with no updates was always taken.
         */
        fun took(screen: AgentScreen, chat: String?, got: Map<Pair<String?, String>, List<String>>): Boolean {
            val parsed = screen.messages.mapNotNull { message -> A2uiEnvelope.of(message)?.let { message to it } }
            val made = parsed.filter { it.second.creates }.mapTo(HashSet()) { it.second.surfaceId }
            return parsed.filterNot { it.second.surfaceId in made }.groupBy({ it.second.surfaceId }, { it.first }).all { (id, messages) ->
                val known = got[chat to id] ?: got[null to id] ?: return@all false
                known.size >= messages.size && known.subList(known.size - messages.size, known.size) == messages
            }
        }

        /** A screen the chat doesn't show by then isn't waited on: nobody would see it. */
        const val SHOWN_WITHIN_MS = 15_000L

        const val REPLACED = "A newer screen with the same surface id took its place."
        const val NOT_SHOWN = "Kyllene isn't showing this chat on the phone right now, so the user can't see the " +
            "screen. Ask in words instead, or try again when they're in the chat."
    }
}
