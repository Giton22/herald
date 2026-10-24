package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.InputAnswers
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.SendOutcome
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.phone.CallReason
import dev.hermeskotlin.core.phone.liveOpener
import dev.hermeskotlin.core.phone.spokenOpener
import dev.hermeskotlin.core.settings.DictationEngine
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.BargeIn
import dev.hermeskotlin.core.voice.BargeInSettings
import dev.hermeskotlin.core.voice.DeviceDictation
import dev.hermeskotlin.core.voice.DeviceDictationUnavailable
import dev.hermeskotlin.core.voice.LiveCalls
import dev.hermeskotlin.core.voice.ReadAloudVoices
import dev.hermeskotlin.core.voice.InMemoryReadAloudVoices
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpeechStream
import dev.hermeskotlin.core.voice.SpeechStreamEvent
import dev.hermeskotlin.core.voice.SpeechStreams
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceAnswers
import dev.hermeskotlin.core.voice.DeviceLock
import dev.hermeskotlin.core.voice.VoiceRecorder
import dev.hermeskotlin.core.voice.isTtsEcho
import dev.hermeskotlin.core.voice.isVoiceStopCommand
import dev.hermeskotlin.core.voice.readAloudText
import dev.hermeskotlin.core.voice.speakableText
import dev.hermeskotlin.core.voice.speechChunks
import dev.hermeskotlin.core.voice.speakableSoFar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class VoicePhase { Off, Connecting, Listening, Transcribing, Thinking, Speaking }

data class VoiceChatState(
    val phase: VoicePhase = VoicePhase.Off,
    /** Live microphone level while listening, 0..1. */
    val level: Float = 0f,
    /** Speech has been picked up in this turn, so a pause will send it. */
    val hearing: Boolean = false,
    /** The last thing that went wrong; the conversation carries on where it can. */
    val error: String? = null,
    /** A GPT-Live call rather than Desktop's listen, send and read aloud. */
    val live: Boolean = false,
    /** The microphone is off in a live call. */
    val muted: Boolean = false,
    /** The latest words said, as captions: yours, or the voice's when [captionIsVoice]. */
    val caption: String? = null,
    val captionIsVoice: Boolean = false,
    /** The tool Hermes is running for the request, while it works. */
    val working: String? = null,
    /** Talking over the reply cuts in (the profile's `voice.barge_in`). */
    val bargeIn: Boolean = false,
)

data class DictationState(
    val recording: Boolean = false,
    val transcribing: Boolean = false,
    val level: Float = 0f,
    val error: String? = null,
) {
    val active: Boolean get() = recording || transcribing
}

/** A reply read aloud from the chat, outside a voice chat. */
data class ReadAloudState(
    /** The reply being read, by its message key; null when none is. */
    val key: String? = null,
    /** Why the last reply couldn't be read. */
    val error: String? = null,
)

/**
 * Voice on this device, Desktop's way: the phone records, the gateway transcribes with the profile's
 * speech-to-text and voices replies with its text-to-speech, and the phone plays them.
 *
 * Dictation drops a transcript into the composer. A voice chat loops: listen until you stop talking,
 * send what you said, wait for the reply, read it aloud, listen again; saying "stop" ends it. Read aloud
 * voices one finished reply from the chat.
 *
 * When the profile picked GPT-Live (`voice.voice_chat_mode: gpt-live`) and the device can make the call
 * ([liveCalls]), a voice chat is a live call instead: see [LiveConversation].
 */
