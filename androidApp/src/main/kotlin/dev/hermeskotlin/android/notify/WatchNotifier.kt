package dev.hermeskotlin.android.notify

import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.SessionWatcher
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.core.sessions.ActiveSessions
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Notifies for the chats [SessionWatcher] follows: those running on the gateway that aren't open here,
 * turns started on Desktop or the CLI included. Same rules as [ChatNotifier] for the open chat: a heads-up
 * for each approval or question, the reply when a turn ends, posted only while the app is out of sight and
 * only as the settings allow. The open chat and bot chats are left to their own notifiers.
 */
class WatchNotifier(
    private val watcher: SessionWatcher,
    private val host: ChatHost,
    private val active: ActiveSessions,
    private val settings: SettingsStore,
    private val visibility: AppVisibility,
    private val notifications: ChatNotifications,
    private val bots: BotNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start() {
        scope.launch {
            visibility.visible.collect { visible -> active.inBackground = !visible }
        }
        scope.launch {
            // Only what was actually posted counts, as in ChatNotifier.
            val notified = mutableSetOf<String>()
            combine(watcher.chats, visibility.visible, settings.settings) { chats, visible, prefs -> Triple(chats, visible, prefs ?: AppSettings()) }
                .collect { (chats, visible, prefs) ->
                    val open = openChat()
                    val waiting = chats.values.filter { it.storedId != open && bots.botWithChat(it.storedId) == null }
                    if (visible) {
                        notified.clear()
                    } else if (prefs.notifyRequests) {
                        for (chat in waiting) {
                            for (request in chat.requests) {
                                if (request.id !in notified && notifications.postRequest(chat.storedId, chat.title.ifBlank { null }, request)) notified.add(request.id)
                            }
                        }
                    }
                    // Answered (here or elsewhere), withdrawn, or the turn is over: its notification goes.
                    val stillOpen = waiting.flatMapTo(HashSet()) { chat -> chat.requests.map { it.id } }
                    notified.filter { it !in stillOpen }.forEach {
                        notified.remove(it)
                        notifications.cancelRequest(it)
                    }
                }
        }
        scope.launch {
            watcher.turnEnds.collect { end ->
                val prefs = settings.settings.value ?: AppSettings()
                if (visibility.visible.value || !prefs.notifyReplies) return@collect
                if (end.outcome == TurnOutcome.Interrupted || end.storedId == openChat()) return@collect
                if (bots.botWithChat(end.storedId) != null) return@collect
                val failed = end.outcome == TurnOutcome.Error
                notifications.postReply(end.storedId, end.title.ifBlank { null }, if (failed) end.error ?: end.text else end.text, failed)
            }
        }
    }

    private fun openChat(): String? = host.session.value?.state?.value?.storedSessionId
}
