package dev.hermeskotlin.wear

import android.content.Context
import android.os.SystemClock
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import dev.hermeskotlin.wearlink.CALL_EVENT_TYPES
import dev.hermeskotlin.wearlink.InOrder
import dev.hermeskotlin.wearlink.CallEnd
import dev.hermeskotlin.wearlink.CallEvents
import dev.hermeskotlin.wearlink.CallPhase
import dev.hermeskotlin.wearlink.CallStart
import dev.hermeskotlin.wearlink.CallUpdate
import dev.hermeskotlin.wearlink.ChatList
import dev.hermeskotlin.wearlink.PhoneStatus
import dev.hermeskotlin.wearlink.PhoneStatusRequest
import dev.hermeskotlin.wearlink.RecordedAsk
import dev.hermeskotlin.wearlink.ReplyPhase
import dev.hermeskotlin.wearlink.ReplySpeech
import dev.hermeskotlin.wearlink.ReplyUpdate
import dev.hermeskotlin.wearlink.RingEnd
import dev.hermeskotlin.wearlink.RingOn
import dev.hermeskotlin.wearlink.WatchRing
import dev.hermeskotlin.wearlink.WatchAsk
import dev.hermeskotlin.wearlink.WatchChat
import dev.hermeskotlin.wearlink.WatchStop
import dev.hermeskotlin.wearlink.WearCapabilities
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** One ask and where its turn stands: [asked] is what was typed or recognized here (null until the phone transcribes). */
data class WatchTurn(val askId: String, val asked: String?, val update: ReplyUpdate) {
    val running: Boolean get() = update.phase != ReplyPhase.Done && update.phase != ReplyPhase.Failed
    val said: String? get() = asked ?: update.heard
}

enum class CallStage {
    /** Opening the microphone and making the offer. */
    Starting,

    /** The phone is getting the voice model's answer, then the call connects. */
    Connecting,
    Live,
    Ended,
}

/**
 * A live voice call and where it stands: [caption] the latest words (the voice's when [captionIsVoice]), [thinking]
 * while Hermes works on a request ([working]: the tool), [speaking] while the voice talks, [level] your voice.
 */
data class WatchCallState(
    val id: String,
    val stage: CallStage,
    val caption: String? = null,
    val captionIsVoice: Boolean = false,
    val thinking: Boolean = false,
    val working: String? = null,
    val error: String? = null,
    val muted: Boolean = false,
    val speaking: Boolean = false,
    val level: Float = 0f,
    /** When it went live, on [android.os.SystemClock.elapsedRealtime], for the time on the call. */
    val liveSince: Long? = null,
) {
    val active: Boolean get() = stage != CallStage.Ended
}

/** How the phone stands, for what the watch can offer. */
enum class PhoneState {
    Unknown,

    /** No phone is connected to the watch. */
    NotConnected,

    /** A phone is connected, but without Herald (or with one too old to answer). */
    NoHerald,

    /** Herald is there, signed in to no gateway. */
    SignedOut,

    /** Herald is signed in, but not connected to its gateway right now; an ask waits for it a little. */
    Reconnecting,

    /** Herald gave up on its gateway (a setting or the server is wrong); asking won't help until that's fixed. */
    Unreachable,

    Ready,
}

/**
 * The watch's side of the companion: it sends what's asked to Herald on the phone, and turns what comes back into
 * [turn], [chats] and [phone]. It lives as long as the app, so updates that arrive with the screen off (through
 * [WatchListenerService]) are there when it's looked at again.
 *
 * In a debug build on a watch with no phone at all (an emulator not paired), [FakePhone] answers instead.
 */