class VoiceController(
    private val audio: AudioApi,
    private val recorder: VoiceRecorder,
    private val player: SpeechPlayer,
    private val scope: CoroutineScope,
    /** Outlives the screen, so the speech engine lease is released even as it closes. */
    private val appScope: CoroutineScope,
    /** Keeps a voice chat going with the screen off or another app in front. */
    private val keepAlive: VoiceKeepAlive? = null,
    /** The phone's own speech recognizer, for dictation with [DictationEngine.Device]. */
    private val deviceDictation: DeviceDictation? = null,
    /** The voice of replies read aloud, so one read again is the same take. */
    private val readAloudVoices: ReadAloudVoices = InMemoryReadAloudVoices(),
    /** Voices a reply while it's still being written; without it, a reply is read out once it's done. */
    private val speechStreams: SpeechStreams? = null,
    /** An approval is only taken by voice while the phone is unlocked. */
    private val deviceLock: DeviceLock = DeviceLock.None,
    private val liveCalls: LiveCalls? = null,
) {
    private val _chat = MutableStateFlow(VoiceChatState())
    val chat: StateFlow<VoiceChatState> = _chat.asStateFlow()

    private val _dictation = MutableStateFlow(DictationState())
    val dictation: StateFlow<DictationState> = _dictation.asStateFlow()

    private val _reading = MutableStateFlow(ReadAloudState())
    val reading: StateFlow<ReadAloudState> = _reading.asStateFlow()

    private var chatJob: Job? = null
    private var stoppedByUser = false
    private var speechJob: Job? = null
    /** The gateway whose speech socket couldn't open in this chat; its replies are read out once they're done. */
    private var noSpeechStream: GatewayUrl? = null
    /** What of this turn's reply went to the speech socket, as it was written. */
    private var voicedSoFar = ""
    /** The speech socket stopped because the turn waited on the user, so what came after it is read out. */
    private var voicedUntilAsked = false
    private var dictationJob: Job? = null
    private var readingJob: Job? = null
    /** The recognizer the dictation in progress listens with; null when the gateway transcribes it. */
    private var dictating: DeviceDictation? = null
    /** The dictation in progress was asked to finish; a gateway fallback doesn't start recording then. */
    private var dictationFinished = false
    private var live: LiveConversation? = null
    private var held = false
    /** The room's quiet floor as the chat's last listen over the voice left it, for the next one. */
    private var roomFloor = 0f
    /** Herald went out of sight since the chat started. */
    private var away = false
    /** The voice is sounding right now; someone talking over it has to be louder than its bleed. */
    private val playing = MutableStateFlow(false)
    /** What the voice has been given to say of the reply, to tell its echo from someone cutting in. */
    private var voiced = ""
    /** The chained chat's barge-in settings, read once per chat; null outside one. */
    private var bargeIn: Deferred<BargeInSettings>? = null
    /** The microphone wouldn't listen over the voice in this chat; it isn't asked again. */
    private var cantListenOver = false

    /**
     * [pauseMs] is how long a quiet spell after speech has to last before it's sent. [opener]: a call the agent placed,
     * which the voice opens by saying why.
     */
    fun startChat(
        session: ChatSession,
        gateway: GatewayUrl,
        profile: String?,
        pauseMs: Long = VoiceActivity().silenceMs,
        opener: CallReason? = null,
    ) {
        if (chatJob?.isActive == true) return
        cancelDictation()
        // The voice chat has the speaker now.
        stopReading()
        _chat.value = VoiceChatState(VoicePhase.Listening)
        stoppedByUser = false
        roomFloor = 0f
        noSpeechStream = null
        // Held now, on the tap: Android only lets the microphone go to the background from the foreground.
        away = false
        held = keepAlive?.hold(
            onEnd = ::stopChat,
            onLost = {
                held = false
                // Refused after the app already went away: it can't listen from there.
                if (away) stopChat()
            },
        ) == true
        val job = scope.launch {
            try {
                if (startLive(session, gateway, profile, opener)) return@launch
                converseChained(session, gateway, profile, pauseMs, opener)
            } finally {
                // Also a chat ended before it found its mode (End during the status check). An ending the person
                // chose needs no explanation; one forced by an error keeps it. A newer chat keeps its own.
                if (chatJob == null || chatJob === coroutineContext.job) {
                    _chat.update { VoiceChatState(error = it.error.takeUnless { stoppedByUser }) }
                }
            }
        }
        chatJob = job
        job.invokeOnCompletion {
            // A newer chat may already hold it again.
            if (chatJob === job || chatJob == null) {
                held = false
                keepAlive?.release()
            }
        }
    }

    /** Desktop's chained voice chat: listen, send what you said, read the reply aloud, until it ends. */
    private suspend fun converseChained(session: ChatSession, gateway: GatewayUrl, profile: String?, pauseMs: Long, opener: CallReason?) = coroutineScope {
        appScope.launch { audio.ttsLease(gateway, active = true, profile) }
        // Read while the first listen goes on; it's needed once the first reply is on its way.
        val settings = async { audio.bargeIn(gateway, profile) }
        bargeIn = settings
        cantListenOver = false
        var failures = 0
        var cutIn: String? = null
        try {
            if (opener != null) {
                val said = opener.spokenOpener()
                _chat.update { it.copy(phase = VoicePhase.Speaking, caption = said, captionIsVoice = true) }
                speak(said, gateway, profile)
            }
            while (currentCoroutineContext().isActive) {
                val turn = converseOnce(session, gateway, profile, VoiceActivity(silenceMs = pauseMs), cutIn)
                cutIn = null
                when (turn) {
                    is Turn.CutIn -> {
                        failures = 0
                        cutIn = turn.transcript
                    }
                    Turn.End -> break
                    Turn.Done -> failures = 0
                    // Nobody spoke: nothing failed, nor came right. The last error stays on show meanwhile.
                    Turn.Quiet -> Unit
                    Turn.Failed -> {
                        // A provider that keeps failing would loop forever; give up after a few in a row.
                        if (++failures >= MAX_FAILURES) break
                        delay(RETRY_PAUSE_MS)
                    }
                }
            }
        } finally {
            bargeIn = null
            appScope.launch { audio.ttsLease(gateway, active = false, profile) }
        }
        settings.cancel()
    }

    /**
     * Herald went out of sight. Dictation and Read aloud stop; a voice chat carries on when it's held up, like a
     * call, and stops otherwise, since Android doesn't let the app listen from the background.
     */
    fun onBackground() {
        away = true
        cancelDictation()
        stopReading()
        if (!held) stopChat()
    }

    /**
     * Runs a GPT-Live call when the profile picked it and it can start; false to fall back to the chained chat
     * (another mode, no OpenAI key on the gateway, an older gateway, or no WebRTC on this device).
     */
    private suspend fun startLive(session: ChatSession, gateway: GatewayUrl, profile: String?, opener: CallReason?): Boolean {
        val calls = liveCalls ?: return false
        val status = audio.voiceLiveStatus(gateway, profile)
        if (status !is ApiResult.Success || !status.value.live) return false
        val call = calls.create() ?: return false
        _chat.value = VoiceChatState(VoicePhase.Connecting, live = true)
        val conversation = LiveConversation(call, session, _chat, deviceLock, opener?.liveOpener())
        live = conversation
        var error: String? = null
        try {
            error = conversation.run {
                call.connect { offer ->
                    when (val answer = audio.startVoiceLive(gateway, offer, conversation.history(), profile)) {
                        is ApiResult.Success -> answer.value.ifBlank { error("GPT-Live sent no answer.") }
                        else -> error(liveFailure(answer))
                    }
                }
            }
        } finally {
            live = null
            _chat.value = VoiceChatState(error = error.takeUnless { stoppedByUser })
        }
        return true
    }

    /** Turns the microphone off or on in a live call. */
    fun toggleMute() {
        val conversation = live ?: return
        val muted = !_chat.value.muted
        conversation.setMuted(muted)
        _chat.update { it.copy(muted = muted) }
    }

    fun stopChat() {
        stoppedByUser = true
        chatJob?.cancel()
        chatJob = null
    }

    /** Cuts the reply being read aloud and listens again. */
    fun skipSpeech() {
        speechJob?.cancel()
    }

    /**
     * Sends what's been said so far without waiting for the pause, where noise keeps the recording from
     * hearing one (a car, wind; #185). Only while the chained chat listens: a live call has no recording.
     */
    fun sendNow() {
        // As the button shows: once it has heard you, so the recording is under way and has something to send.
        if (!canSendNow(_chat.value) || chatJob?.isActive != true) return
        recorder.finish()
    }

    fun dismissChatError() = _chat.update { it.copy(error = null) }

    /**
     * One turn of the conversation, and how it went. [cutIn] is what someone said over the last reply, which
     * this turn sends instead of listening first.
     */
    private suspend fun converseOnce(
        session: ChatSession,
        gateway: GatewayUrl,
        profile: String?,
        activity: VoiceActivity,
        cutIn: String?,
    ): Turn {
        // Hermes already waits on an answer (the chat opened from a call for one): that comes first, then the rest of
        // its turn, whose reply follows the last prompt.
        if (cutIn == null && session.state.value.inputRequests.any(VoiceAnswers::canAsk)) {
            val from = session.state.value.messages.indexOfLast { it is ChatMessage.User } + 1
            return finishTurn(session, from, gateway, profile, activity, streamed = false)
        }
        // What was said over the last reply was heard and written down already.
        val transcript = cutIn ?: when (val heard = hear(gateway, profile, activity)) {
            Heard.Broken -> return Turn.End
            Heard.Silence -> return Turn.Quiet
            Heard.Unclear -> return Turn.Failed
            is Heard.Words -> heard.text.also { if (isVoiceStopCommand(it)) return Turn.End }
        }

        _chat.update { it.copy(phase = VoicePhase.Thinking, caption = transcript.trim(), captionIsVoice = false) }
        val sentAt = session.state.value.messages.size
        voicedSoFar = ""
        voicedUntilAsked = false
        voiced = ""
        val cut = coroutineScope {
            // The speech socket opens while the prompt goes out, so the first words don't wait for it.
            val opening = async { openSpeech(gateway, profile) }
            val sent = try {
                // What was said over a reply replaces it: a turn still running stops first.
                if (cutIn != null) session.stopAndSubmit(transcript).outcome == SendOutcome.Sent else session.send(transcript)
            } catch (e: Throwable) {
                closeUnused(opening)
                throw e
            }
            if (!sent) {
                _chat.update { it.copy(error = session.state.value.error ?: "Couldn't send that.") }
                closeUnused(opening)
                return@coroutineScope null
            }
            overVoice(session, gateway, profile, activity) { voiceAsWritten(session, sentAt, opening) }
        } ?: return Turn.Failed
        val voiced = when (cut) {
            is Cut.Over -> return cut.turn
            is Cut.Done -> cut.value
        }
        // Skipped while the agent is still at work: listen again now rather than once it's done. Anything it then
        // waits on is asked at the start of the next turn.
        if (voiced == Voiced.Skipped) return Turn.Done
        return finishTurn(session, sentAt, gateway, profile, activity, streamed = voiced == Voiced.Yes)
    }

    /** The reply once the prompt has gone, voiced as it's written where the gateway can. */
    private suspend fun voiceAsWritten(session: ChatSession, sentAt: Int, opening: Deferred<SpeechStream?>): Voiced {
        var handedOver = false
        try {
            // The turn starts once the gateway takes it.
            withTimeoutOrNull(TURN_START_MS) { session.state.first { it.running } }
            val stream = opening.await()
            handedOver = true
            // Voiced while it's written where the gateway can; otherwise read out once it's done.
            return speakAsWritten(stream, session, sentAt)
        } finally {
            // Not voiced after all (the chat ended first, or someone cut in): the socket doesn't stay open.
            if (!handedOver) closeUnused(opening)
        }
    }

    /** A speech socket that was opening for a reply that won't be voiced through it. */
    private suspend fun closeUnused(opening: Deferred<SpeechStream?>) = withContext(NonCancellable) {
        opening.cancel()
        runCatching { opening.await()?.close() }
    }

    /**
     * Runs [work], the agent at work or its voice, with the microphone open over it for someone cutting in, where
     * the profile has it on. [Cut.Done] with what [work] came to; [Cut.Over] with how the turn went when someone
     * talked over it.
     */
    private suspend fun <T> overVoice(
        session: ChatSession,
        gateway: GatewayUrl,
        profile: String?,
        activity: VoiceActivity,
        work: suspend () -> T,
    ): Cut<T> = coroutineScope {
        val done = async { work() }
        var cut: Cut<T>? = null
        while (cut == null) {
            val tripped = CompletableDeferred<Unit>()
            val over = async { listenOver(activity, tripped) }
            val someone = select {
                done.onAwait {
                    // The microphone is free again before the next listen takes it.
                    over.cancelAndJoin()
                    false
                }
                tripped.onAwait { true }
                // A microphone that couldn't listen over the voice won't on the next try either.
                over.onAwait { heard -> heard != null }
            }
            // Null when it was its own voice, or a knock: the voice went on where it held; listen again.
            cut = if (someone) heardOver(session, gateway, profile, done, over)?.let { Cut.Over(it) } else Cut.Done(done.await())
        }
        cut
    }

    /**
     * Listens over the reply for someone cutting in, where the profile has it on; [tripped] completes the moment
     * they do. What they said once they pause, or null when nobody can cut in this turn.
     */
    private suspend fun listenOver(activity: VoiceActivity, tripped: CompletableDeferred<Unit>): Recording? {
        val settings = bargeIn?.await() ?: return null
        if (!settings.enabled || cantListenOver) return null
        val detector = BargeIn(settings, activity.silenceMs, roomFloor)
        val heard = try {
            recorder.listenOver(
                detector,
                playing = { playing.value },
                // Offered once the microphone listens, not before.
                onListening = { _chat.update { it.copy(bargeIn = true) } },
                onTrip = { tripped.complete(Unit) },
                onLevel = { level -> _chat.update { if (it.phase == VoicePhase.Listening) it.copy(level = level) else it } },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            if (detector.roomFloor > 0f) roomFloor = detector.roomFloor
            // Not offered once the microphone is closed again, as while a question is asked aloud.
            _chat.update { it.copy(bargeIn = false) }
        }
        // The microphone wouldn't listen over the voice: the reply goes on as without barge-in, and doesn't offer it,
        // this turn or the next.
        if (heard == null) cantListenOver = true
        return heard
    }

    /**
     * Someone seemed to talk over the reply. The voice holds at once, and what they say is heard to its end and
     * written down. When nobody went on talking once it held, it was the phone hearing its own voice (a speaker
     * turned right up), or a knock: null, and the voice goes on where it held, nothing lost. Otherwise the reply
     * stops, and the agent too if it's still at work, since what it's writing answers what came before; what they
     * said is the next turn, or "stop" ends the chat.
     */
    private suspend fun heardOver(session: ChatSession, gateway: GatewayUrl, profile: String?, reply: Deferred<*>, over: Deferred<Recording?>): Turn? {
        val overVoice = voiced
        // Held even when nothing plays, so a voice due to start waits; but only a voice that was sounding has an echo
        // that stops with it. Over the agent at work, what they said tells it.
        val held = player.hold(true) && playing.value
        val phase = _chat.value.phase
        _chat.update { it.copy(phase = VoicePhase.Listening, level = 0f, hearing = true) }
        try {
            val heard = over.await()
            if (held && heard?.heardSpeech != true) {
                // Nobody went on once it held: its own echo, or a knock. It goes on at once. What was heard is still
                // written down meanwhile, since a quick "stop" can be over before the voice held.
                val chat = chatJob
                if (heard != null) scope.launch {
                    val words = (audio.transcribe(gateway, heard.bytes, heard.mimeType, profile) as? ApiResult.Success)?.value.orEmpty()
                    if (isVoiceStopCommand(words) && chat != null && chatJob === chat) stopChat()
                }
                _chat.update { it.copy(phase = phase, level = 0f, hearing = false) }
                return null
            }
            val transcript = heard?.let {
                _chat.update { it.copy(phase = VoicePhase.Transcribing, level = 0f, hearing = false) }
                (audio.transcribe(gateway, it.bytes, it.mimeType, profile) as? ApiResult.Success)?.value?.trim()
            }.orEmpty()
            // Its own words heard back, compared with every stretch of the reply: off the main thread.
            suspend fun echo(words: String) = withContext(Dispatchers.Default) { isTtsEcho(words, overVoice) }
            // "Stop" said over the voice can come after some of its own words, and be over before it held.
            val stop = isVoiceStopCommand(transcript) ||
                isVoiceStopCommand(transcript.substringAfterLast(' ')) && echo(transcript.substringBeforeLast(' '))
            // A voice that held stops its echo with it, so someone went on talking; one that couldn't hold is told by
            // its words.
            val someone = !stop && transcript.isNotBlank() && (held || !echo(transcript))
            if (!stop && !someone) {
                _chat.update { it.copy(phase = phase, level = 0f, hearing = false) }
                return null
            }
            reply.cancelAndJoin()
            playing.value = false
            _chat.update { it.copy(working = null) }
            // A cut-in's prompt stops the turn itself (stopAndSubmit), in order: a second stop sent alongside could land
            // after it and stop the new turn, or drop the prompt where it was queued behind the old one.
            if (stop && session.state.value.running) appScope.launch { session.interrupt() }
            return if (stop) Turn.End else Turn.CutIn(transcript)
        } finally {
            // Ended mid-hold (the chat stopped): the voice stops first, so letting go of it doesn't play a moment more.
            if (!currentCoroutineContext().isActive) withContext(NonCancellable) { reply.cancelAndJoin() }
            player.hold(false)
        }
    }

    /**
     * Waits for the turn running in [session] to end, answering aloud what it asks on the way, then reads its reply
     * (from message [from] on). [streamed]: the reply was voiced as it was written, up to where the turn stopped to
     * ask, so only what came after is read.
     */
    private suspend fun finishTurn(
        session: ChatSession,
        from: Int,
        gateway: GatewayUrl,
        profile: String?,
        activity: VoiceActivity,
        streamed: Boolean,
    ): Turn {
        val pointedAt = mutableSetOf<String>()
        var stopped = false
        while (true) {
            // The microphone listens over the agent at work, but not while it asks: that takes the microphone itself. Nor
            // while the turn waits on something said to be answered elsewhere ("unlock the phone"): what's said then
            // is about that, and a cut-in would stop the turn that's waiting.
            val chat = if (session.state.value.inputRequests.isNotEmpty()) {
                nextStop(session, pointedAt)
            } else {
                when (val cut = overVoice(session, gateway, profile, activity) { nextStop(session, pointedAt) }) {
                    is Cut.Over -> return cut.turn
                    is Cut.Done -> cut.value
                }
            }
            val request = askable(chat)
            if (request != null) {
                stopped = true
                if (!askAloud(session, request, gateway, profile, activity)) return Turn.End
                _chat.update { it.copy(phase = VoicePhase.Thinking) }
                continue
            }
            // A password or a code is never said aloud, nor an approval taken from a locked phone: the turn waits,
            // and the voice says so once.
            val typed = chat.inputRequests.firstOrNull { it.id !in pointedAt }
            if (typed != null) {
                stopped = true
                pointedAt += typed.id
                val said = if (typed is InputRequest.Approval) {
                    "Hermes needs your approval. Unlock the phone, and I'll ask you for it."
                } else {
                    "Hermes needs something typed in the chat to go on."
                }
                _chat.update { it.copy(phase = VoicePhase.Speaking, caption = said, captionIsVoice = true) }
                speak(said, gateway, profile)
                _chat.update { it.copy(phase = VoicePhase.Thinking) }
                continue
            }
            if (!chat.running && chat.inputRequests.none(VoiceAnswers::canAsk)) break
        }
        val messages = session.state.value.messages
        val text = when {
            // All of it was voiced as it was written.
            streamed && !stopped && !voicedUntilAsked -> ""
            // Voiced up to where it stopped to ask: the rest of it, which the one-shot voice reads as plain text.
            streamed -> speakableText(unsentSpeech(voicedSoFar, speakableSoFar(replyText(messages, from), complete = true)))
            else -> (messages.drop(from).lastOrNull { it is ChatMessage.Assistant } as? ChatMessage.Assistant)?.text?.let(::speakableText).orEmpty()
        }.trim()
        if (text.isEmpty()) return Turn.Done

        // An error left from asking (an unclear last try, answered elsewhere after) isn't this reading's.
        _chat.update { it.copy(phase = VoicePhase.Speaking, caption = text, captionIsVoice = true, error = null) }
        val cut = overVoice(session, gateway, profile, activity) { speak(text, gateway, profile) }
        if (cut is Cut.Over) return cut.turn
        // The voice couldn't be made or played (a provider's 429, say).
        return if (_chat.value.error == null) Turn.Done else Turn.Failed
    }

    /**
     * Waits until the turn running in [session] needs the voice: it asks something, waits on something not yet
     * [pointedAt], or ends. Looked at again now and then too: an approval held back while the phone was locked can
     * be asked once it isn't.
     */
    private suspend fun nextStop(session: ChatSession, pointedAt: Set<String>): ChatState {
        while (true) {
            val chat = withTimeoutOrNull(LOCK_CHECK_MS) {
                session.state.first { !it.running || askable(it) != null || it.inputRequests.any { r -> r.id !in pointedAt } }
            } ?: session.state.value
            val waiting = askable(chat) != null || chat.inputRequests.any { it.id !in pointedAt }
            if (waiting || !chat.running && chat.inputRequests.none(VoiceAnswers::canAsk)) return chat
        }
    }

    /** What's said next, as [Heard] tells. An error on show stays through silence, and goes once words come. */
    private suspend fun hear(gateway: GatewayUrl, profile: String?, activity: VoiceActivity): Heard {
        _chat.update { it.copy(phase = VoicePhase.Listening, level = 0f, hearing = false) }
        val recording = try {
            recorder.record(
                activity,
                onLevel = { level -> _chat.update { it.copy(level = level) } },
                onSpeech = { _chat.update { it.copy(hearing = true) } },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _chat.update { it.copy(error = e.message ?: "Couldn't use the microphone.") }
            return Heard.Broken
        }
        if (!recording.heardSpeech) return Heard.Silence
        _chat.update { it.copy(phase = VoicePhase.Transcribing, level = 0f, hearing = false) }
        val transcript = when (val result = audio.transcribe(gateway, recording.bytes, recording.mimeType, profile)) {
            is ApiResult.Success -> result.value
            else -> {
                _chat.update { it.copy(error = result.errorMessage ?: "Couldn't transcribe that.") }
                return Heard.Unclear
            }
        }
        // From here, an error on show is this turn's own.
        _chat.update { it.copy(error = null) }
        return if (transcript.isBlank()) Heard.Silence else Heard.Words(transcript)
    }

    /** The question or approval Hermes waits on in [chat] that can be answered aloud. */
    private fun askable(chat: ChatState): InputRequest? = chat.inputRequests.firstOrNull {
        VoiceAnswers.canAsk(it) && (it !is InputRequest.Approval || !deviceLock.locked())
    }

    /**
     * Reads [request] aloud and sends what's said back as its answer, asking again when it isn't clear. False
     * when the chat should end: "stop" said, no answer after a few tries (it stays on the screen), or it
     * couldn't be sent.
     */
    private suspend fun askAloud(
        session: ChatSession,
        request: InputRequest,
        gateway: GatewayUrl,
        profile: String?,
        activity: VoiceActivity,
    ): Boolean {
        val answer = when (request) {
            is InputRequest.Approval -> {
                val choice = askUntilClear(session, request, VoiceAnswers.approvalPrompt(request), gateway, profile, activity) {
                    VoiceAnswers.approval(request, it)
                } ?: return answeredElsewhere(session, request)
                InputAnswers.approval(choice)
            }
            is InputRequest.Clarify -> {
                val answers = request.questions.map { question ->
                    askUntilClear(session, request, VoiceAnswers.questionPrompt(question), gateway, profile, activity) {
                        VoiceAnswers.clarify(question, it).takeIf { picked -> picked.isNotEmpty() }
                    } ?: return answeredElsewhere(session, request)
                }
                InputAnswers.clarify(request, answers)
            }
            else -> return true
        }
        // Answered on the screen while it was being said: the gateway takes the first answer only.
        if (answeredElsewhere(session, request)) return true
        // The phone locked while it was asked: whoever is near it doesn't get to approve. It's asked again unlocked.
        if (request is InputRequest.Approval && deviceLock.locked()) return true
        if (!session.answer(request, answer)) {
            _chat.update { it.copy(error = session.state.value.error ?: "Couldn't send the answer.") }
            return false
        }
        return true
    }

    /** True once [request] no longer waits: answered on the screen or another device, or withdrawn. */
    private fun answeredElsewhere(session: ChatSession, request: InputRequest): Boolean =
        session.state.value.inputRequests.none { it.id == request.id }

    /**
     * Says [prompt] and listens until [heard] makes something of the answer; null after a few unclear answers or
     * failures, or "stop". Silence isn't an answer: it listens on, as someone thinking it over gets to.
     */
    private suspend fun <T : Any> askUntilClear(
        session: ChatSession,
        request: InputRequest,
        prompt: String,
        gateway: GatewayUrl,
        profile: String?,
        activity: VoiceActivity,
        heard: (String) -> T?,
    ): T? {
        var tries = 0
        var say = true
        while (tries < MAX_ASKS) {
            if (answeredElsewhere(session, request)) return null
            if (say) {
                val said = if (tries == 0) prompt else "Sorry, I didn't get that. $prompt"
                _chat.update { it.copy(phase = VoicePhase.Speaking, caption = said, captionIsVoice = true) }
                speak(said, gateway, profile)
                if (answeredElsewhere(session, request)) return null
            }
            val transcript = when (val result = hear(gateway, profile, activity)) {
                Heard.Broken -> return null
                // Nothing said listens on; a recording that couldn't be written down counts as a try.
                Heard.Silence -> {
                    say = false
                    continue
                }
                Heard.Unclear -> {
                    say = false
                    tries++
                    continue
                }
                is Heard.Words -> result.text
            }
            if (isVoiceStopCommand(transcript)) return null
            _chat.update { it.copy(caption = transcript.trim(), captionIsVoice = false) }
            heard(transcript)?.let { return it }
            tries++
            say = true
        }
        // The chat ends here, so it says why, and where the question went.
        val said = "I couldn't get that, so it's waiting in the chat."
        _chat.update { it.copy(phase = VoicePhase.Speaking, caption = said, captionIsVoice = true) }
        speak(said, gateway, profile)
        return null
    }

    /**
     * Voices the reply while the agent is still writing it (`/api/audio/speak-stream`): its text goes to the
     * gateway as it streams in, and the audio plays as it comes back, so the first sentence is heard long
     * before the reply is finished. [Voiced.No] when the gateway can't (an older one), made no audio at all,
     * or the voice broke off (the socket dropped, the player failed), so the finished reply is read out instead.
     */
    private suspend fun speakAsWritten(stream: SpeechStream?, session: ChatSession, sentAt: Int): Voiced {
        stream ?: return Voiced.No
        var ended = false
        var playFailed = false
        var skipped = false
        try {
            coroutineScope {
                // A child of the conversation, so ending the chat stops the voice; skipping cancels only this.
                val job = launch {
                    coroutineScope {
                        val audio = Channel<ByteArray>(Channel.UNLIMITED)
                        val feeding = launch { feedReply(stream, session, sentAt) }
                        var format: SpeechStreamEvent.Start? = null
                        var playing = false
                        // A player that failed hears nothing more; the finished reply is read out instead.
                        stream.events
                            // A closed socket can end its events with a cancellation of its own (OkHttp's, #183):
                            // the voice broke off, which isn't a skip. A real skip or the chat's end isn't caught.
                            .catch { }
                            .takeWhile { !playFailed }
                            .collect { event ->
                            when (event) {
                                is SpeechStreamEvent.Start -> format = event
                                is SpeechStreamEvent.Audio -> {
                                    val start = format ?: return@collect
                                    // The player starts with the first sound, not before: a reply with nothing to say plays nothing.
                                    if (!playing) {
                                        playing = true
                                        launch {
                                            if (!playStreamed(start, audio)) {
                                                playFailed = true
                                                // Nothing will take the rest: it isn't kept.
                                                audio.cancel()
                                            }
                                        }
                                    }
                                    audio.trySend(event.pcm)
                                }
                                SpeechStreamEvent.End -> ended = true
                                SpeechStreamEvent.Fallback -> Unit
                            }
                        }
                        audio.close()
                        // The socket is done: nothing more will be voiced.
                        feeding.cancel()
                    }
                }
                speechJob = job
                job.join()
                skipped = job.isCancelled
            }
        } finally {
            speechJob = null
            // Closing a socket the gateway already closed can throw (OkHttp's own cancellation, #183); it would
            // otherwise end the whole chat, silently, right after the reply.
            withContext(NonCancellable) { runCatching { stream.close() } }
        }
        return when {
            skipped -> Voiced.Skipped
            ended && !playFailed -> Voiced.Yes
            else -> Voiced.No
        }
    }

    /** The speech socket for one reply, unless this gateway has already shown it has none. */
    private suspend fun openSpeech(gateway: GatewayUrl, profile: String?): SpeechStream? {
        val streams = speechStreams?.takeIf { gateway != noSpeechStream } ?: return null
        return streams.open(gateway, profile).also { if (it == null) noSpeechStream = gateway }
    }

    /** Plays the voice as it streams in; false when the player failed. */
    private suspend fun playStreamed(format: SpeechStreamEvent.Start, audio: Channel<ByteArray>): Boolean = try {
        player.playPcm(format.sampleRate, format.channels, audio, ::onSound)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /**
     * Sends the reply's text to [stream] as it's written, and its end once the turn is over, or once it waits on
     * the user ([finishTurn] asks then, and reads what comes after); the captions follow it.
     */
    private suspend fun feedReply(stream: SpeechStream, session: ChatSession, sentAt: Int) {
        var sent = ""
        session.state
            .transformWhile {
                emit(it)
                // Answered elsewhere while the voice still plays, the turn goes on unseen: what follows is read after.
                if (it.inputRequests.isNotEmpty()) voicedUntilAsked = true
                it.running && it.inputRequests.isEmpty()
            }
            .map { replyText(it.messages, sentAt) to (!it.running || it.inputRequests.isNotEmpty()) }
            .distinctUntilChanged()
            // Behind a fast reply, only the latest of it matters.
            .conflate()
            .collect { (reply, complete) ->
                val now = speakableSoFar(reply, complete)
                val unsent = unsentSpeech(sent, now)
                if (unsent.isNotEmpty()) {
                    stream.append(unsent)
                    sent = now
                    voicedSoFar = now
                    voiced = now
                    _chat.update { it.copy(caption = speakableText(now), captionIsVoice = true) }
                }
            }
        stream.finish()
    }

    /** Sound coming out is Speaking; a pause in it, while the agent works between sentences, is Thinking again. */
    private fun onSound(sound: Boolean) {
        playing.value = sound
        _chat.update { if (it.phase == VoicePhase.Speaking || it.phase == VoicePhase.Thinking) it.copy(phase = if (sound) VoicePhase.Speaking else VoicePhase.Thinking) else it }
    }

    /** Reads [text] aloud in the voice chat; Skip cuts it. */
    private suspend fun speak(text: String, gateway: GatewayUrl, profile: String?) = coroutineScope {
        voiced = text
        // A child of the conversation, so ending the chat stops the voice; skipping cancels only this.
        val job = launch { voice(text, gateway, profile) { error -> _chat.update { it.copy(error = error) } } }
        speechJob = job
        job.join()
        speechJob = null
    }

    /**
     * Reads [text] aloud piece by piece, synthesizing the next piece while the current one plays. [onError] hears
     * what went wrong: a piece the gateway couldn't voice ends it, one the player couldn't play is passed over.
     * [keptAs]: the pieces already voiced under it in [readAloudVoices] play again as they are, and new ones are kept,
     * each by its own words.
     */
    private suspend fun voice(
        text: String,
        gateway: GatewayUrl,
        profile: String?,
        keptAs: String? = null,
        onError: (String) -> Unit,
    ) = coroutineScope {
        val clips = Channel<SpokenAudio>(capacity = 1)
        launch {
            for (chunk in speechChunks(text)) {
                val clip = keptAs?.let { readAloudVoices.piece(it, chunk) } ?: when (val result = audio.speak(gateway, chunk, profile)) {
                    is ApiResult.Success -> result.value.also { made -> keptAs?.let { readAloudVoices.keep(it, chunk, made) } }
                    else -> {
                        onError(result.errorMessage ?: "Couldn't voice the reply.")
                        break
                    }
                }
                clips.send(clip)
            }
            clips.close()
        }
        for (clip in clips) {
            try {
                playing.value = true
                player.play(clip)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Couldn't play the reply.")
            } finally {
                playing.value = false
            }
        }
    }

    /**
     * Reads reply [key] aloud with the gateway's voice, the way the voice chat reads a finished reply: [reply]
     * without its code, links, pictures and files. A reply already being read stops first. Not while a voice chat
     * or dictation runs: they have the speaker and the microphone.
     */
    fun readAloud(key: String, reply: String, gateway: GatewayUrl, profile: String?) {
        if (chatJob?.isActive == true || dictationJob?.isActive == true) return
        stopReading()
        val text = readAloudText(reply)
        if (text.isEmpty()) return
        _reading.value = ReadAloudState(key)
        val keptAs = "$gateway\n${profile.orEmpty()}\n$text"
        // Started once it's [readingJob], so its end can tell whether a newer one took over.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var error: String? = null
            try {
                // The first thing that went wrong says why; what follows from it says less.
                voice(text, gateway, profile, keptAs) { if (error == null) error = it }
            } finally {
                // Stopped, or another reply started: that one keeps its own state.
                if (readingJob === coroutineContext.job) {
                    readingJob = null
                    _reading.value = ReadAloudState(error = error)
                }
            }
        }
        readingJob = job
        job.start()
    }

    /** Stops the reply being read aloud, and clears why the last one couldn't be. */
    fun stopReading() {
        readingJob?.cancel()
        readingJob = null
        _reading.value = ReadAloudState()
    }

    fun dismissReadAloudError() = _reading.update { it.copy(error = null) }

    /**
     * Starts dictating; the transcript goes to [onText]. Call [finishDictation] to stop early. With
     * [DictationEngine.Device] and a recognizer on this device, the phone writes it down and [onPartial]
     * gets the words so far as they are said; otherwise the gateway transcribes the recording.
     * [onCancelled] runs when the dictation is cancelled rather than finished, so partial words can go.
     */
    fun startDictation(
        gateway: GatewayUrl,
        profile: String?,
        engine: DictationEngine = DictationEngine.Gateway,
        onPartial: (String) -> Unit = {},
        onCancelled: () -> Unit = {},
        onText: (String) -> Unit,
    ) {
        if (dictationJob?.isActive == true || chatJob?.isActive == true) return
        // The microphone would hear it.
        stopReading()
        _dictation.value = DictationState(recording = true)
        val device = deviceDictation?.takeIf { engine == DictationEngine.Device && it.available() }
        dictating = device
        dictationFinished = false
        dictationJob = scope.launch {
            try {
                if (device != null) {
                    val text = try {
                        device.listen(
                            onPartial = onPartial,
                            onLevel = { level -> _dictation.update { it.copy(level = level) } },
                        ).trim()
                    } catch (_: DeviceDictationUnavailable) {
                        // No recognizer here could start (a missing language pack, say): the gateway writes it down.
                        dictating = null
                        null
                    }
                    if (text != null) {
                        _dictation.value = DictationState(error = "Didn't catch anything.".takeIf { text.isEmpty() })
                        if (text.isNotEmpty()) onText(text)
                        return@launch
                    }
                    // Stopped while the recognizers were being tried: nothing to record.
                    if (dictationFinished) {
                        _dictation.value = DictationState(error = "Didn't catch anything.")
                        return@launch
                    }
                }
                // Pauses to think are fine here; a long quiet spell still ends it.
                val recording = recorder.record(
                    VoiceActivity(silenceMs = DICTATION_SILENCE_MS, idleMs = DICTATION_IDLE_MS, maxMs = DICTATION_MAX_MS),
                    onLevel = { level -> _dictation.update { it.copy(level = level) } },
                )
                if (!recording.heardSpeech) {
                    _dictation.value = DictationState(error = "Didn't catch anything.")
                    return@launch
                }
                _dictation.value = DictationState(transcribing = true)
                when (val result = audio.transcribe(gateway, recording.bytes, recording.mimeType, profile)) {
                    is ApiResult.Success -> {
                        _dictation.value = DictationState(error = "Didn't catch anything.".takeIf { result.value.isBlank() })
                        if (result.value.isNotBlank()) onText(result.value)
                    }
                    else -> _dictation.value = DictationState(error = result.errorMessage ?: "Couldn't transcribe that.")
                }
            } catch (e: CancellationException) {
                _dictation.value = DictationState()
                onCancelled()
                throw e
            } catch (e: Exception) {
                _dictation.value = DictationState(error = e.message ?: "Couldn't use the microphone.")
            }
        }
    }

    fun finishDictation() {
        if (!_dictation.value.recording) return
        dictationFinished = true
        dictating?.finish() ?: recorder.finish()
    }

    fun cancelDictation() {
        dictationJob?.cancel()
        dictationJob = null
    }

    fun dismissDictationError() = _dictation.update { it.copy(error = null) }

    /** Everything off: the screen moved to another chat or the app went to the background. */
    fun stopAll() {
        stopChat()
        cancelDictation()
        stopReading()
    }

    private companion object {
        const val MAX_FAILURES = 3
        /** Times a question is asked before it's left for the screen. */
        const val MAX_ASKS = 3
        /** How often a waiting turn is looked at again, for an approval held back while the phone was locked. */
        const val LOCK_CHECK_MS = 1_000L
        const val RETRY_PAUSE_MS = 1_000L
        const val TURN_START_MS = 15_000L
        const val DICTATION_SILENCE_MS = 3_000L
        const val DICTATION_IDLE_MS = 15_000L
        const val DICTATION_MAX_MS = 120_000L
    }
}

/** Whether a reply was voiced as it was written. */
private enum class Voiced { Yes, No, Skipped }

/** What one listen heard. */
private sealed interface Heard {
    data class Words(val text: String) : Heard

    /** Nobody spoke, or nothing that could be written down. */
    data object Silence : Heard

    /** It couldn't be written down; the error on show says why. */
    data object Unclear : Heard

    /** The microphone can't be used: the chat can't go on. */
    data object Broken : Heard
}

/**
 * How a turn of the chained voice chat went. Only [Failed] turns count towards giving up: a listen that heard
 * nobody leaves the count as it was, so one error and then a quiet room doesn't end the chat (#184).
 */
private sealed interface Turn {
    /** Said, sent and answered. */
    data object Done : Turn
    /** Nobody spoke, or nothing that could be written down. */
    data object Quiet : Turn
    /** Transcribing, sending or voicing the reply failed. */
    data object Failed : Turn
    /** The chat ends: "stop" was said, or the microphone can't be used. */
    data object End : Turn
    /** Someone talked over the reply; what they said, [transcript], is the next turn. */
    class CutIn(val transcript: String) : Turn
}

/** How a stretch with the microphone open over it went: it came to [Done.value], or someone talked [Over] it. */
private sealed interface Cut<out T> {
    class Done<T>(val value: T) : Cut<T>
    class Over(val turn: Turn) : Cut<Nothing>
}

/**
 * Everything the agent has written since the prompt at [sentAt], its messages a paragraph apart. Empty ones
 * add nothing, so one that comes or goes (a turn with no words) doesn't shift what's after it.
 */
internal fun replyText(messages: List<ChatMessage>, sentAt: Int): String =
    messages.drop(sentAt).filterIsInstance<ChatMessage.Assistant>().filter { it.text.isNotBlank() }.joinToString("\n\n") { it.text }

/**
 * What of the speakable reply [now] hasn't been sent yet, after [sent]: what was added to it. Where the reply
 * was rewritten under what was sent (the transcript read again), the words from where the two part; what was
 * already said can't be taken back.
 */
internal fun unsentSpeech(sent: String, now: String): String {
    if (now.startsWith(sent)) return now.substring(sent.length)
    if (now.length <= sent.length) return ""
    val common = now.commonPrefixWith(sent)
    return now.substring(common.indexOfLast { it.isWhitespace() } + 1)
}

/**
 * Why a live call couldn't start, in a line: the vendor's own message when the gateway passed one on (no
 * credits, a bad key), rather than the raw JSON under "Can't reach the gateway".
 */
internal fun liveFailure(result: ApiResult<*>): String {
    val raw = when (result) {
        is ApiResult.Unavailable -> result.message
        is ApiResult.Failed -> result.message
        else -> null
    }
    val vendor = raw?.let { VENDOR_MESSAGE.find(it)?.groupValues?.get(1) }
    return when {
        vendor != null -> "GPT-Live couldn't start: $vendor"
        else -> result.errorMessage ?: "Couldn't start the live call."
    }
}

private val VENDOR_MESSAGE = Regex(""""message"\s*:\s*"((?:[^"\\]|\\.)*)"""")

/**
 * A voice error as a banner shows it: its first line that says something, without the provider's request URL
 * or any address's query, fragment or sign-in. A speech provider's failure can come through whole,
 * `url='wss://…?TrustedClientToken=…'` and all, which fills the screen and puts tokens on it.
 */
internal fun briefError(message: String): String {
    val brief = message.lineSequence().map { briefLine(it.take(LINE_MAX)) }.firstOrNull { it.isNotEmpty() }
        ?: return VOICE_FAILED
    if (brief.length <= BRIEF_MAX) return brief
    // Not between the halves of an emoji.
    val cut = if (brief[BRIEF_MAX - 2].isHighSurrogate()) BRIEF_MAX - 2 else BRIEF_MAX - 1
    return brief.take(cut).trimEnd() + "…"
}

private fun briefLine(line: String): String = line.replace(URL_ARGUMENT, "")
    .replace(WORD) { bareAddress(it.value) }
    .replace(SPACES, " ").trim().trimEnd(',', ';', ':').trimEnd()

/**
 * [word] with each address in it cut to the scheme, host and path: the query, fragment and sign-in are where
 * keys and tokens ride, and a query takes the rest of the word with it. Without an address ahead of it, as in
 * `GET /v1/speech?key=…`, a query goes when it sets something; a plain question mark stays. The punctuation that
 * closes a sentence or a bracket stays too.
 */
private fun bareAddress(word: String): String {
    val body = word.trimEnd { it in CLOSERS }
    val tail = word.substring(body.length)
    val schemes = SCHEME.findAll(body).toList()
    val lead = body.take(schemes.firstOrNull()?.range?.first ?: body.length)
    val leadQuery = lead.indexOfFirst(::opensQuery)
    if (leadQuery >= 0) {
        val after = body.substring(leadQuery)
        return if ('=' in after || "://" in after) lead.take(leadQuery) + tail else word
    }
    val brief = StringBuilder(lead)
    for ((i, scheme) in schemes.withIndex()) {
        brief.append(scheme.value)
        val address = signedOut(body.substring(scheme.range.last + 1, schemes.getOrNull(i + 1)?.range?.first ?: body.length))
        val query = address.indexOfFirst(::opensQuery)
        if (query >= 0) return brief.append(address, 0, query).append(tail).toString()
        brief.append(address)
    }
    return brief.append(tail).toString()
}

/**
 * [address] (what follows its scheme) without its sign-in. A password can hold `/`, `@`, `?` or `#`, so with a
 * `user:` in front everything up to the last `@` goes; better a wrong host than a password. Digits after the
 * `:` are a port instead when the only `@`s are in the query, as in `host:8080/x?email=a@b`.
 */
private fun signedOut(address: String): String {
    val authority = address.substringBefore('/')
    val query = address.indexOfFirst(::opensQuery).let { if (it < 0) address.length else it }
    // A port follows a host (a name with a dot, an IP, localhost), not a user: `user:12/34?x@host` is a password.
    val host = authority.substringBeforeLast(':')
    val port = authority.substringAfterLast(':', "").let { it.isNotEmpty() && it.all(Char::isDigit) } &&
        ('.' in host || host.startsWith('[') || host.equals("localhost", ignoreCase = true))
    return when {
        ':' in authority && '@' in address && !(port && address.indexOf('@') > query) -> address.substringAfterLast('@')
        '@' in authority -> address.substring(authority.lastIndexOf('@') + 1)
        else -> address
    }
}

private fun opensQuery(c: Char) = c == '?' || c == '#'

/**
 * Python's `url='…'`, `url=URL('…')` or `url=…` (also `base_url=` and the like), as aiohttp, yarl and httpx put
 * it in their errors; not "url = " in prose. A quoted one runs on past a quote inside it, to the last before a
 * space; an unquoted one runs to the next space.
 */
private val URL_ARGUMENT = Regex(
    """[,;]?\s*\b(?:\w+_)?url\s*=\s*(?:URL\()?(?:'[^']*'(?:[^\s']*')*|"[^"]*"(?:[^\s"]*")*)\)?""" +
        """|[,;]?\s*\b(?:\w+_)?url=\S+""",
    RegexOption.IGNORE_CASE,
)
private val WORD = Regex("""\S+""")
/** No word boundary: a scheme glued on, as in `x_https://`, is still one. */
private val SCHEME = Regex("""[a-z][a-z0-9+.-]*://""", RegexOption.IGNORE_CASE)
private const val CLOSERS = ").,;:'\"]>}"
private val SPACES = Regex("""\s{2,}""")
/** Past this, a line is long enough to cut before it's searched. */
private const val LINE_MAX = 2_000
private const val BRIEF_MAX = 200
private const val VOICE_FAILED = "The voice service failed."
