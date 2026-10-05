package dev.hermeskotlin.android.notify

import android.content.Context
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var serviceStarted = false

    fun start() {
        scope.launch {
            combine(host.session, settings.settings) { session, prefs -> session to (prefs?.keepsProcessUp == true) }
                .distinctUntilChanged()
                .collectLatest { (session, stay) ->
                    when {
                        session != null -> follow(session)
                        // No chat open (the app started in the background after an update or reboot):
                        // Notifications anywhere still needs the process up, for its ntfy stream.
                        stay -> keepConnected()
                        else -> stopService()
                    }
                }
        }
        scope.launch {
            visibility.visible.collect { visible -> if (visible) notifications.cancelAttention() }
        }
    }

    private suspend fun follow(session: ChatSession) = coroutineScope {
        val notified = mutableSetOf<String>()
        var previous: ChatState? = null
        var shown: Shown? = null
        try {
            combine(session.state, visibility.visible, settings.settings, connection.state) { state, visible, stored, link ->
                Quad(state, visible, stored, link)
            }.collect { (state, visible, stored, link) ->
                val prefs = stored ?: AppSettings()

                // Each change is a chance to start it: Android refuses while the app is in the background.
                val wanted = state.running || prefs.keepsProcessUp
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

                // Only what was actually posted counts: a question that came in while Herald was in sight
                // still needs its notification once Herald isn't. In sight, cancelAttention took them down.
                if (visible) {
                    notified.clear()
                } else if (prefs.notifyRequests) {
                    for (request in state.inputRequests) {
                        if (request.id !in notified && notifications.postRequest(state.storedSessionId, state.title, request)) notified.add(request.id)
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
                previous = state
            }
        } finally {
            notified.forEach(notifications::cancelRequest)
        }
    }

    private suspend fun keepConnected() {
        if (!serviceStarted) serviceStarted = ChatService.start(context)
        combine(connection.state, settings.settings) { state, prefs -> state to (prefs?.pushAnywhere == true) }
            .distinctUntilChanged()
            .collect { (state, push) -> if (serviceStarted) notifications.postOngoing(state, push) }
    }

    private fun stopService() {
        if (!serviceStarted) return
        serviceStarted = false
        ChatService.stop(context)
    }

    /** Notifications anywhere holds the ntfy stream, which needs the process alive between turns too. */
    private val AppSettings.keepsProcessUp: Boolean get() = pushAnywhere

    private data class Quad(val state: ChatState, val visible: Boolean, val settings: AppSettings?, val link: ConnectionState)

    /** What the ongoing notification says: it's posted again only when this changes. */
    private data class Shown(val link: KClass<*>, val push: Boolean)
}
