package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.phone.CallOpeners
import dev.hermeskotlin.core.phone.liveOpener
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.LiveCall
import dev.hermeskotlin.core.wear.CallChat
import dev.hermeskotlin.core.wear.WatchOutbox
import dev.hermeskotlin.core.wear.WatchRelay
import dev.hermeskotlin.core.wear.contextPercentOf
import dev.hermeskotlin.core.wear.modelOf
import dev.hermeskotlin.wearlink.CallEnd
import dev.hermeskotlin.wearlink.CallEvents
import dev.hermeskotlin.wearlink.CallPhase
import dev.hermeskotlin.wearlink.CallStart
import dev.hermeskotlin.wearlink.CallUpdate
import dev.hermeskotlin.wearlink.InOrder
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * Kyllene's side of a live voice call held by the watch. The watch makes the WebRTC call (its microphone and
 * speaker, straight to the voice model); this trades its offer for the model's answer through the gateway and
 * runs the conversation with [LiveConversation], as for a call on the phone: what the model sends on the call's
 * event channel comes from the watch, what Kyllene sends it goes back for the watch to send. Requests run as
 * Hermes turns in the watch's chat. One call at a time; a new one ends the last.
 *
 * Every message is handled on one thread, in the order it came, so a hang-up right after a start finds its call.
 * A watch that goes quiet (out of range, its app gone) ends the call here too: it says it's there every few seconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchCalls(
    private val relay: WatchRelay,
    private val audio: AudioApi,
    private val outbox: WatchOutbox,
    appScope: CoroutineScope,
    private val openers: CallOpeners = CallOpeners(),
) {
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob(appScope.coroutineContext[Job]) + serial)
    private var running: Job? = null
    private var call: WatchHeldCall? = null

    /** The watch [node] starts a call. Busy at once, before the caller returns, so the app may keep itself up. */
    fun start(node: String, start: CallStart) {
        relay.callStarted()
        scope.launch {
            val previous = running
            val held = WatchHeldCall(start.id, node, start.offerSdp)
            // Before waiting on the last call: the watch's events and hang-up for this one find it meanwhile.
            call = held
            // Atomic, so it runs even when cancelled at once, and the call it counted is always let go.
            val job = scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    previous?.cancelAndJoin()
                    run(node, start, held)
                } finally {
                    if (call === held) call = null
                }
            }
            job.invokeOnCompletion { relay.callEnded() }
            running = job
        }
    }

    /**
     * The voice model's events of a call as the watch got them, or (empty) that the watch is still there. A call
     * that's over here (the watch went quiet too long, Kyllene restarted) is told over, so the watch hangs up too.
     */
    fun events(node: String, events: CallEvents) {
        scope.launch {
            val held = call?.takeIf { it.id == events.callId }
            if (held != null) {
                held.received(events)
            } else {
                val over = CallUpdate(events.callId, CallPhase.Ended, seq = Int.MAX_VALUE, error = CALL_LOST)
                outbox.sendSafely(node, WearPaths.CALL_UPDATE, WearJson.encode(over))
            }
        }
    }

    /** Hung up on the watch. */
    fun end(end: CallEnd) {
        scope.launch { call?.takeIf { it.id == end.callId }?.hungUp() }
    }

    private suspend fun run(node: String, start: CallStart, held: WatchHeldCall) {
        if (held.over) return
        var seq = 0
        var last = CallUpdate(start.id, CallPhase.Connecting, chatId = start.chatId)
        val telling = Mutex()
        suspend fun tell(update: CallUpdate) {
            telling.withLock {
                last = update.copy(seq = ++seq)
                outbox.sendSafely(node, WearPaths.CALL_UPDATE, WearJson.encode(last))
            }
        }
        suspend fun end(error: String?) = tell(last.copy(phase = CallPhase.Ended, answerSdp = null, error = error, thinking = false, working = null))

        tell(last)
        val chat = when (val found = relay.chatForCall(start.chatId)) {
            is CallChat.Failed -> return end(found.message)
            is CallChat.Ready -> found
        }
        if (held.over) return
        when (val status = audio.voiceLiveStatus(chat.gateway, chat.profile)) {
            is ApiResult.Success -> {
                relay.liveVoiceIs(chat.gateway, status.value.live)
                if (!status.value.live) return end(NOT_LIVE)
            }
            else -> return end(liveFailure(status))
        }
        if (held.over) return

        val voice = MutableStateFlow(VoiceChatState(VoicePhase.Connecting, live = true))
        // A call the agent placed, picked up on the watch: the voice opens with why.
        val conversation = LiveConversation(held, chat.session, voice, opener = openers.take(start.chatId)?.liveOpener())
        var answer: String? = null
        held.onAnswer = { sdp ->
            answer = sdp
            tell(last.copy(phase = CallPhase.Live, answerSdp = sdp))
        }
        // What the watch shows of the call, a few times a second at most: the captions, Hermes at work, the chat.
        val forward = scope.launch {
            var heldChat: String? = null
            combine(voice, chat.session.state) { v, s ->
                CallView(v.caption, v.captionIsVoice, v.phase == VoicePhase.Thinking, v.working, v.error?.let(::briefError), s.storedSessionId, s.title, modelOf(s), contextPercentOf(s))
            }.distinctUntilChanged().conflate().collect { view ->
                view.chatId?.takeIf { it != heldChat }?.let {
                    heldChat = it
                    relay.callChat(it)
                }
                if (last.phase == CallPhase.Live) {
                    tell(
                        last.copy(
                            answerSdp = answer.takeUnless { held.heard },
                            caption = view.caption,
                            captionIsVoice = view.captionIsVoice,
                            thinking = view.thinking,
                            working = view.working,
                            error = view.error,
                            chatId = view.chatId ?: last.chatId,
                            title = view.title ?: last.title,
                            model = view.model ?: last.model,
                            contextPercent = view.contextPercent ?: last.contextPercent,
                        ),
                    )
                }
                delay(UPDATE_EVERY_MS)
            }
        }
        // The answer goes again until the call's first events say the watch has it: a lost one would leave the
        // watch "Live" on a call that never connects.
        val answering = scope.launch {
            while (!held.heard) {
                delay(ANSWER_AGAIN_MS)
                val sdp = answer
                if (sdp != null && !held.heard && last.phase == CallPhase.Live) tell(last.copy(answerSdp = sdp))
            }
        }
        val watching = scope.launch { held.watch() }
        val error = try {
            conversation.run {
                held.connect { offer ->
                    when (val result = audio.startVoiceLive(chat.gateway, offer, conversation.history(), chat.profile)) {
                        is ApiResult.Success -> result.value.ifBlank { error("GPT-Live sent no answer.") }
                        else -> error(liveFailure(result))
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                watching.cancel()
                answering.cancel()
                forward.cancelAndJoin()
            }
        }
        // Hung up on the watch: nothing to tell. Otherwise ("stop" said, the call dropped, the watch gone quiet)
        // it's told, in case it's still there.
        if (!held.hungUp) end(if (held.lost) CALL_LOST else error?.let(::briefError))
    }

    /** The watch's call as [LiveConversation] sees it: the model's events come from the watch, and go back to it. */
    private inner class WatchHeldCall(val id: String, private val node: String, private val offer: String) : LiveCall {
        var onAnswer: (suspend (String) -> Unit)? = null

        /** Hung up on the watch. */
        var hungUp = false
            private set

        /** The watch stopped saying it's there. */
        var lost = false
            private set

        val over: Boolean get() = hungUp || lost

        /** The call's events have come from the watch: it has the answer, the call is through. */
        var heard = false
            private set

        private val incoming = Channel<String>(Channel.UNLIMITED)
        override val events: ReceiveChannel<String> = incoming
        private val order = InOrder<CallEvents> { batch -> batch.events.forEach { incoming.trySend(it) } }
        private var gap: Job? = null
        private var heardAt = TimeSource.Monotonic.markNow()
        private val outgoing = Channel<String>(Channel.UNLIMITED)

        override suspend fun connect(answer: suspend (offerSdp: String) -> String) {
            val sdp = answer(offer)
            onAnswer?.invoke(sdp)
            scope.launch { sendBatches() }
        }

        /** A batch from the watch, put back in order if it overtook another. Empty, it only says the watch is there. */
        fun received(batch: CallEvents) {
            heardAt = TimeSource.Monotonic.markNow()
            if (batch.events.isNotEmpty()) heard = true
            order.add(batch.seq, batch)
            if (order.waiting && gap?.isActive != true) gap = scope.launch { order.skipGaps() }
        }

        fun hungUp() {
            hungUp = true
            incoming.close()
        }

        /** Ends the call once the watch has said nothing for a while. */
        suspend fun watch() {
            while (true) {
                delay(WATCH_CHECK_MS)
                if (heardAt.elapsedNow().inWholeMilliseconds > WATCH_QUIET_MS) {
                    lost = true
                    incoming.close()
                    return
                }
            }
        }

        override fun send(event: String): Boolean = outgoing.trySend(event).isSuccess

        /** What Kyllene says to the model, gathered a moment so a reply's sentences go to the watch together. */
        private suspend fun sendBatches() {
            var seq = 0
            while (true) {
                val first = outgoing.receiveCatching().getOrNull() ?: return
                delay(SEND_GATHER_MS)
                val batch = mutableListOf(first)
                while (true) batch += outgoing.tryReceive().getOrNull() ?: break
                outbox.sendSafely(node, WearPaths.CALL_SEND, WearJson.encode(CallEvents(id, ++seq, batch)))
            }
        }

        // The watch shows its own call: it knows when the voice talks and how loud you are.
        override fun speaking(): Boolean = false

        override fun micLevel(): Float = 0f

        // Muting is the watch's: it turns its microphone off and tells the model itself.
        override fun setMuted(muted: Boolean) = Unit

        override fun close() {
            incoming.close()
            gap?.cancel()
            // The close command LiveConversation sends last still goes, then the sender stops.
            outgoing.close()
        }
    }

    private data class CallView(
        val caption: String?,
        val captionIsVoice: Boolean,
        val thinking: Boolean,
        val working: String?,
        val error: String?,
        val chatId: String?,
        val title: String?,
        val model: String?,
        val contextPercent: Int?,
    )

    private suspend fun WatchOutbox.sendSafely(node: String, path: String, data: ByteArray): Boolean = try {
        send(node, path, data)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    companion object {
        const val NOT_LIVE = "Live voice isn't set up on your gateway any more. Tap to ask one question at a time."
        const val CALL_LOST = "Kyllene on your phone lost the call."
        private const val UPDATE_EVERY_MS = 250L
        private const val ANSWER_AGAIN_MS = 1_500L
        private const val SEND_GATHER_MS = 60L
        private const val WATCH_CHECK_MS = 5_000L

        /** The watch says it's there every 10 s; three missed and it's gone. */
        private const val WATCH_QUIET_MS = 30_000L
    }
}
