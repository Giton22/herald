package dev.hermeskotlin.android.notify

import android.content.Context
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.LiveTurn
import dev.hermeskotlin.core.chat.LiveTurnKind
import dev.hermeskotlin.core.chat.SessionWatcher
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.core.chat.WatchedChat
import dev.hermeskotlin.core.chat.liveTurn
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.phone.ringsForAnswers
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * Turns the open chat's state into Android notifications: the foreground [ChatService] (with its one quiet
 * line) while a turn runs, a promoted Live Update of the open chat while the app is out of sight — working, waiting
 * on an approval, or finished, stopped or failed — and a heads-up for every question the agent asks, plus the reply
 * when a turn ends.
 *
 * An approval waiting in the Live Update stands in for its usual notification, and a quiet second card lists the
 * other chats when more than one is working. Progress is throttled to [LIVE_GAP_MS] so a busy turn doesn't churn the
 * shade. The Live Update, requests and replies are all posted only while the app is out of sight, and cleared when
 * it comes back.
 *
 * Runs on the main thread, so starting/stopping the service and posting its notification can't race.
 */
class ChatNotifier(
    private val context: Context,
    private val host: ChatHost,
    private val settings: SettingsStore,
    private val visibility: AppVisibility,
    private val notifications: ChatNotifications,
    private val connection: GatewayConnection,
    private val bots: BotNotifier,
    private val watcher: SessionWatcher,
    /** Something outside the open chat needs the connection now: the watch's turn ([WatchRelay.busy]). */
    private val outsideWork: StateFlow<Boolean> = MutableStateFlow(false),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var serviceStarted = false

    /** The turn whose card the user dismissed or demoted: it isn't posted again until a new turn starts. */
    @Volatile
    private var dismissedTurn = 0L

    /** The other-chats card was dismissed or demoted: it stays away until fewer than two chats are working. */
    @Volatile
    private var othersDismissed = false

    /** A live card was dismissed or demoted: its turn stays quiet, as Android asks, and the next one may show again. */
    fun liveDismissed(card: Int, since: Long) {
        if (card == ChatNotifications.LIVE_OTHERS_ID) {
            othersDismissed = true
        } else if (since > 0) {
            dismissedTurn = since
        }
    }

    /**
     * Whether Android can promote a Live Update, cached here when the app goes out of sight (and false while it is
     * in sight: none is posted then). [LiveUpdates.supported] is the live read; checking it asks the system, so it
     * isn't done on every change of the chat.
     */
    private var canPromote = false

    fun start() {
        scope.launch {
            combine(host.session, settings.settings, outsideWork) { session, prefs, outside -> Triple(session, prefs?.keepsProcessUp == true, outside) }
                .distinctUntilChanged()
                .collectLatest { (session, stay, outside) ->
                    when {
                        session != null -> follow(session)
                        // No chat open (the app started in the background after an update or reboot):
                        // Notifications anywhere still needs the process up, for its ntfy stream, and a watch turn the socket.
                        stay || outside -> keepConnected()
                        else -> stopService()
                    }
                }
        }
        scope.launch {
            visibility.visible.collect { visible ->
                canPromote = !visible && LiveUpdates.supported(context)
                if (visible) notifications.cancelAttention()
            }
        }
    }

    private suspend fun follow(session: ChatSession) = coroutineScope {
        val notified = mutableSetOf<String>()
        var previous: ChatState? = null
        var shown: Shown? = null
        var liveShown = false
        var liveNow: LivePosted? = null
        var liveSince = 0L
        var liveThisTurn = false
        var livePostedAt = 0L
        var liveJob: Job? = null
        var pendingLive: LivePosted? = null
        var approvalShown: String? = null
        var othersShown = false
        var othersNow: List<LiveOtherChat>? = null
        /** The turn as last seen, not as last posted: the finished card must show tools run since the last card. */
        var liveLatest: LiveTurn? = null

        fun dueIn() = livePostedAt + LIVE_GAP_MS - System.currentTimeMillis()

        suspend fun postLiveCard(posting: LivePosted) {
            // A Bot Chat keeps its own bot; any other chat runs in a profile, and a profile is a bot with a face.
            val botChat = posting.storedSessionId?.let(bots::botWithChat)
            val face = botChat ?: bots.knownBot(session.profile ?: Bot.DEFAULT)
            notifications.postLive(
                posting.storedSessionId,
                botChat?.label ?: posting.title,
                posting.turn,
                liveSince,
                face,
                face?.let(bots::picture),
            )
            livePostedAt = System.currentTimeMillis()
            liveNow = posting
            liveShown = true
            pendingLive = null
        }

        // Quiet progress: a card that changes while the turn works is refreshed at most every LIVE_GAP_MS. The
        // first card of a turn, a wait, and the end go at once; the rest waits out the gap, and only what is
        // still current when it ends is posted.
        suspend fun showLive(posting: LivePosted, immediate: Boolean) {
            if (posting == liveNow && liveShown) return
            val wait = dueIn()
            if (immediate || wait <= 0) {
                liveJob?.cancel()
                postLiveCard(posting)
                return
            }
            pendingLive = posting
            liveJob?.cancel()
            val atSince = liveSince
            liveJob = launch {
                delay(wait)
                val next = pendingLive ?: return@launch
                val current = session.state.value
                val stillLive = current.liveTurn()
                if (atSince == liveSince && liveSince != dismissedTurn && current.running && stillLive != null &&
                    LivePosted(current.storedSessionId, current.title, stillLive) == next
                ) {
                    postLiveCard(next)
                }
            }
        }

        try {
            val shared = combine(visibility.visible, settings.settings, connection.state, outsideWork, watcher.chats) { visible, stored, link, outside, chats ->
                Ctx(visible, stored, link, outside, chats)
            }
            combine(session.state, shared) { state, ctx -> state to ctx }.collect { (state, ctx) ->
                val (visible, stored, link, outside, chats) = ctx
                val prefs = stored ?: AppSettings()

                // Each change is a chance to start it: Android refuses while the app is in the background.
                val wanted = state.running || prefs.keepsProcessUp || outside
                if (wanted && !serviceStarted) {
                    serviceStarted = ChatService.start(context)
                    // The service shows the notification as it is when it starts.
                    shown = Shown(link::class, prefs.pushAnywhere)
                }
                if (!wanted) stopService()
                // Its line says only whether Herald is connected, so it changes with the link, never with a turn.
                val now = Shown(link::class, prefs.pushAnywhere)
                if (serviceStarted && now != shown) {
                    notifications.postOngoing(link, prefs.pushAnywhere)
                    shown = now
                }

                // The turn's own clock, on the false→true edge, whether or not anything is posted. A turn this
                // device opens mid-flight starts when it is first seen; leaving the app, losing the link or
                // reopening the chat keeps that same start. A new turn may show a card the last one had dismissed.
                if (state.running && previous?.running != true) {
                    liveSince = System.currentTimeMillis()
                    dismissedTurn = 0L
                    liveThisTurn = false
                }

                val botChat = state.storedSessionId?.let(bots::botWithChat)
                // Every chat runs in a profile, and a profile is a bot with a face: a Bot Chat keeps its own.
                val face = botChat ?: bots.knownBot(session.profile ?: Bot.DEFAULT)
                val picture = face?.let(bots::picture)
                // The face never stands in for the title: a Bot Chat is its bot's name, any other chat its own title.
                val cardTitle = botChat?.label ?: state.title
                // An approval that can show as a card, and wouldn't ring like a call instead: the live card is
                // the approval surface, so no plain notification and no second live card.
                val approval = state.inputRequests.filterIsInstance<InputRequest.Approval>().firstOrNull()
                val approvalSurface = approval != null && prefs.liveUpdates && !visible && canPromote && prefs.notifyRequests &&
                    !notifications.ringing.value.containsKey(approval.id) &&
                    !notifications.wouldRing(approval, prefs.ringsForAnswers)
                if (approvalSurface) {
                    if (approvalShown != approval.id) {
                        liveJob?.cancel()
                        pendingLive = null
                        notifications.postLiveApproval(state.storedSessionId, cardTitle, approval, liveSince, face, picture)
                        approvalShown = approval.id
                        notified.add(approval.id)
                        liveShown = false
                    }
                } else if (approvalShown != null) {
                    // The card is gone: let the ordinary path decide again, even if it was marked as shown.
                    notified.remove(approvalShown)
                    notifications.cancelLiveApproval()
                    approvalShown = null
                }

                // A Live Update only while the app is out of sight, the link is up, Android can promote one, and
                // the user hasn't dismissed this turn's card.
                val turnNow = state.liveTurn()
                if (turnNow != null) liveLatest = turnNow
                val live = if (prefs.liveUpdates && !visible) {
                    turnNow?.takeIf {
                        link is ConnectionState.Connected && canPromote && !approvalSurface && liveSince != dismissedTurn
                    }
                } else {
                    null
                }
                if (live != null) {
                    val posting = LivePosted(state.storedSessionId, state.title, live)
                    val immediate = !liveShown ||
                        (live.kind == LiveTurnKind.Waiting && liveNow?.turn?.kind != LiveTurnKind.Waiting)
                    showLive(posting, immediate)
                    liveThisTurn = true
                } else if (liveShown) {
                    liveJob?.cancel()
                    pendingLive = null
                    notifications.cancelLive()
                    liveShown = false
                }
                if (approvalSurface) liveThisTurn = true

                // Only what was actually posted counts: a question that came in while Herald was in sight
                // still needs its notification once Herald isn't. In sight, cancelAttention took them down.
                if (visible) {
                    notified.clear()
                } else if (prefs.notifyRequests) {
                    for (request in state.inputRequests) {
                        if (request.id in notified) continue
                        // The one approval the card shows; any other still gets its usual notification.
                        if (request is InputRequest.Approval && approvalSurface && request.id == approval.id) continue
                        if (notifications.postRequest(state.storedSessionId, state.title, request, prefs.ringsForAnswers, prefs.phoneRingsTalk)) {
                            notified.add(request.id)
                        }
                    }
                }
                val open = state.inputRequests.mapTo(HashSet()) { it.id }
                notified.filter { it !in open }.forEach {
                    notified.remove(it)
                    notifications.cancelRequest(it)
                }

                val storedId = state.storedSessionId
                if (storedId != null) {
                    if (state.running && previous?.running == false) notifications.cancelReply(storedId)
                    if (previous?.running == true && !state.running && !visible && prefs.notifyReplies) {
                        val reply = state.messages.lastOrNull() as? ChatMessage.Assistant
                        if (reply != null && reply.outcome != TurnOutcome.Interrupted) {
                            val failed = reply.outcome == TurnOutcome.Error
                            val text = if (failed) reply.error ?: reply.text else reply.text
                            // A bot's chat is the bot talking: its face and name, not "Bot Chat".
                            val endBot = bots.botWithChat(storedId)
                            if (endBot != null && !failed) {
                                notifications.postBotMessage(endBot, bots.picture(endBot), storedId, text)
                            } else {
                                notifications.postReply(storedId, endBot?.label ?: state.title, text, failed)
                            }
                        }
                    }
                }
                if (previous?.running == true && !state.running) {
                    liveJob?.cancel()
                    pendingLive = null
                    // The Live Update, if one was up, becomes the finished card and then goes by itself.
                    if (!visible && prefs.liveUpdates && liveThisTurn && canPromote && liveSince != dismissedTurn) {
                        val reply = state.messages.lastOrNull { it is ChatMessage.Assistant } as? ChatMessage.Assistant
                        val outcome = when (reply?.outcome) {
                            TurnOutcome.Interrupted -> LiveOutcome.Stopped
                            TurnOutcome.Error -> LiveOutcome.Failed
                            else -> LiveOutcome.Finished
                        }
                        val endBotChat = state.storedSessionId?.let(bots::botWithChat)
                        val endFace = endBotChat ?: bots.knownBot(session.profile ?: Bot.DEFAULT)
                        notifications.postLiveDone(
                            state.storedSessionId,
                            endBotChat?.label ?: state.title,
                            liveLatest?.tools ?: 0,
                            System.currentTimeMillis() - liveSince,
                            outcome,
                            liveSince,
                            endFace,
                            endFace?.let(bots::picture),
                        )
                    }
                    approvalShown?.let { notified.remove(it) }
                    notifications.cancelLiveApproval()
                    approvalShown = null
                    liveThisTurn = false
                    liveShown = false
                    liveNow = null
                    liveLatest = null
                    liveSince = 0L
                    dismissedTurn = 0L
                }

                // More than one chat working: one quiet card for the ones that aren't on screen, with the open
                // one's own live card telling its story. Only while the link is up: with it down a chat that
                // finished can keep its stale "running" and hold the card (and the threshold) up for good.
                val otherChats = if (link is ConnectionState.Connected) {
                    chats.values.filter { it.running && it.storedId != state.storedSessionId }
                } else {
                    emptyList()
                }
                val working = otherChats.size + if (state.running) 1 else 0
                if (prefs.liveUpdates && !visible && canPromote && working >= 2) {
                    val list = otherChats.map { LiveOtherChat(it.title.ifBlank { it.storedId }, it.requests.isNotEmpty()) }
                    if (!othersDismissed && (!othersShown || list != othersNow)) {
                        notifications.postLiveOthers(list)
                        othersNow = list
                        othersShown = true
                    }
                } else {
                    if (othersShown) notifications.cancelLiveOthers()
                    othersShown = false
                    othersNow = null
                    if (working < 2) othersDismissed = false
                }

                previous = state
            }
        } finally {
            notified.forEach(notifications::cancelRequest)
            liveJob?.cancel()
            notifications.cancelLive()
            notifications.cancelLiveDone()
            notifications.cancelLiveApproval()
            notifications.cancelLiveOthers()
        }
    }

    private suspend fun keepConnected() {
        if (!serviceStarted) serviceStarted = ChatService.start(context)
        combine(connection.state, settings.settings) { state, prefs -> state to (prefs?.pushAnywhere == true) }
            .distinctUntilChanged()
            .collect { (state, push) -> if (serviceStarted) notifications.postOngoing(state, push) }
    }

    /**
     * Starts the service now, from a caller Android lets start one (the watch's message as it's delivered);
     * the usual rules stop it again once nothing needs it. Main thread only.
     */
    fun startServiceNow() {
        if (!serviceStarted) serviceStarted = ChatService.start(context)
    }

    private fun stopService() {
        if (!serviceStarted) return
        serviceStarted = false
        ChatService.stop(context)
    }

    /** Notifications anywhere holds the ntfy stream, which needs the process alive between turns too. */
    private val AppSettings.keepsProcessUp: Boolean get() = pushAnywhere

    /** The things around the open chat that a card's look depends on: sight, settings, the link and other chats. */
    private data class Ctx(
        val visible: Boolean,
        val settings: AppSettings?,
        val link: ConnectionState,
        val outside: Boolean,
        val chats: Map<String, WatchedChat>,
    )

    /** What the Live Update shows: the chat as well as the turn, so a new title or chat is posted too. */
    private data class LivePosted(val storedSessionId: String?, val title: String?, val turn: LiveTurn)

    /** What the ongoing notification says: it's posted again only when this changes. */
    private data class Shown(val link: KClass<*>, val push: Boolean)

    private companion object {
        /** How often a working turn's card may change while the app is out of sight. */
        const val LIVE_GAP_MS = 2_500L
    }
}
