package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.SendOutcome
import dev.hermeskotlin.core.chat.VoiceLiveTurn
import dev.hermeskotlin.core.voice.LiveCall
import dev.hermeskotlin.core.voice.LiveFragment
import dev.hermeskotlin.core.voice.SubscriptionCommands
import dev.hermeskotlin.core.voice.SubscriptionEvent
import dev.hermeskotlin.core.voice.isVoiceStopCommand
import dev.hermeskotlin.core.voice.parseSubscriptionEvent
import dev.hermeskotlin.core.voice.speakableText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One call with the talk-desktop plugin's voice, on the ChatGPT plan, in a chat: the voice owns the microphone and the
 * speaker; each request it hands over becomes a Hermes turn in [session], one at a time as the desktop client runs
 * them, and the turn's reply goes back to it once the turn ends.
 */
internal class SubscriptionConversation(
    private val call: LiveCall,
    private val session: ChatSession,
    private val state: MutableStateFlow<VoiceChatState>,
) {
    /** Herald runs what the voice hands over; false once the plugin said it runs it itself. */
    var clientHandoff = true

    private val handovers = Channel<SubscriptionEvent.Delegation>(Channel.UNLIMITED)
    /** A handover is waiting or running. */
    private var working = 0
    /** What the person said last, for a handover that came without its words. */
    private var lastSaid = ""
    /** The spoken exchange so far, newest last, as the turn's context. */
    private val exchange = ArrayDeque<String>()

    /**
     * Runs the call until it ends: hung up, "stop" said, or dropped. [connect] opens it (it throws when it can't).
     * Returns what went wrong, or null for an ending the person chose.
     */
    suspend fun run(connect: suspend () -> Unit): String? = coroutineScope {
        try {
            connect()
        } catch (e: CancellationException) {
            call.close()
            throw e
        } catch (e: Exception) {
            call.close()
            return@coroutineScope e.message ?: "Couldn't start the live call."
        }
        val ended = CompletableDeferred<String?>()
        state.update { it.copy(phase = VoicePhase.Listening) }
        val poll = launch {
            while (isActive) {
                val speaking = call.speaking()
                state.update {
                    it.copy(
                        phase = when {
                            speaking -> VoicePhase.Speaking
                            working > 0 -> VoicePhase.Thinking
                            else -> VoicePhase.Listening
                        },
                        level = if (it.muted || speaking) 0f else call.micLevel(),
                    )
                }
                delay(POLL_MS)
            }
        }
        val worker = launch {
            for (handover in handovers) {
                try {
                    handle(handover)
                } finally {
                    working--
                    state.update { it.copy(working = null) }
                }
            }
        }
        val reader = launch {
            for (raw in call.events) {
                when (val event = parseSubscriptionEvent(raw)) {
                    is SubscriptionEvent.Heard -> caption(event.speaker, event.text)
                    is SubscriptionEvent.TurnDone -> {
                        val said = event.transcript.trim()
                        if (said.isEmpty()) continue
                        remember(event.speaker, said)
                        if (event.speaker != LiveFragment.Speaker.User) continue
                        lastSaid = said
                        // The voice answers a bare "stop" itself and never hands it over.
                        if (isVoiceStopCommand(said)) ended.complete(null)
                    }
                    is SubscriptionEvent.Delegation -> if (clientHandoff) {
                        working++
                        handovers.trySend(event)
                    }
                    is SubscriptionEvent.Failure -> state.update { it.copy(error = event.message) }
                    SubscriptionEvent.Started, null -> Unit
                }
            }
            ended.complete("The live call dropped.")
        }
        try {
            ended.await()
        } finally {
            poll.cancel()
            reader.cancel()
            worker.cancel()
            handovers.close()
            call.close()
        }
    }

    fun setMuted(muted: Boolean) = call.setMuted(muted)

    /** Runs one request the voice handed over as a Hermes turn and gives the voice what came of it. */
    private suspend fun handle(handover: SubscriptionEvent.Delegation) {
        val id = handover.itemId
        val request = handover.request.ifBlank { lastSaid }.trim()
        if (request.isEmpty()) return respond(id, "No intelligible request was available. Nothing was submitted.")
        // A turn already running (typed in the chat, say) finishes first: one turn at a time, as on the desktop.
        if (session.state.value.running) {
            respond(id, "This request is queued behind the work running in the chat. It has not started yet.")
            withTimeoutOrNull(TURN_MS) { session.state.first { !it.running } }
                ?: return respond(id, "The work running in the chat hasn't finished, so this request wasn't submitted.")
        }
        val context = exchange.joinToString("\n")
        if (session.submit(request, voiceLive = VoiceLiveTurn(context)) != SendOutcome.Sent) {
            state.update { it.copy(error = session.state.value.error ?: "Couldn't send that to Hermes.") }
            return respond(id, "The handoff failed: Hermes couldn't be reached. Nothing was submitted.")
        }
        // The prompt just sent. A history reload swaps its key for its stored row's; then it's the last prompt.
        val askedKey = session.state.value.messages.lastOrNull { it is ChatMessage.User }?.key
        withTimeoutOrNull(TURN_START_MS) { session.state.first { it.running } }
        var toldWaiting = false
        val done = withTimeoutOrNull(TURN_MS) {
            session.state.first { chat ->
                val tool = chat.messages.lastOrNull()
                    .let { it as? ChatMessage.Assistant }?.tools?.lastOrNull { it.running }?.let { it.detail ?: it.name }
                if (tool != state.value.working) state.update { it.copy(working = tool) }
                // An approval or a question stops the turn; it's answered in the chat.
                if (chat.inputRequests.isNotEmpty() && !toldWaiting) {
                    toldWaiting = true
                    respond(id, "Hermes is waiting for an approval or an answer in the chat on the phone. The result is not ready yet.")
                }
                !chat.running
            }
        }
        if (done == null) return respond(id, "Hermes is still working on it. The result will be in the chat.")
        val reply = speakableText(replyTo(done, askedKey)).trim()
        remember(LiveFragment.Speaker.Assistant, reply)
        respond(id, if (reply.isEmpty()) "Hermes finished without a written result. Check the chat." else "Agent result for this request:\n$reply")
    }

    /** What Hermes wrote after the prompt [askedKey], all of the turn's replies. */
    private fun replyTo(chat: ChatState, askedKey: String?): String {
        val asked = chat.messages.indexOfFirst { it.key == askedKey }.takeIf { it >= 0 }
            ?: chat.messages.indexOfLast { it is ChatMessage.User }
        if (asked < 0) return ""
        return chat.messages.drop(asked + 1).filterIsInstance<ChatMessage.Assistant>().joinToString("\n\n") { it.text.trim() }
    }

    private fun respond(id: String, text: String) {
        call.send(SubscriptionCommands.delegationResult(id, text))
    }

    private fun remember(speaker: LiveFragment.Speaker, text: String) {
        val line = text.replace(WHITESPACE, " ").take(LINE_CHARS)
        exchange.addLast("${if (speaker == LiveFragment.Speaker.User) "User" else "Voice assistant"}: $line")
        while (exchange.size > CONTEXT_LINES) exchange.removeFirst()
    }

    /** Shows what's being said as captions: the speaker's words so far, or theirs anew once the other one spoke. */
    private fun caption(speaker: LiveFragment.Speaker, text: String) = state.update {
        val voice = speaker == LiveFragment.Speaker.Assistant
        val words = (if (it.caption != null && it.captionIsVoice == voice) it.caption + text else text).trimStart()
        val shown = if (words.length <= CAPTION_CHARS) words else "…" + words.takeLast(CAPTION_CHARS).substringAfter(' ')
        it.copy(caption = shown.ifBlank { null }, captionIsVoice = voice)
    }

    private companion object {
        const val POLL_MS = 100L
        const val TURN_START_MS = 15_000L
        /** The longest a turn is waited on before the voice is told the result will be in the chat. */
        const val TURN_MS = 10 * 60_000L
        const val CAPTION_CHARS = 140
        const val CONTEXT_LINES = 12
        const val LINE_CHARS = 600
        val WHITESPACE = Regex("""\s+""")
    }
}
