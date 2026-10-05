package dev.hermeskotlin.android.notify

import android.content.Context
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * Turns the open chat's state into Android notifications: the foreground [ChatService] while a turn
 * runs, a heads-up for every question the agent asks, and the reply when a turn ends. Requests and
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
        notifications.botName = { storedId -> bots.botWithChat(storedId)?.label }
        scope.launch {
            combine(host.session, settings.settings) { session, prefs -> session to (prefs?.keepsProcessUp == true) }
                .distinctUntilChanged()
                .collectLatest { (session, stay) ->
                    when {
                        session != null -> follow(session)
                        // No chat open (the app started in the background after an update or reboot):
                        // Stay connected still keeps the socket up, for bot notifications.
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
        try {
            launch { keepWorkingNotificationCurrent(session) }
            combine(session.state, visibility.visible, settings.settings, ::Triple).collect { (state, visible, stored) ->
                val prefs = stored ?: AppSettings()

                // Each change is a chance to start it: Android refuses while the app is in the background.
                val wanted = state.running || prefs.keepsProcessUp
                if (wanted && !serviceStarted) serviceStarted = ChatService.start(context)
                if (!wanted) stopService()

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

    /** Status lines can change several times a second; Android drops updates past a few per second. */
    private suspend fun keepWorkingNotificationCurrent(session: ChatSession) {
        combine(session.state, connection.state) { state, connectionState ->
            val tool = (state.messages.lastOrNull() as? ChatMessage.Assistant)?.tools?.lastOrNull { it.running }
            WorkingKey(state.running, state.title, state.status, state.inputRequests.size, tool?.id, connectionState::class)
        }
            .distinctUntilChanged()
            .collect {
                if (serviceStarted) notifications.postWorking(session.state.value, connection.state.value)
                delay(1_000)
            }
    }

    private suspend fun keepConnected() {
        if (!serviceStarted) serviceStarted = ChatService.start(context)
        connection.state.collect { state -> if (serviceStarted) notifications.postWorking(null, state) }
    }

    private fun stopService() {
        if (!serviceStarted) return
        serviceStarted = false
        ChatService.stop(context)
    }

    /** Stay connected holds the socket; Notifications anywhere holds the ntfy stream. Either needs the process alive. */
    private val AppSettings.keepsProcessUp: Boolean get() = stayConnected || pushAnywhere

    private data class WorkingKey(
        val running: Boolean,
        val title: String?,
        val status: String?,
        val waiting: Int,
        val toolId: String?,
        val connection: KClass<*>,
    )
}