class WatchLink(
    private val context: Context,
    private val store: WatchStore,
    private val speech: SpeechOut,
    private val scope: CoroutineScope,
    private val rings: WatchRings,
) {
    private val messages = Wearable.getMessageClient(context)
    private val channels = Wearable.getChannelClient(context)
    private val capabilities = Wearable.getCapabilityClient(context)
    private val nodes = Wearable.getNodeClient(context)
    private val fake = if (BuildConfig.DEBUG) FakePhone(scope) { path, data -> onMessage(path, data) } else null

    private val _turn = MutableStateFlow<WatchTurn?>(null)
    val turn: StateFlow<WatchTurn?> = _turn.asStateFlow()

    private val _chats = MutableStateFlow<ChatList?>(null)
    val chats: StateFlow<ChatList?> = _chats.asStateFlow()

    private val _phone = MutableStateFlow(PhoneState.Unknown)
    val phone: StateFlow<PhoneState> = _phone.asStateFlow()

    private val _liveVoice = MutableStateFlow(false)

    /** The phone's gateway talks live, so the watch can hold a voice call instead of sending what it heard. */
    val liveVoice: StateFlow<Boolean> = _liveVoice.asStateFlow()

    private val _call = MutableStateFlow<WatchCallState?>(null)
    val call: StateFlow<WatchCallState?> = _call.asStateFlow()

    // The call's state is the main thread's alone: the screen, the phone's messages and the call itself meet there.
    private val main = Dispatchers.Main.immediate
    private var watchCall: WatchCall? = null
    private var callJob: Job? = null
    private var callUpdateSeq = 0
    private var callEventsSeq = 0
    private var callSends = InOrder<CallEvents> {}
    private var callSendGap: Job? = null

    private var watchdog: Job? = null

    /** The ask whose voice was stopped here: its clips still arriving are dropped. */
    @Volatile private var silenced: String? = null

    /** Sends what was said or typed; with [audio], the phone has the gateway transcribe it first. */
    fun ask(text: String?, audio: ByteArray? = null, audioType: String? = null) {
        speech.stop()
        dismissCall()
        val id = UUID.randomUUID().toString()
        val chat = store.chat.value
        val ask = WatchAsk(id, text.orEmpty().trim(), chat.id, speak = store.speak.value && speech.canPlay(), audioType = audioType)
        _turn.value = WatchTurn(id, text?.trim(), ReplyUpdate(id, if (audio != null) ReplyPhase.Transcribing else ReplyPhase.Sending, chatId = chat.id, title = chat.title))
        scope.launch {
            val sent = if (audio != null) stream(WearPaths.recordingPath(id), RecordedAsk.write(ask, audio)) else send(WearPaths.ASK, WearJson.encode(ask))
            if (sent != null) fail(id, sent)
        }
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(ANSWER_WITHIN_MS)
            val now = _turn.value
            if (now != null && now.askId == id && now.update.seq == 0 && now.running) {
                _phone.value = PhoneState.NoHerald
                fail(id, NO_HERALD)
            }
        }
    }

    /** Stops the running turn, and the voice reading a reply. */
    fun stop() {
        quiet()
        val now = _turn.value ?: return
        if (!now.running) return
        // The phone stops telling once it stops the turn: what came so far stays as the reply.
        _turn.update { it?.takeIf { t -> t.askId == now.askId }?.copy(update = now.update.copy(phase = ReplyPhase.Done, activity = null)) ?: it }
    }

    /**
     * Silences the reply: what plays stops, and clips still on their way are dropped. The phone is told while it
     * may still be making them, so it stops (and lets its connection go).
     */
    fun quiet() {
        speech.stop()
        val now = _turn.value ?: return
        silenced = now.askId
        if (now.running || now.update.speech == ReplySpeech.Coming) {
            scope.launch { send(WearPaths.STOP, WearJson.encode(WatchStop(now.askId))) }
        }
    }

    /**
     * Starts a live voice call in the chat: the watch makes the WebRTC call, the phone gets the voice model's
     * answer and runs the conversation. Needs the microphone permission.
     */
    fun startCall() {
        if (_call.value?.active == true) return
        quiet()
        val id = UUID.randomUUID().toString()
        callUpdateSeq = 0
        callEventsSeq = 0
        callSends = InOrder { batch -> watchCall?.let { call -> batch.events.forEach(call::send) } }
        _turn.value = null
        _call.value = WatchCallState(id, CallStage.Starting)
        callJob = scope.launch(main) {
            val call = WatchCall(context)
            watchCall = call
            try {
                val offer = call.offer()
                send(WearPaths.CALL_START, WearJson.encode(CallStart(id, store.chat.value.id, offer)))?.let { return@launch finishCall(id, it) }
                _call.update { if (it?.id == id && it.stage == CallStage.Starting) it.copy(stage = CallStage.Connecting) else it }
                launch {
                    delay(ANSWER_WITHIN_MS)
                    if (_call.value?.id == id && callUpdateSeq == 0) {
                        _phone.value = PhoneState.NoHerald
                        finishCall(id, NO_HERALD)
                    }
                }
                launch {
                    while (true) {
                        val speaking = call.speaking()
                        _call.update { if (it?.id == id) it.copy(speaking = speaking, level = if (it.muted || speaking) 0f else call.micLevel()) else it }
                        delay(CALL_POLL_MS)
                    }
                }
                // The phone ends a call it no longer hears from: it's told the watch is still there.
                launch {
                    while (true) {
                        delay(HEARTBEAT_MS)
                        send(WearPaths.CALL_EVENTS, WearJson.encode(CallEvents(id, ++callEventsSeq, emptyList())))
                    }
                }
                forwardEvents(id, call)
                // The channel closed. When the phone ended it ("stop" said), its word is on the way; else it dropped.
                delay(DROP_GRACE_MS)
                if (_call.value?.let { it.id == id && it.active } == true) {
                    send(WearPaths.CALL_END, WearJson.encode(CallEnd(id)))
                    finishCall(id, "The call dropped.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finishCall(id, e.message ?: "Couldn't start the call.")
            }
        }
    }

    /**
     * Picks up the ring for [requestId]: the phone stops ringing, and the watch talks in its chat from now on. The
     * call itself is started by the screen that holds it ([startCall]). Null once it's no longer ringing.
     */
    fun answerRing(requestId: String): WatchRing? {
        val ring = rings.stop(requestId) ?: return null
        store.setChat(ring.chatId, ring.title)
        scope.launch {
            // Again if it didn't go: else the phone rings on through the call.
            repeat(ANSWER_TRIES) {
                if (send(WearPaths.RING_END, WearJson.encode(RingEnd(requestId, answered = true))) != null) return@launch
                delay(ANSWER_AGAIN_MS)
            }
        }
        return ring
    }

    /** Declines the ring for [requestId]: it stops here and on the phone, where the question waits as a notification. */
    fun declineRing(requestId: String) {
        rings.stop(requestId)
        scope.launch { send(WearPaths.RING_END, WearJson.encode(RingEnd(requestId))) }
    }

    /** Hangs up. */
    fun endCall() {
        val now = _call.value?.takeIf { it.active } ?: return
        scope.launch { send(WearPaths.CALL_END, WearJson.encode(CallEnd(now.id))) }
        finishCall(now.id, null)
    }

    /** Turns the microphone off or on in the call, and tells the voice model so it doesn't wait on you. */
    fun toggleMute() {
        val now = _call.value?.takeIf { it.stage == CallStage.Live } ?: return
        val muted = !now.muted
        watchCall?.setMuted(muted)
        watchCall?.send(muteEvent(UUID.randomUUID().toString(), muted))
        _call.update { if (it?.id == now.id) it.copy(muted = muted, level = 0f) else it }
    }

    /** Puts away an ended call, and what went wrong in it. */
    fun dismissCall() {
        _call.update { if (it?.active == false) null else it }
    }

    /** What the voice model sends on the event channel goes to the phone, gathered a moment, the kinds it acts on. */
    private suspend fun forwardEvents(id: String, call: WatchCall) {
        while (true) {
            val first = call.events.receiveCatching().getOrNull() ?: return
            delay(EVENT_GATHER_MS)
            val batch = mutableListOf(first)
            while (true) batch += call.events.tryReceive().getOrNull() ?: break
            val wanted = batch.filter { eventType(it) in CALL_EVENT_TYPES }
            if (wanted.isNotEmpty()) send(WearPaths.CALL_EVENTS, WearJson.encode(CallEvents(id, ++callEventsSeq, wanted)))
        }
    }

    private fun finishCall(id: String, error: String?) {
        // Once: a call already over keeps how it ended, not what failed in it after.
        if (_call.value?.let { it.id == id && it.active } != true) return
        watchCall?.close()
        watchCall = null
        _call.update { if (it?.id == id) it.copy(stage = CallStage.Ended, error = error, speaking = false, level = 0f, thinking = false, working = null) else it }
        // Something went wrong: what the phone can do may have changed (live voice gone, signed out).
        if (error != null) refreshPhone()
        callSendGap?.cancel()
        callJob?.cancel()
        callJob = null
    }

    private fun onCallUpdate(update: CallUpdate) {
        val now = _call.value ?: return
        // Only the call on screen, and never an older update over a newer one.
        if (update.callId != now.id || !now.active || update.seq <= callUpdateSeq) return
        callUpdateSeq = update.seq
        _phone.value = PhoneState.Ready
        update.chatId?.let { store.adoptChat(it, update.title ?: store.chat.value.title) }
        store.setStats(update.model, update.contextPercent)
        if (update.phase == CallPhase.Ended) {
            finishCall(now.id, update.error)
            return
        }
        // The phone repeats it until it hears from the call; the call takes the first.
        update.answerSdp?.let { sdp ->
            scope.launch(main) {
                try {
                    watchCall?.answer(sdp)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Hung up meanwhile: the failure is only the call closing under it.
                    if (_call.value?.let { it.id == now.id && it.active } != true) return@launch
                    send(WearPaths.CALL_END, WearJson.encode(CallEnd(now.id)))
                    finishCall(now.id, e.message ?: "The call didn't connect.")
                }
            }
        }
        _call.update {
            if (it?.id != now.id || !it.active) it
            else it.copy(
                stage = if (update.phase == CallPhase.Live) CallStage.Live else it.stage,
                liveSince = it.liveSince ?: SystemClock.elapsedRealtime().takeIf { update.phase == CallPhase.Live },
                caption = update.caption ?: it.caption,
                captionIsVoice = if (update.caption != null) update.captionIsVoice else it.captionIsVoice,
                thinking = update.thinking,
                working = update.working,
                error = update.error,
            )
        }
    }

    /** What Herald says to the voice model, in the order it was said, though messages may overtake each other. */
    private fun onCallSend(events: CallEvents) {
        val now = _call.value ?: return
        if (events.callId != now.id || !now.active) return
        val order = callSends
        order.add(events.seq, events)
        if (order.waiting && callSendGap?.isActive != true) callSendGap = scope.launch(main) { order.skipGaps() }
    }

    fun newChat() {
        speech.stop()
        // A call talks in its chat: another one hangs it up.
        endCall()
        dismissCall()
        store.setChat(null, null)
        _turn.value = null
    }

    fun pickChat(chat: WatchChat) {
        speech.stop()
        if (chat.id != store.chat.value.id) endCall()
        dismissCall()
        store.setChat(chat.id, chat.title)
        _turn.value = null
    }

    fun refreshChats() {
        scope.launch {
            send(WearPaths.CHATS, ByteArray(0))?.let { _chats.value = ChatList(error = it) }
        }
    }

    fun refreshPhone() {
        scope.launch { send(WearPaths.STATUS, WearJson.encode(PhoneStatusRequest(accentRevision = store.accent.value?.revision))) }
    }

    /** A message from the phone, from [WatchListenerService] or the fake phone. */
    fun onMessage(path: String, data: ByteArray) {
        when (path) {
            WearPaths.REPLY -> WearJson.decode<ReplyUpdate>(data)?.let(::onReply)
            WearPaths.CHATS -> WearJson.decode<ChatList>(data)?.let { _chats.value = it }
            WearPaths.CALL_UPDATE -> WearJson.decode<CallUpdate>(data)?.let { scope.launch(main) { onCallUpdate(it) } }
            WearPaths.CALL_SEND -> WearJson.decode<CallEvents>(data)?.let { scope.launch(main) { onCallSend(it) } }
            // Not over a call already on: the question is asked there anyway, and it waits on the phone.
            // On a call it doesn't ring over it: the phone, told nothing, rings on and shows its own call here.
            WearPaths.RING -> WearJson.decode<WatchRing>(data)?.let {
                if (_call.value?.active != true && rings.ring(it)) {
                    scope.launch { send(WearPaths.RING_ON, WearJson.encode(RingOn(it.requestId))) }
                }
            }
            WearPaths.RING_END -> WearJson.decode<RingEnd>(data)?.let { rings.stop(it.requestId) }
            WearPaths.STATUS -> WearJson.decode<PhoneStatus>(data)?.let {
                // The tile draws its mic in the accent too.
                if (it.accent?.let(store::setAccent) == true) TileService.getUpdater(context).requestUpdate(AskTileService::class.java)
                it.liveVoice?.let { live -> _liveVoice.value = live }
                _phone.value = when {
                    !it.signedIn -> PhoneState.SignedOut
                    it.connected -> PhoneState.Ready
                    it.failed -> PhoneState.Unreachable
                    else -> PhoneState.Reconnecting
                }
            }
        }
    }

    /** A clip of the reply's voice. */
    fun onSpeech(path: String, bytes: ByteArray) {
        val clip = WearPaths.parseSpeech(path) ?: return
        val now = _turn.value ?: return
        if (clip.askId != now.askId || clip.askId == silenced || !store.speak.value) return
        scope.launch(Dispatchers.Main) { speech.clip(clip.askId, clip.index, clip.count, clip.mimeType, bytes) }
    }

    private fun onReply(update: ReplyUpdate) {
        var taken = false
        // Atomic: this runs off the main thread, where an ask() in between must not be written over.
        _turn.update { now ->
            taken = false
            // Only the ask on screen, and never an older update over a newer one.
            if (now == null || update.askId != now.askId || update.seq <= now.update.seq) return@update now
            if (!now.running && update.phase != ReplyPhase.Done) return@update now
            taken = true
            now.copy(update = update)
        }
        if (!taken) return
        _phone.value = PhoneState.Ready
        update.chatId?.let { store.adoptChat(it, update.title ?: store.chat.value.title) }
        store.setStats(update.model, update.contextPercent)
        if (update.phase == ReplyPhase.Done) {
            if (update.text.isNotBlank()) {
                store.lastReply = update.text
                TileService.getUpdater(context).requestUpdate(AskTileService::class.java)
            }
            if (update.speech == ReplySpeech.Unavailable && update.askId != silenced && store.speak.value && update.text.isNotBlank()) {
                scope.launch(Dispatchers.Main) { speech.say(update.askId, spokenText(update.text)) }
            }
        }
    }

    private fun fail(askId: String, message: String) {
        _turn.update { now ->
            if (now == null || now.askId != askId || !now.running) now else now.copy(update = now.update.copy(phase = ReplyPhase.Failed, error = message, activity = null))
        }
    }

    /** Sends a message to Herald on the phone; null when it went, else what to tell the person. */
    private suspend fun send(path: String, data: ByteArray): String? = reach(path, data) { node ->
        messages.sendMessage(node, path, data).await()
    }

    private suspend fun stream(path: String, data: ByteArray): String? = reach(path, data) { node ->
        val channel = channels.openChannel(node, path).await()
        try {
            val output = channels.getOutputStream(channel).await()
            withContext(Dispatchers.IO) { output.use { it.write(data) } }
        } finally {
            channels.close(channel)
        }
    }

    /** Runs [block] with Herald's phone node; [path] and [data] go to the fake phone when it stands in. */
    private suspend fun reach(path: String, data: ByteArray, block: suspend (String) -> Unit): String? {
        return try {
            val phone = capabilities.getCapability(WearCapabilities.PHONE, CapabilityClient.FILTER_REACHABLE).await().nodes
            // A phone's capabilities can reach the watch minutes after Herald is installed or updated there: until
            // then the connected phone is tried anyway, and one without Herald never answers (ANSWER_WITHIN_MS).
            val connected = if (phone.isEmpty()) nodes.connectedNodes.await() else emptyList()
            val node = phone.firstOrNull { it.isNearby } ?: phone.firstOrNull() ?: connected.firstOrNull { it.isNearby } ?: connected.firstOrNull()
            if (node == null) {
                if (fake != null) {
                    _phone.value = PhoneState.Ready
                    fake.receive(path, data)
                    return null
                }
                _phone.value = PhoneState.NotConnected
                return NOT_CONNECTED
            }
            block(node.id)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "Couldn't reach your phone."
        }
    }

    companion object {
        const val NOT_CONNECTED = "Your phone isn't connected."
        const val NO_HERALD = "Kyllene on your phone didn't answer. Open it there, or update it."
        private const val ANSWER_WITHIN_MS = 30_000L
        private const val CALL_POLL_MS = 100L
        private const val EVENT_GATHER_MS = 120L

        /** The phone gives up on a call after 30 s without a word from the watch. */
        private const val HEARTBEAT_MS = 10_000L
        private const val DROP_GRACE_MS = 2_000L
        private const val ANSWER_TRIES = 3
        private const val ANSWER_AGAIN_MS = 1_000L
    }
}

/** The `type` of an event-channel message; null for garbage. */
internal fun eventType(raw: String): String? = runCatching {
    (WearJson.json.parseToJsonElement(raw) as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
}.getOrNull()

/** Tells the voice model the microphone is off or on again (its `session.input_audio.mute`). */
internal fun muteEvent(id: String, muted: Boolean): String = buildJsonObject {
    put("event_id", id)
    put("type", if (muted) "session.input_audio.mute" else "session.input_audio.unmute")
}.toString()

/** A reply as it should sound in the watch's own voice: the words, without the markdown around them. */
internal fun spokenText(markdown: String): String = markdown
    .replace(Regex("```[\\s\\S]*?(?:```|$)"), "")
    .replace(Regex("""\[([^\]]+)\]\(([^)]+)\)"""), "$1")
    .replace(Regex("`([^`]+)`"), "$1")
    .replace(Regex("""\bhttps?://\S+""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("[*_~>#|]"), "")
    .replace(Regex("""\s+"""), " ")
    .trim()
