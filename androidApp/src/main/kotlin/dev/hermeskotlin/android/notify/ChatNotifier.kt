package dev.hermeskotlin.android.notify

import android.content.Context
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.LiveTurn
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.core.chat.liveTurn
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.phone.ringsForAnswers
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * Turns the open chat's state into Android notifications: the foreground [ChatService] (with its one quiet
 * line) while a turn runs, a heads-up for every question the agent asks, and the reply when a turn ends. Requests and
 * replies are only posted while the app is out of sight, and cleared when it comes back.
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
    /** Something outside the open chat needs the connection now: the watch's turn ([WatchRelay.busy]). */
    private val outsideWork: StateFlow<Boolean> = MutableStateFlow(false),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var serviceStarted = false

    /**
     * Whether Android can promote a Live Update, read when the app goes out of sight (and false while it is in
     * sight: none is posted then). Checking it asks the system, so it isn't done on every change of the chat.
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
        try {
            combine(session.state, visibility.visible, settings.settings, connection.state, outsideWork) { state, visible, stored, link, outside ->
                Quad(state, visible, stored, link) to outside
            }.collect { (quad, outside) ->
                val (state, visible, stored, link) = quad
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
                // reopening the chat keeps that same start.
                if (state.running && previous?.running != true) liveSince = System.currentTimeMillis()

                // A Live Update only while the app is out of sight, the link is up, and Android can promote one.
                val live = if (prefs.liveUpdates && !visible) {
                    state.liveTurn()?.takeIf { link is ConnectionState.Connected && canPromote }
                } else {
                    null
                }
                if (live != null) {
                    val posting = LivePosted(state.storedSessionId, state.title, live)
                    if (posting != liveNow || !liveShown) {
                        notifications.postLive(state.storedSessionId, state.title, live, liveSince)
                        liveShown = true
                    }
                    liveNow = posting
                    liveThisTurn = true
                } else if (liveShown) {
                    notifications.cancelLive()
                    liveShown = false
                }

                // Only what was actually posted counts: a question that came in while Herald was in sight
                // still needs its notification once Herald isn't. In sight, cancelAttention took them down.
                if (visible) {
                    notified.clear()
                } else if (prefs.notifyRequests) {
                    for (request in state.inputRequests) {
                        if (request.id !in notified && notifications.postRequest(state.storedSessionId, state.title, request, prefs.ringsForAnswers, prefs.phoneRingsTalk)) {
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
                            val bot = bots.botWithChat(storedId)
                            if (bot != null && !failed) {
                                notifications.postBotMessage(bot, bots.picture(bot), storedId, text)
                            } else {
                                notifications.postReply(storedId, bot?.label ?: state.title, text, failed)
                            }
                        }
                    }
                }
                if (previous?.running == true && !state.running) {
                    // The Live Update, if one was up, becomes the finished card and then goes by itself.
                    if (!visible && prefs.liveUpdates && liveThisTurn && canPromote) {
                        val reply = state.messages.lastOrNull { it is ChatMessage.Assistant } as? ChatMessage.Assistant
                        notifications.postLiveDone(
                            state.storedSessionId,
                            state.title,
                            liveNow?.turn?.tools ?: 0,
                            System.currentTimeMillis() - liveSince,
                            reply?.outcome == TurnOutcome.Error,
                        )
                    }
                    liveThisTurn = false
                    liveShown = false
                    liveNow = null
                    liveSince = 0L
                }
                previous = state
            }
        } finally {
            notified.forEach(notifications::cancelRequest)
            notifications.cancelLive()
            notifications.cancelLiveDone()
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

    private data class Quad(val state: ChatState, val visible: Boolean, val settings: AppSettings?, val link: ConnectionState)

    /** What the Live Update shows: the chat as well as the turn, so a new title or chat is posted too. */
    private data class LivePosted(val storedSessionId: String?, val title: String?, val turn: LiveTurn)

    /** What the ongoing notification says: it's posted again only when this changes. */
    private data class Shown(val link: KClass<*>, val push: Boolean)
}
