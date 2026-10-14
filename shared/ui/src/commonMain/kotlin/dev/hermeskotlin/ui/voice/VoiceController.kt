package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
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
import dev.hermeskotlin.core.voice.SpeechStream
import dev.hermeskotlin.core.voice.SpeechStreamEvent
import dev.hermeskotlin.core.voice.SpeechStreams
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceRecorder
import dev.hermeskotlin.core.voice.isVoiceStopCommand
import dev.hermeskotlin.core.voice.speakableText
import dev.hermeskotlin.core.voice.speechChunks
import dev.hermeskotlin.core.voice.speakableSoFar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
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
    /** Voices a reply while it's still being written; without it, a reply is read out once it's done. */
    private val speechStreams: SpeechStreams? = null,
    private val liveCalls: LiveCalls? = null,
) {
    private val _chat = MutableStateFlow(VoiceChatState())
    val chat: StateFlow<VoiceChatState> = _chat.asStateFlow()

    private val _dictation = MutableStateFlow(DictationState())
    val dictation: StateFlow<DictationState> = _dictation.asStateFlow()

    private var chatJob: Job? = null
    private var stoppedByUser = false
    private var speechJob: Job? = null
    /** The gateway whose speech socket couldn't open in this chat; its replies are read out once they're done. */
    private var noSpeechStream: GatewayUrl? = null
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
                when (converseOnce(session, gateway, profile, VoiceActivity(silenceMs = pauseMs))) {
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

    /** One turn of the conversation, and how it went. */
    private suspend fun converseOnce(session: ChatSession, gateway: GatewayUrl, profile: String?, activity: VoiceActivity): Turn {
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
            return Turn.End
        }
        if (!recording.heardSpeech) return Turn.Quiet
        _chat.update { it.copy(phase = VoicePhase.Transcribing, level = 0f, hearing = false) }
        val transcript = when (val result = audio.transcribe(gateway, recording.bytes, recording.mimeType, profile)) {
            is ApiResult.Success -> result.value
            else -> {
                _chat.update { it.copy(error = result.errorMessage ?: "Couldn't transcribe that.") }
                return Turn.Failed
            }
        }
        // From here, an error on show is this turn's own.
        _chat.update { it.copy(error = null) }
        if (transcript.isBlank()) return Turn.Quiet
        if (isVoiceStopCommand(transcript)) return Turn.End

        _chat.update { it.copy(phase = VoicePhase.Thinking, caption = transcript.trim(), captionIsVoice = false) }
        val sentAt = session.state.value.messages.size
        val voiced = coroutineScope {
            // The speech socket opens while the prompt goes out, so the first words don't wait for it.
            val opening = async { openSpeech(gateway, profile) }
            var handedOver = false
            try {
                if (!session.send(transcript)) {
                    _chat.update { it.copy(error = session.state.value.error ?: "Couldn't send that.") }
                    return@coroutineScope null
                }
                // The turn starts once the gateway takes it.
                withTimeoutOrNull(TURN_START_MS) { session.state.first { it.running } }
                val stream = opening.await()
                handedOver = true
                // Voiced while it's written where the gateway can; otherwise read out once it's done.
                speakAsWritten(stream, session, sentAt)
            } finally {
                // Not voiced after all (the prompt didn't go, or the chat ended first): the socket doesn't stay open.
                if (!handedOver) withContext(NonCancellable) {
                    opening.cancel()
                    runCatching { opening.await()?.close() }
                }
            }
        } ?: return Turn.Failed
        // Skipped while the agent is still at work: listen again now rather than once it's done.
        if (voiced == Voiced.Skipped) return Turn.Done
        session.state.first { !it.running }
        if (voiced == Voiced.Yes) return Turn.Done
        val reply = session.state.value.messages.drop(sentAt).lastOrNull { it is ChatMessage.Assistant } as? ChatMessage.Assistant
        val text = reply?.text?.let(::speakableText).orEmpty()
        if (text.isEmpty()) return Turn.Done

        _chat.update { it.copy(phase = VoicePhase.Speaking, caption = text, captionIsVoice = true) }
        speak(text, gateway, profile)
        // The voice couldn't be made or played (a provider's 429, say).
        return if (_chat.value.error == null) Turn.Done else Turn.Failed
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

    /** Sends the reply's text to [stream] as it's written, and its end once the turn is over; the captions follow it. */
    private suspend fun feedReply(stream: SpeechStream, session: ChatSession, sentAt: Int) {
        var sent = ""
        session.state
            .transformWhile { emit(it); it.running }
            .map { replyText(it.messages, sentAt) to !it.running }
            .distinctUntilChanged()
            // Behind a fast reply, only the latest of it matters.
            .conflate()
            .collect { (reply, complete) ->
                val now = speakableSoFar(reply, complete)
                val unsent = unsentSpeech(sent, now)
                if (unsent.isNotEmpty()) {
                    stream.append(unsent)
                    sent = now
                    _chat.update { it.copy(caption = speakableText(now), captionIsVoice = true) }
                }
            }
        stream.finish()
    }

    /** Sound coming out is Speaking; a pause in it, while the agent works between sentences, is Thinking again. */
    private fun onSound(sound: Boolean) = _chat.update {
        if (it.phase == VoicePhase.Speaking || it.phase == VoicePhase.Thinking) it.copy(phase = if (sound) VoicePhase.Speaking else VoicePhase.Thinking) else it
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
        const val RETRY_PAUSE_MS = 1_000L
        const val TURN_START_MS = 15_000L
        const val DICTATION_SILENCE_MS = 3_000L
        const val DICTATION_IDLE_MS = 15_000L
        const val DICTATION_MAX_MS = 120_000L
    }
}

/** Whether a reply was voiced as it was written. */
private enum class Voiced { Yes, No, Skipped }

/**
 * How a turn of the chained voice chat went. Only [Failed] turns count towards giving up: a listen that heard
 * nobody leaves the count as it was, so one error and then a quiet room doesn't end the chat (#184).
 */
private enum class Turn {
    /** Said, sent and answered. */
    Done,
    /** Nobody spoke, or nothing that could be written down. */
    Quiet,
    /** Transcribing, sending or voicing the reply failed. */
    Failed,
    /** The chat ends: "stop" was said, or the microphone can't be used. */
    End,
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
