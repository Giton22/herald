package dev.hermeskotlin.android.notify

import android.content.Context
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.TurnOutcome
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var serviceStarted = false

    fun start() {
        scope.launch {
            host.session.collectLatest { session ->
                if (session == null) stopService() else follow(session)
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
            combine(session.state, visibility.visible, ::Pair).collect { (state, visible) ->
                val prefs = settings.settings.value ?: AppSettings()

                // Visible counts as a chance to start it: Android refuses from the background.
                if (state.running && !serviceStarted) serviceStarted = ChatService.start(context)
                if (!state.running) stopService()

                for (request in state.inputRequests) {
                    if (!notified.add(request.id)) continue
                    if (!visible && prefs.notifyRequests) notifications.postRequest(state.title, request)
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
                            notifications.postReply(storedId, state.title, if (failed) reply.error ?: reply.text else reply.text, failed)
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
        session.state
            .map { state ->
                val tool = (state.messages.lastOrNull() as? ChatMessage.Assistant)?.tools?.lastOrNull { it.running }
                WorkingKey(state.title, state.status, state.inputRequests.size, tool?.id)
            }
            .distinctUntilChanged()
            .collect {
                if (serviceStarted) notifications.postWorking(session.state.value)
                delay(1_000)
            }
    }

    private fun stopService() {
        if (!serviceStarted) return
        serviceStarted = false
        ChatService.stop(context)
    }

    private data class WorkingKey(val title: String?, val status: String?, val waiting: Int, val toolId: String?)
}
