package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.InputAnswers
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.settings.DictationEngine
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.DeviceDictation
import dev.hermeskotlin.core.voice.DeviceDictationUnavailable
import dev.hermeskotlin.core.voice.LiveCalls
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceAnswers
import dev.hermeskotlin.core.voice.DeviceLock
import dev.hermeskotlin.core.voice.VoiceRecorder
import dev.hermeskotlin.core.voice.isVoiceStopCommand
import dev.hermeskotlin.core.voice.speakableText
import dev.hermeskotlin.core.voice.speechChunks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
)

data class DictationState(
    val recording: Boolean = false,
    val transcribing: Boolean = false,
    val level: Float = 0f,
    val error: String? = null,
) {
    val active: Boolean get() = recording || transcribing
}

/**
 * Voice on this device, Desktop's way: the phone records, the gateway transcribes with the profile's
 * speech-to-text and voices replies with its text-to-speech, and the phone plays them.
 *
 * Dictation drops a transcript into the composer. A voice chat loops: listen until you stop talking,
 * send what you said, wait for the reply, read it aloud, listen again; saying "stop" ends it.
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
    /** An approval is only taken by voice while the phone is unlocked. */
    private val deviceLock: DeviceLock = DeviceLock.None,
    private val liveCalls: LiveCalls? = null,
) {
    private val _chat = MutableStateFlow(VoiceChatState())
    val chat: StateFlow<VoiceChatState> = _chat.asStateFlow()

    private val _dictation = MutableStateFlow(DictationState())
    val dictation: StateFlow<DictationState> = _dictation.asStateFlow()

    private var chatJob: Job? = null
    private var stoppedByUser = false
    private var speechJob: Job? = null
    private var dictationJob: Job? = null
    /** The recognizer the dictation in progress listens with; null when the gateway transcribes it. */
    private var dictating: DeviceDictation? = null
    /** The dictation in progress was asked to finish; a gateway fallback doesn't start recording then. */
    private var dictationFinished = false
    private var live: LiveConversation? = null
    private var held = false
    /** Herald went out of sight since the chat started. */
    private var away = false

    /** [pauseMs] is how long a quiet spell after speech has to last before it's sent. */
    fun startChat(session: ChatSession, gateway: GatewayUrl, profile: String?, pauseMs: Long = VoiceActivity().silenceMs) {
        if (chatJob?.isActive == true) return
        cancelDictation()
        _chat.value = VoiceChatState(VoicePhase.Listening)
        stoppedByUser = false
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
                if (startLive(session, gateway, profile)) return@launch
                converseChained(session, gateway, profile, pauseMs)
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
    private suspend fun converseChained(session: ChatSession, gateway: GatewayUrl, profile: String?, pauseMs: Long) {
        appScope.launch { audio.ttsLease(gateway, active = true, profile) }
        var failures = 0
        try {
            while (currentCoroutineContext().isActive) {
                if (!converseOnce(session, gateway, profile, VoiceActivity(silenceMs = pauseMs))) break
                failures = if (_chat.value.error == null) 0 else failures + 1
                // A provider that keeps failing would loop forever; give up after a few in a row.
                if (failures >= MAX_FAILURES) break
                if (failures > 0) delay(RETRY_PAUSE_MS)
            }
        } finally {
            appScope.launch { audio.ttsLease(gateway, active = false, profile) }
        }
    }

    /**
     * Herald went out of sight. Dictation stops; a voice chat carries on when it's held up, like a call,
     * and stops otherwise, since Android doesn't let the app listen from the background.
     */
    fun onBackground() {
        away = true
        cancelDictation()
        if (!held) stopChat()
    }

    /**
     * Runs a GPT-Live call when the profile picked it and it can start; false to fall back to the chained chat
     * (another mode, no OpenAI key on the gateway, an older gateway, or no WebRTC on this device).
     */
    private suspend fun startLive(session: ChatSession, gateway: GatewayUrl, profile: String?): Boolean {
        val calls = liveCalls ?: return false
        val status = audio.voiceLiveStatus(gateway, profile)
        if (status !is ApiResult.Success || !status.value.live) return false
        val call = calls.create() ?: return false
        _chat.value = VoiceChatState(VoicePhase.Connecting, live = true)
        val conversation = LiveConversation(call, session, _chat, deviceLock)
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

    fun dismissChatError() = _chat.update { it.copy(error = null) }

    /** One turn of the conversation; false when it should end. */
    private suspend fun converseOnce(session: ChatSession, gateway: GatewayUrl, profile: String?, activity: VoiceActivity): Boolean {
        // Hermes already waits on an answer (the chat opened from a call for one): that comes first, then the rest of
        // its turn, whose reply follows the last prompt.
        if (session.state.value.inputRequests.any(VoiceAnswers::canAsk)) {
            val from = session.state.value.messages.indexOfLast { it is ChatMessage.User } + 1
            return finishTurn(session, from, gateway, profile, activity)
        }
        val transcript = hear(gateway, profile, activity) ?: return false
        if (transcript.isBlank()) return true
        if (isVoiceStopCommand(transcript)) return false

        _chat.update { it.copy(phase = VoicePhase.Thinking, caption = transcript.trim(), captionIsVoice = false) }
        val sentAt = session.state.value.messages.size
        if (!session.send(transcript)) {
            _chat.update { it.copy(error = session.state.value.error ?: "Couldn't send that.") }
            return true
        }
        // The turn starts once the gateway takes it.
        withTimeoutOrNull(TURN_START_MS) { session.state.first { it.running } }
        return finishTurn(session, sentAt, gateway, profile, activity)
    }

    /**
     * Waits for the turn running in [session] to end, answering aloud what it asks on the way, then reads its reply
     * (the last one from message [from] on). False when the chat should end.
     */
    private suspend fun finishTurn(session: ChatSession, from: Int, gateway: GatewayUrl, profile: String?, activity: VoiceActivity): Boolean {
        val pointedAt = mutableSetOf<String>()
        while (true) {
            // Looked at again now and then too: an approval held back while the phone was locked can be asked once it isn't.
            val chat = withTimeoutOrNull(LOCK_CHECK_MS) {
                session.state.first { !it.running || askable(it) != null || it.inputRequests.any { r -> r.id !in pointedAt } }
            } ?: session.state.value
            val request = askable(chat)
            if (request != null) {
                if (!askAloud(session, request, gateway, profile, activity)) return false
                _chat.update { it.copy(phase = VoicePhase.Thinking) }
                continue
            }
            // A password or a code is never said aloud, nor an approval taken from a locked phone: the turn waits,
            // and the voice says so once.
            val typed = chat.inputRequests.firstOrNull { it.id !in pointedAt }
            if (typed != null) {
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
        val reply = session.state.value.messages.drop(from).lastOrNull { it is ChatMessage.Assistant } as? ChatMessage.Assistant
        val text = reply?.text?.let(::speakableText).orEmpty()
        if (text.isEmpty()) return true

        _chat.update { it.copy(phase = VoicePhase.Speaking, caption = text, captionIsVoice = true) }
        speak(text, gateway, profile)
        return true
    }

    /**
     * What's said next: blank when nothing was heard or it couldn't be written down (the error says which),
     * null when the microphone failed and the chat can't go on.
     */
    private suspend fun hear(gateway: GatewayUrl, profile: String?, activity: VoiceActivity): String? {
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
            return null
        }
        // Silence isn't a failure: an error from before doesn't stand for it.
        if (!recording.heardSpeech) return "".also { _chat.update { it.copy(error = null) } }
        _chat.update { it.copy(phase = VoicePhase.Transcribing, level = 0f, hearing = false) }
        return when (val result = audio.transcribe(gateway, recording.bytes, recording.mimeType, profile)) {
            is ApiResult.Success -> result.value.also { _chat.update { it.copy(error = null) } }
            else -> {
                _chat.update { it.copy(error = result.errorMessage ?: "Couldn't transcribe that.") }
                ""
            }
        }
    }

    /** The question or approval Hermes waits on in [session] that can be answered aloud. */
    private fun askable(session: ChatSession): InputRequest? = askable(session.state.value)

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
            val transcript = hear(gateway, profile, activity) ?: return null
            if (isVoiceStopCommand(transcript)) return null
            if (transcript.isBlank()) {
                // Nothing said listens on; a recording that couldn't be written down counts as a try.
                say = false
                if (_chat.value.error != null) tries++
                continue
            }
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

    /** Reads [text] aloud piece by piece, synthesizing the next piece while the current one plays. */
    private suspend fun speak(text: String, gateway: GatewayUrl, profile: String?) = coroutineScope {
        // A child of the conversation, so ending the chat stops the voice; skipping cancels only this.
        val job = launch {
            coroutineScope {
                val clips = Channel<SpokenAudio>(capacity = 1)
                launch {
                    for (chunk in speechChunks(text)) {
                        when (val result = audio.speak(gateway, chunk, profile)) {
                            is ApiResult.Success -> clips.send(result.value)
                            else -> {
                                _chat.update { it.copy(error = result.errorMessage ?: "Couldn't voice the reply.") }
                                break
                            }
                        }
                    }
                    clips.close()
                }
                for (clip in clips) {
                    try {
                        player.play(clip)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _chat.update { it.copy(error = e.message ?: "Couldn't play the reply.") }
                    }
                }
            }
        }
        speechJob = job
        job.join()
        speechJob = null
    }

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
