package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.InputAnswers
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.SendOutcome
import dev.hermeskotlin.core.chat.VoiceLiveTurn
import dev.hermeskotlin.core.voice.LiveCall
import dev.hermeskotlin.core.voice.LiveCommands
import dev.hermeskotlin.core.voice.LiveEvent
import dev.hermeskotlin.core.voice.LiveFragment
import dev.hermeskotlin.core.voice.LiveHistoryMessage
import dev.hermeskotlin.core.voice.VoiceAnswers
import dev.hermeskotlin.core.voice.DeviceLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
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
import kotlinx.coroutines.sync.Mutex
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
    /** An approval is only taken while the phone is unlocked. */
    private val lock: DeviceLock = DeviceLock.None,
    /** A call the agent placed: what the voice is told to say first, why it called. */
    private val opener: String? = null,
) {
    private val transcript = ArrayList<LiveFragment>()
    private var eventSeq = 0
    private var delegation: Job? = null
    private var delegationId: String? = null
    /** The question or approval the voice was given to ask; the next request it hands over answers it. */
    private var asking: InputRequest? = null
    /**
     * What was said, by both, since [asking] began: the answer is the person's last words in it. Their transcript
     * lags the voice's, so it's put in order by time, not by when it came; a piece without times goes after the one
     * before it.
     */
    private val heard = ArrayList<Pair<LiveFragment, Long>>()
    /** Each piece the person says, counted: their answer is taken once they stop. */
    private var userPieces = 0
    /** The request an answer is going out for: another handover meanwhile isn't a second answer. */
    private var answering: String? = null
    /** Taking what the person said as the answer, the voice not having handed it over (yet). */
    private var taking: Job? = null
    /**
     * Held while what was said is judged as the answer, until the answer is sent: one judging at a time, so the
     * voice handing over what was just taken waits for it to be in, not for Hermes's whole turn after it.
     */
    private val judging = Mutex()
    /**
     * Where the person's last answer ended in the call (0 when its pieces had no times), while they haven't said
     * anything new since: a handover then is that answer again, not a new request.
     */
    private var answeredUpTo: Long? = null
    private val announced = mutableSetOf<String>()
    /** Approvals the voice said wait for the phone to be unlocked. */
    private val heldBack = mutableSetOf<String>()
    /** The voice was told how to ask for Hermes. */
    private var steered = false

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
        val started = CompletableDeferred<Unit>()
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
                            // Or the turn after an answer taken from what was said.
                            delegationId != null || delegation?.isActive == true -> VoicePhase.Thinking
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
                        caption(event.fragment)
                        val fragment = event.fragment
                        if (asking != null) heard += fragment to (if (timed(fragment)) fragment.startMs else heard.lastOrNull()?.second ?: 0)
                        if (fragment.speaker != LiveFragment.Speaker.User) continue
                        userPieces++
                        // New words, said after the answer; without times to tell, any that come now.
                        val upTo = answeredUpTo
                        if (fragment.text.isNotBlank() && upTo != null && (upTo == 0L || !timed(fragment) || fragment.startMs > upTo)) {
                            answeredUpTo = null
                        }
                        // The voice answers a bare "stop" itself and never hands it over, so the whole utterance is
                        // judged once it settles: "stop" ends the call, "stop the container" is a request.
                        utterance.append(event.fragment.text)
                        settle?.cancel()
                        settle = launch {
                            delay(UTTERANCE_SETTLE_MS)
                            val said = utterance.toString()
                            utterance = StringBuilder()
                            if (isVoiceStopCommand(said)) {
                                ended.complete(null)
                                return@launch
                            }
                            // The voice may well talk on instead of handing the answer over: a clear one is taken
                            // as it is.
                            val asked = asking ?: return@launch
                            if (!judging.isLocked && answering != asked.id && VoiceAnswers.takesAsAnswer(asked, lastWords(putToThem = true))) {
                                taking = this@coroutineScope.launch { answerAsked(null, asked) }
                            }
                        }
                    }
                    is LiveEvent.Delegation -> {
                        val ask = delegationPrompt(contextWindow())
                        val asked = asking
                        when {
                            ask.prompt.isNotEmpty() && isVoiceStopCommand(ask.prompt) -> ended.complete(null)
                            // The answer to what Hermes asked, not a new request: the turn waiting on it goes on.
                            // Once one taken meanwhile is in, it's that one.
                            asked != null -> launch { answerAsked(event.id, asked) }
                            // Nothing said since the answer, yet: the same answer handed over again, unless new words
                            // come in late.
                            answeredUpTo != null -> launch {
                                withTimeoutOrNull(NEW_WORDS_WAIT_MS) { while (answeredUpTo != null) delay(POLL_MS) }
                                val again = delegationPrompt(contextWindow())
                                when {
                                    answeredUpTo != null -> send(LiveCommands.commentary(nextId("say"), event.id, ALREADY_ANSWERED))
                                    isVoiceStopCommand(again.prompt) -> ended.complete(null)
                                    session.state.value.inputRequests.isNotEmpty() -> send(
                                        LiveCommands.commentary(nextId("say"), event.id, WAITING_ELSEWHERE),
                                    )
                                    else -> delegate(event.id, again.prompt, again.context)
                                }
                            }
                            // A new request would stop the turn that waits on an answer.
                            session.state.value.inputRequests.isNotEmpty() -> send(
                                LiveCommands.commentary(nextId("say"), event.id, WAITING_ELSEWHERE),
                            )
                            else -> delegate(event.id, ask.prompt, ask.context)
                        }
                    }
                    is LiveEvent.Failure -> state.update { it.copy(error = event.message) }
                    is LiveEvent.Closed -> {
                        ended.complete(if (event.reason == "closed") "The live call ended." else "The live call ended: ${event.reason}")
                        break
                    }
                    LiveEvent.Started -> started.complete(Unit)
                    null -> Unit
                }
            }
            ended.complete("The live call dropped.")
        }
        // A call the agent placed: the voice says why first, once it's up (it drops what comes earlier).
        if (opener != null) {
            launch {
                started.await()
                speakAside(opener)
            }
        }
        // A question or an approval Hermes stops on goes to the voice to ask, once each; not before the voice is up,
        // which drops what comes earlier.
        // One at a time: the next waits until the last is answered. Looked at again now and then too, for an approval
        // held back while the phone was locked.
        val asker = launch {
            started.await()
            while (isActive) {
                val chat = session.state.value
                asking?.let { if (!waiting(it)) asking = null }
                if (asking == null) askNext(chat)
                withTimeoutOrNull(LOCK_CHECK_MS) { session.state.first { it !== chat } }
            }
        }
        try {
            ended.await()
        } finally {
            asker.cancel()
            poll.cancel()
            reader.cancel()
            taking?.cancel()
            delegation?.cancel()
            call.send(LiveCommands.close())
            call.close()
        }
    }

    fun setMuted(muted: Boolean) {
        call.setMuted(muted)
        call.send(LiveCommands.mute(nextId(if (muted) "mute" else "unmute"), muted))
    }

    /** Shows [fragment] as captions: the speaker's words so far, or theirs anew once the other one spoke. */
    private fun caption(fragment: LiveFragment) = state.update {
        val voice = fragment.speaker == LiveFragment.Speaker.Assistant
        val words = (if (it.caption != null && it.captionIsVoice == voice) it.caption + fragment.text else fragment.text).trimStart()
        // The tail that fits, from a word's start.
        val shown = if (words.length <= CAPTION_CHARS) words else "…" + words.takeLast(CAPTION_CHARS).substringAfter(' ')
        it.copy(caption = shown.ifBlank { null }, captionIsVoice = voice)
    }

    /** The recent conversation, oldest first: the last five minutes, at most 80 pieces. */
    private fun contextWindow(): List<LiveFragment> {
        val last = transcript.lastOrNull() ?: return emptyList()
        return transcript.filter { it.endMs >= last.endMs - CONTEXT_WINDOW_MS }.takeLast(CONTEXT_FRAGMENTS)
    }

    private fun CoroutineScope.delegate(id: String, prompt: String, context: String) {
        delegation?.cancel()
        delegationId = id
        // The request it replaces doesn't clear its tool once it's no longer the current one.
        state.update { it.copy(working = null) }
        delegation = launch {
            try {
                answer(id, prompt, context)
            } finally {
                if (delegationId == id) {
                    delegationId = null
                    state.update { it.copy(working = null) }
                }
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
        relayTurn(id, askedKey)
    }

    /**
     * Feeds the voice the replies to the prompt [askedKey] as they stream, until its turn ends. [alreadySaid] is
     * what of each reply was there before, not to be said again.
     */
    private suspend fun relayTurn(id: String?, askedKey: String?, alreadySaid: List<String> = emptyList()) {
        fun asked(chat: ChatState) = chat.messages.indexOfFirst { it.key == askedKey }.takeIf { it >= 0 }
            ?: chat.messages.indexOfLast { it is ChatMessage.User }
        // The markdown spoken so far of each reply of the turn, in order. A reply reloaded from history that
        // no longer starts with it counts as spoken, so nothing is said twice.
        val spoken = alreadySaid.toMutableList()
        var spokeAny = false
        var tool: String? = null
        session.state.first { chat ->
            val asked = asked(chat)
            val replies = if (asked < 0) emptyList() else chat.messages.drop(asked + 1).filterIsInstance<ChatMessage.Assistant>()
            val running = replies.lastOrNull()?.tools?.lastOrNull { it.running }?.let { it.detail ?: it.name }
            // The chip follows the tool running now, and goes once it's done.
            if (running != tool) {
                tool = running
                state.update { it.copy(working = running) }
                if (running != null) send(LiveCommands.thinking(nextId("think"), id, "Hermes is working: $running. Not done yet."))
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

    /** Hands the voice the next question or approval [chat] waits on to ask, or says why it can't. */
    private fun askNext(chat: ChatState) {
        val request = chat.inputRequests.firstOrNull { VoiceAnswers.canAsk(it) && it.id !in announced } ?: return
        // From a locked phone an approval isn't taken; it's asked once the phone is unlocked.
        if (request is InputRequest.Approval && lock.locked()) {
            if (request.id !in heldBack) speakAside("Hermes needs your approval. Unlock the phone, and I'll ask you for it.")
            heldBack += request.id
            return
        }
        announced += request.id
        // Several questions at once are too many to take by ear.
        val prompt = askPrompt(request) ?: return speakAside("Hermes has a few questions for you. They're waiting in the chat.")
        if (!steered) send(LiveCommands.instructions(nextId("steer"), ASKING_INSTRUCTIONS))
        steered = true
        heard.clear()
        asking = request
        speakAside("Hermes needs an answer before it goes on. $prompt")
    }

    /**
     * The person's last words in [heard], in the call's order: what they said after the voice last spoke before them.
     * The voice answering them doesn't cut them short, even when its words came first. [putToThem]: only once the voice
     * asked them something before those words. The end of what it said before it was given the question can still
     * come in after, and a "yeah" to that isn't the answer.
     */
    private fun lastWordsSaid(putToThem: Boolean = false): List<LiveFragment> {
        // Stable: pieces at the same time keep the order they came in.
        val inOrder = heard.sortedBy { it.second }.map { it.first }.filter { it.text.isNotBlank() }
        val last = inOrder.indexOfLast { it.speaker == LiveFragment.Speaker.User }
        if (last < 0) return emptyList()
        val voice = inOrder.subList(0, last).indexOfLast { it.speaker != LiveFragment.Speaker.User }
        // Only what's said after the voice asked: words from before it, late to come in, aren't the answer.
        if (voice < 0) return emptyList()
        if (putToThem && !askedThem(inOrder.subList(0, voice + 1))) return emptyList()
        return inOrder.subList(voice + 1, last + 1)
    }

    /**
     * Whether the voice, in [said], put the question to them: it asked something, and for an approval it offered an
     * answer too. "Want me to keep you posted?", left over from before, isn't asking for the approval.
     */
    private fun askedThem(said: List<LiveFragment>): Boolean {
        val voice = said.filter { it.speaker != LiveFragment.Speaker.User }.joinToString("") { it.text }
        if ('?' !in voice) return false
        if (asking !is InputRequest.Approval) return true
        // Letters by hand: Kotlin/Native's regex doesn't know `\p{L}`.
        val words = buildString { voice.lowercase().forEach { append(if (it.isLetter()) it else ' ') } }.split(' ').toSet()
        return APPROVAL_CUES.any { it in words }
    }

    /** Whether [fragment] says when in the call it was said. */
    private fun timed(fragment: LiveFragment) = fragment.startMs > 0 || fragment.endMs > 0

    private fun lastWords(putToThem: Boolean = false): String = lastWordsSaid(putToThem).joinToString("") { it.text }

    /** Whether [request] still waits on an answer. */
    private fun waiting(request: InputRequest) = session.state.value.inputRequests.any { it.id == request.id }

    /**
     * Takes the request the voice handed over as the answer to [request], the one it was given to ask, in the
     * person's own words: never the voice's paraphrase, which may restate an earlier request as if it were the
     * answer. Their words may still be on the way when the voice hands over; with none, or none that are clear, it's
     * asked again. Never a new request either: one handed over twice, or after the answer came from the screen,
     * would stop the turn that just got its answer. With no [id] the voice didn't hand anything over: what the person
     * said is taken as it is ([VoiceAnswers.takesAsAnswer]), and nothing is said when it's already in.
     */
    private suspend fun answerAsked(id: String?, request: InputRequest) {
        judging.lock()
        var judged = false
        fun letGo() {
            if (!judged) judging.unlock()
            judged = true
        }
        try {
            judgeAnswer(id, request, ::letGo)
        } finally {
            letGo()
        }
    }

    /** [answerAsked] while it judges; [sent] once the answer is in, or isn't going. */
    private suspend fun judgeAnswer(id: String?, request: InputRequest, sent: () -> Unit) {
        fun alreadyAnswered() {
            if (id != null) send(LiveCommands.commentary(nextId("say"), id, ALREADY_ANSWERED))
        }
        if (!waiting(request) || answering == request.id) return alreadyAnswered()
        withTimeoutOrNull(WORDS_WAIT_MS) { while (lastWords().isBlank()) delay(POLL_MS) }
        // Not a word before they stop: "yes… but not on prod" isn't a yes.
        withTimeoutOrNull(MAX_ANSWER_MS) {
            do {
                val pieces = userPieces
                delay(UTTERANCE_SETTLE_MS)
            } while (userPieces != pieces)
        }
        if (asking?.id != request.id || !waiting(request) || answering == request.id) return alreadyAnswered()
        if (request is InputRequest.Approval && lock.locked()) {
            return send(LiveCommands.commentary(nextId("say"), id, "The phone locked. Ask the user to unlock it, then to say it again."))
        }
        // Taken without a handover only once the voice asked: else it's the voice's to hand over.
        val words = lastWordsSaid(putToThem = id == null)
        if (id == null && words.isEmpty()) return
        val said = words.joinToString("") { it.text }.trim()
        val wasHeard = heard.toList()
        heard.clear()
        val answer = when (request) {
            is InputRequest.Approval -> VoiceAnswers.approval(request, said)?.let(InputAnswers::approval)
            is InputRequest.Clarify -> VoiceAnswers.clarify(request.questions.single(), said)
                .takeIf { it.isNotEmpty() }
                ?.let { InputAnswers.clarify(request, listOf(it)) }
            else -> null
        }
        if (answer == null) return send(LiveCommands.commentary(nextId("say"), id, "That wasn't clear. ${askPrompt(request)}"))
        // Before the answer goes: the turn's replies so far were there before it, and aren't said again.
        val askedKey = session.state.value.messages.lastOrNull { it is ChatMessage.User }?.key
        val before = session.state.value.messages.let { messages ->
            val from = messages.indexOfLast { it.key == askedKey }
            messages.drop(from + 1).filterIsInstance<ChatMessage.Assistant>().map { it.text }
        }
        answering = request.id
        val answered = try {
            session.answer(request, answer)
        } finally {
            answering = null
        }
        if (answered) {
            asking = null
            answeredUpTo = words.maxOf { it.endMs }
        } else {
            // A handover of the same words, waiting meanwhile, tries them again.
            heard.addAll(0, wasHeard)
        }
        sent()
        if (!answered) {
            // Still asking: what's said next is another go at the answer, not a request that stops the turn.
            send(LiveCommands.commentary(nextId("say"), id, "The answer couldn't reach Hermes. Ask the user to say it again."))
            state.update { it.copy(error = session.state.value.error ?: "Couldn't send the answer.") }
            return
        }
        send(LiveCommands.thinking(nextId("think"), id, "Hermes has the answer and carries on."))
        // A turn no request of this call started (the one that rang): what it says next goes to the voice too.
        if (delegation?.isActive == true) return
        delegationId = id
        delegation = currentCoroutineContext().job
        try {
            relayTurn(id, askedKey, before)
        } finally {
            if (delegationId == id) {
                delegationId = null
                state.update { it.copy(working = null) }
            }
        }
    }

    /** What the voice asks for [request]; null for one it can't take by ear. */
    private fun askPrompt(request: InputRequest): String? = when {
        request is InputRequest.Approval -> VoiceAnswers.approvalPrompt(request)
        // The gateway sends even a lone question as a batch of one.
        request is InputRequest.Clarify && request.questions.size == 1 -> VoiceAnswers.questionPrompt(request.questions.single())
        else -> null
    }

    /** Tells the voice something outside any request's result, for it to say in its own words. */
    private fun speakAside(content: String) = send(LiveCommands.commentary(nextId("say"), delegationId, content))

    /** Hands [markdown] to the voice as it should sound; false when none of it is speakable. */
    private fun speak(delegationId: String?, markdown: String): Boolean {
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
        const val CAPTION_CHARS = 140
        /** How long the person's words may trail the voice handing their answer over. */
        const val WORDS_WAIT_MS = 2_000L
        /** The longest an answer is waited on to be finished. */
        const val MAX_ANSWER_MS = 20_000L
        /** How long a handover right after an answer waits for words of its own before it's taken as that answer. */
        const val NEW_WORDS_WAIT_MS = 4_000L
        const val LOCK_CHECK_MS = 1_000L
        /** Words a voice asking for an approval offers as its answers. */
        val APPROVAL_CUES = setOf("yes", "no", "approve", "allow", "deny")
        const val ALREADY_ANSWERED = "Hermes already has that answer."
        const val WAITING_ELSEWHERE = "Hermes is waiting on an answer in the chat, so it can't take a new request until then."
        const val ASKING_INSTRUCTIONS = "When Hermes needs an answer from the user (an approval or a question), ask it in your own " +
            "words with every option it offers, then hand the user's reply over to Hermes as it is, without answering for them."
    }
}
