package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.LiveCalls
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceRecorder
import dev.hermeskotlin.core.voice.isVoiceStopCommand
import dev.hermeskotlin.core.voice.speakableText
import dev.hermeskotlin.core.voice.speechChunks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
    private var live: LiveConversation? = null

    /** [pauseMs] is how long a quiet spell after speech has to last before it's sent. */
    fun startChat(session: ChatSession, gateway: GatewayUrl, profile: String?, pauseMs: Long = VoiceActivity().silenceMs) {
        if (chatJob?.isActive == true) return
        cancelDictation()
        _chat.value = VoiceChatState(VoicePhase.Listening)
        stoppedByUser = false
        chatJob = scope.launch {
            if (startLive(session, gateway, profile)) return@launch
            appScope.launch { audio.ttsLease(gateway, active = true, profile) }
            var failures = 0
            try {
                while (isActive) {
                    if (!converseOnce(session, gateway, profile, VoiceActivity(silenceMs = pauseMs))) break
                    failures = if (_chat.value.error == null) 0 else failures + 1
                    // A provider that keeps failing would loop forever; give up after a few in a row.
                    if (failures >= MAX_FAILURES) break
                    if (failures > 0) delay(RETRY_PAUSE_MS)
                }
            } finally {
                // An ending the person chose needs no explanation; one forced by an error keeps it.
                _chat.update { VoiceChatState(error = it.error.takeUnless { stoppedByUser }) }
                appScope.launch { audio.ttsLease(gateway, active = false, profile) }
            }
        }
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
        val conversation = LiveConversation(call, session, _chat)
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

    /**
     * Why the call couldn't start, in a line: the vendor's own message when the gateway passed one on (no
     * credits, a bad key), rather than the raw JSON under "Can't reach the gateway".
     */
    private fun liveFailure(result: ApiResult<*>): String {
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
            return false
        }
        if (!recording.heardSpeech) return true
        _chat.update { it.copy(phase = VoicePhase.Transcribing, level = 0f, hearing = false) }
        val transcript = when (val result = audio.transcribe(gateway, recording.bytes, recording.mimeType, profile)) {
            is ApiResult.Success -> result.value
            else -> {
                _chat.update { it.copy(error = result.errorMessage ?: "Couldn't transcribe that.") }
                return true
            }
        }
        _chat.update { it.copy(error = null) }
        if (transcript.isBlank()) return true
        if (isVoiceStopCommand(transcript)) return false

        _chat.update { it.copy(phase = VoicePhase.Thinking) }
        val sentAt = session.state.value.messages.size
        if (!session.send(transcript)) {
            _chat.update { it.copy(error = session.state.value.error ?: "Couldn't send that.") }
            return true
        }
        // The turn starts once the gateway takes it; then wait for it to end.
        withTimeoutOrNull(TURN_START_MS) { session.state.first { it.running } }
        session.state.first { !it.running }
        val reply = session.state.value.messages.drop(sentAt).lastOrNull { it is ChatMessage.Assistant } as? ChatMessage.Assistant
        val text = reply?.text?.let(::speakableText).orEmpty()
        if (text.isEmpty()) return true

        _chat.update { it.copy(phase = VoicePhase.Speaking) }
        speak(text, gateway, profile)
        return true
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

    /** Starts dictating; the transcript goes to [onText]. Call [finishDictation] to stop early. */
    fun startDictation(gateway: GatewayUrl, profile: String?, onText: (String) -> Unit) {
        if (dictationJob?.isActive == true || chatJob?.isActive == true) return
        _dictation.value = DictationState(recording = true)
        dictationJob = scope.launch {
            try {
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
                throw e
            } catch (e: Exception) {
                _dictation.value = DictationState(error = e.message ?: "Couldn't use the microphone.")
            }
        }
    }

    fun finishDictation() {
        if (_dictation.value.recording) recorder.finish()
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
        val VENDOR_MESSAGE = Regex(""""message"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        const val MAX_FAILURES = 3
        const val RETRY_PAUSE_MS = 1_000L
        const val TURN_START_MS = 15_000L
        const val DICTATION_SILENCE_MS = 3_000L
        const val DICTATION_IDLE_MS = 15_000L
        const val DICTATION_MAX_MS = 120_000L
    }
}
