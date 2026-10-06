package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.SendOutcome
import dev.hermeskotlin.core.chat.VoiceLiveTurn
import dev.hermeskotlin.core.voice.LiveCall
import dev.hermeskotlin.core.voice.LiveCommands
import dev.hermeskotlin.core.voice.LiveEvent
import dev.hermeskotlin.core.voice.LiveFragment
import dev.hermeskotlin.core.voice.LiveHistoryMessage
import dev.hermeskotlin.core.voice.commentaryChunks
import dev.hermeskotlin.core.voice.delegationPrompt
import dev.hermeskotlin.core.voice.isVoiceStopCommand
import dev.hermeskotlin.core.voice.liveHistory
import dev.hermeskotlin.core.voice.parseLiveEvent
import dev.hermeskotlin.core.voice.speakableCut
import dev.hermeskotlin.core.voice.speakableText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One GPT-Live call in a chat, Desktop's useVoiceLiveConversation: the voice model owns the microphone and
 * the speaker; each request it hands over becomes a Hermes turn in [session], and the reply streams back to
 * it a sentence at a time while Hermes writes it, with the running tool as quiet progress.
 *
 * A newer request stops a turn still running, so what the voice says answers what was asked last.
 */
internal class LiveConversation(
    private val call: LiveCall,
    private val session: ChatSession,
    private val state: MutableStateFlow<VoiceChatState>,
) {
    private val transcript = ArrayList<LiveFragment>()
    private var eventSeq = 0
    private var delegation: Job? = null
    private var delegationId: String? = null

    /** The chat so far, for the voice to open with. */
    fun history(): List<LiveHistoryMessage> = liveHistory(
        session.state.value.messages.mapNotNull { message ->
            when (message) {
                is ChatMessage.User -> LiveFragment.Speaker.User to message.text
                is ChatMessage.Assistant -> LiveFragment.Speaker.Assistant to speakableText(message.text)
                else -> null
            }
        },
    )

    /**
     * Runs the call until it ends: hung up, "stop" said, or dropped. [connect] opens it (it throws when it
     * can't). Returns what went wrong, or null for an ending the person chose.
     */
    suspend fun run(connect: suspend () -> Unit): String? = coroutineScope {
        val ended = CompletableDeferred<String?>()
        try {
            connect()
        } catch (e: CancellationException) {
            call.close()
            throw e
        } catch (e: Exception) {
            call.close()
            return@coroutineScope e.message ?: "Couldn't start the live call."
        }
        state.update { it.copy(phase = VoicePhase.Listening) }
        val poll = launch {
            while (isActive) {
                val speaking = call.speaking()
                state.update {
                    it.copy(
                        phase = when {
                            speaking -> VoicePhase.Speaking
                            delegationId != null -> VoicePhase.Thinking
                            else -> VoicePhase.Listening
                        },
                        level = if (it.muted || speaking) 0f else call.micLevel(),
                    )
                }
                delay(POLL_MS)
            }
        }
        val reader = launch {
            var utterance = StringBuilder()
            var settle: Job? = null
            for (raw in call.events) {
                when (val event = parseLiveEvent(raw)) {
                    is LiveEvent.Transcript -> {
                        transcript += event.fragment
                        if (transcript.size > MAX_FRAGMENTS) transcript.subList(0, transcript.size - KEPT_FRAGMENTS).clear()
                        if (event.fragment.speaker != LiveFragment.Speaker.User) continue
                        // The voice answers a bare "stop" itself and never hands it over, so the whole utterance is
                        // judged once it settles: "stop" ends the call, "stop the container" is a request.
                        utterance.append(event.fragment.text)
                        settle?.cancel()
                        settle = launch {
                            delay(UTTERANCE_SETTLE_MS)
                            val said = utterance.toString()
                            utterance = StringBuilder()
                            if (isVoiceStopCommand(said)) ended.complete(null)
                        }
                    }
                    is LiveEvent.Delegation -> {
                        val ask = delegationPrompt(contextWindow())
                        if (ask.prompt.isNotEmpty() && isVoiceStopCommand(ask.prompt)) ended.complete(null)
                        else delegate(event.id, ask.prompt, ask.context)
                    }
                    is LiveEvent.Failure -> state.update { it.copy(error = event.message) }
                    is LiveEvent.Closed -> {
                        ended.complete(if (event.reason == "closed") "The live call ended." else "The live call ended: ${event.reason}")
                        break
                    }
                    LiveEvent.Started, null -> Unit
                }
            }
            ended.complete("The live call dropped.")
        }
        try {
            ended.await()
        } finally {
            poll.cancel()
            reader.cancel()
            delegation?.cancel()
            call.send(LiveCommands.close())
            call.close()
        }
    }

    fun setMuted(muted: Boolean) {
        call.setMuted(muted)
        call.send(LiveCommands.mute(nextId(if (muted) "mute" else "unmute"), muted))
    }

    /** The recent conversation, oldest first: the last five minutes, at most 80 pieces. */
    private fun contextWindow(): List<LiveFragment> {
        val last = transcript.lastOrNull() ?: return emptyList()
        return transcript.filter { it.endMs >= last.endMs - CONTEXT_WINDOW_MS }.takeLast(CONTEXT_FRAGMENTS)
    }

    private fun CoroutineScope.delegate(id: String, prompt: String, context: String) {
        delegation?.cancel()
        delegationId = id
        delegation = launch {
            try {
                answer(id, prompt, context)
            } finally {
                if (delegationId == id) delegationId = null
            }
        }
    }

    /** Sends the request to Hermes and feeds the reply to the voice as it streams. */
    private suspend fun answer(id: String, prompt: String, context: String) {
        val sent = session.stopAndSubmit(prompt, voiceLive = VoiceLiveTurn(context)).outcome
        if (sent != SendOutcome.Sent) {
            speak(id, "Sorry, I couldn't reach Hermes for that request.")
            state.update { it.copy(error = session.state.value.error ?: "Couldn't send that to Hermes.") }
            return
        }
        // The prompt just sent. A history reload swaps its key for its stored row's; then it's the last prompt.
        val askedKey = session.state.value.messages.lastOrNull { it is ChatMessage.User }?.key
        fun asked(chat: ChatState) = chat.messages.indexOfFirst { it.key == askedKey }.takeIf { it >= 0 }
            ?: chat.messages.indexOfLast { it is ChatMessage.User }
        fun queued(chat: ChatState) = chat.messages.any { it.key == askedKey && it is ChatMessage.User && it.queued }
        // A prompt queued behind a turn that wouldn't stop: that turn isn't its answer. Its own turn starts
        // once that one ends, or the prompt was dropped (a Stop in the chat drops the queue).
        if (queued(session.state.value)) {
            session.state.first { !queued(it) || !it.running }
            withTimeoutOrNull(TURN_START_MS) { session.state.first { !queued(it) } }
            val chat = session.state.value
            when {
                queued(chat) -> return send(LiveCommands.thinking(nextId("think"), id, "That request is still waiting in Hermes's queue."))
                chat.messages.none { it.key == askedKey } -> return send(LiveCommands.thinking(nextId("think"), id, "That request was dropped before Hermes ran it."))
            }
        }
        withTimeoutOrNull(TURN_START_MS) { session.state.first { it.running } }
        // The markdown spoken so far of each reply of the turn, in order. A reply reloaded from history that
        // no longer starts with it counts as spoken, so nothing is said twice.
        val spoken = mutableListOf<String>()
        var spokeAny = false
        var tool: String? = null
        session.state.first { chat ->
            val asked = asked(chat)
            val replies = if (asked < 0) emptyList() else chat.messages.drop(asked + 1).filterIsInstance<ChatMessage.Assistant>()
            val running = replies.lastOrNull()?.tools?.lastOrNull { it.running }?.let { it.detail ?: it.name }
            if (running != null && running != tool) {
                tool = running
                send(LiveCommands.thinking(nextId("think"), id, "Hermes is working: $running. Not done yet."))
            }
            replies.forEachIndexed { i, reply ->
                val before = spoken.getOrNull(i).orEmpty()
                val from = if (reply.text.startsWith(before)) before.length else reply.text.length
                // A reply still streaming goes a whole sentence at a time; a finished one goes to its end.
                val end = maxOf(from, if (reply.streaming && chat.running) speakableCut(reply.text) else reply.text.length)
                if (end > from) spokeAny = speak(id, reply.text.substring(from, end)) || spokeAny
                if (i < spoken.size) spoken[i] = reply.text.substring(0, end) else spoken += reply.text.substring(0, end)
            }
            if (!chat.running && !spokeAny) send(LiveCommands.thinking(nextId("think"), id, "Hermes finished that request without a spoken result."))
            !chat.running
        }
    }

    /** Hands [markdown] to the voice as it should sound; false when none of it is speakable. */
    private fun speak(delegationId: String, markdown: String): Boolean {
        val chunks = commentaryChunks(speakableText(markdown))
        for (chunk in chunks) send(LiveCommands.commentary(nextId("say"), delegationId, chunk))
        return chunks.isNotEmpty()
    }

    private fun send(event: String) {
        call.send(event)
    }

    private fun nextId(prefix: String) = "${prefix}_${++eventSeq}"

    private companion object {
        const val POLL_MS = 100L
        const val UTTERANCE_SETTLE_MS = 1_500L
        const val TURN_START_MS = 15_000L
        const val CONTEXT_WINDOW_MS = 5 * 60_000L
        const val CONTEXT_FRAGMENTS = 80
        const val MAX_FRAGMENTS = 2_000
        const val KEPT_FRAGMENTS = 1_500
    }
}
