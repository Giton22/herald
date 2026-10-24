package dev.hermeskotlin.ui.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.phone.AgentCall
import dev.hermeskotlin.core.phone.CallOutcome
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.DeviceLock
import dev.hermeskotlin.core.voice.LiveCalls
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import dev.hermeskotlin.core.voice.VoiceRecorder
import dev.hermeskotlin.core.wear.CallChat
import dev.hermeskotlin.core.wear.WatchRelay
import dev.hermeskotlin.ui.voice.VoiceChatState
import dev.hermeskotlin.ui.voice.VoiceController
import dev.hermeskotlin.ui.voice.VoicePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PhoneCallState(
    /** Who's on the line: the chat that called. */
    val caller: String,
    val voice: VoiceChatState = VoiceChatState(VoicePhase.Connecting),
    /** The call is over: hung up, "stop" said, or it couldn't go on ([error] says why). */
    val ended: Boolean = false,
    val error: String? = null,
    /** Over before it went on the line (another call on, the gateway out of reach): its question wasn't asked. */
    val notAsked: PickedUpCall? = null,
)

/**
 * A call being picked up, by its ring's [id]: the chat that rang, and the request it rang for; or a call the agent
 * placed ([agent]), in its chat when this gateway has it, else a new one.
 */
data class PickedUpCall(
    val id: String,
    val storedSessionId: String?,
    val caller: String,
    val request: InputRequest?,
    val agent: AgentCall? = null,
)

/**
 * A call for an answer, picked up: Hermes on the line in the chat that rang, without the chat on screen. It's
 * the chat's voice chat (GPT-Live when the profile picked it), held the way a call from the watch is
 * ([WatchRelay.chatForCall]), so it asks its question aloud, takes the spoken answer, and talks on until hung up.
 * An unanswered question goes back to the usual notification by itself once the call is out of sight.
 */
class PhoneCallModel(
    private val relay: WatchRelay,
    private val settings: SettingsStore,
    audio: AudioApi,
    recorder: VoiceRecorder,
    player: SpeechPlayer,
    appScope: CoroutineScope,
    keepAlive: VoiceKeepAlive,
    liveCalls: LiveCalls,
    deviceLock: DeviceLock,
) : ViewModel() {
    val voice = VoiceController(audio, recorder, player, viewModelScope, appScope, keepAlive, liveCalls = liveCalls, deviceLock = deviceLock)

    private val _state = MutableStateFlow<PhoneCallState?>(null)
    val state: StateFlow<PhoneCallState?> = _state.asStateFlow()

    /** Picked up, while the phone unlocks and the microphone is allowed: kept here, so a rotation meanwhile keeps it. */
    var pickingUp: PickedUpCall? = null
        private set

    private var counted = false
    private var cleared = false

    /** The agent's call being put on the line; until it is, closing the screen ends it as declined. */
    private var placing: AgentCall? = null

    fun pickUp(call: PickedUpCall) {
        if (_state.value == null) pickingUp = call
    }

    /** The call picked up goes on the line. Once; a second start is ignored. */
    fun start() {
        val call = pickingUp ?: return
        if (_state.value != null || cleared) return
        pickingUp = null
        placing = call.agent
        _state.value = PhoneCallState(call.caller)
        // Counted from here, so the gateway link stays up and the chat's turns aren't notified over the call.
        relay.callStarted()
        counted = true
        call.storedSessionId?.let(relay::callChat)
        viewModelScope.launch {
            val chat = when (val found = relay.chatForCall(call.storedSessionId)) {
                is CallChat.Failed -> {
                    _state.update { it?.copy(notAsked = call) }
                    call.agent?.end(CallOutcome.Declined)
                    return@launch end(found.message)
                }
                is CallChat.Ready -> found
            }
            if (_state.value?.ended == true) {
                call.agent?.end(CallOutcome.Declined)
                return@launch
            }
            // On the line: the agent hears it was answered, and the voice opens with why it called.
            call.agent?.end(CallOutcome.Answered)
            val pause = (settings.settings.value ?: AppSettings()).voicePause
            voice.startChat(chat.session, chat.gateway, chat.profile, pause.millis, opener = call.agent?.reason)
            // A new chat gets its id with the first request: it's held from then on.
            if (call.storedSessionId == null) {
                launch { relay.callChat(chat.session.state.mapNotNull { it.storedSessionId }.first()) }
            }
            launch { voice.chat.collect { v -> _state.update { it?.copy(voice = v) } } }
            voice.chat.first { it.phase == VoicePhase.Off }
            end(voice.chat.value.error)
        }
    }

    fun toggleMute() = voice.toggleMute()

    fun hangUp() {
        voice.stopChat()
        end(null)
    }

    private fun end(error: String?) = _state.update { it?.copy(ended = true, error = it.error ?: error) }

    override fun onCleared() {
        cleared = true
        // Picked up but never on the line (swiped away while unlocking or connecting): the agent hears it now,
        // not when its time runs out. One already answered stays answered.
        pickingUp?.agent?.end(CallOutcome.Declined)
        placing?.end(CallOutcome.Declined)
        voice.stopAll()
        if (counted) relay.callEnded()
    }
}
