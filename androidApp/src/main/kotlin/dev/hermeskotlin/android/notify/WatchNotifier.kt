package dev.hermeskotlin.android.notify

import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.SessionWatcher
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.core.sessions.ActiveSessions
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/**
 * Notifies for the chats [SessionWatcher] follows: those running on the gateway that aren't open here,
 * turns started on Desktop or the CLI included. Same rules as [ChatNotifier] for the open chat: a heads-up
 * for each approval or question, the reply when a turn ends, posted only while the app is out of sight and
 * only as the settings allow. The open chat and bot chats are left to their own notifiers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
            // Attaching keeps a chat loaded on the gateway until the socket closes, so only while something here
            // would notify for it, and only once Herald has stayed out of sight a while: a quick switch to another
            // app attaches nothing. A request asked meanwhile still comes with the attach. Read again on each
            // change, so permission granted in system settings counts on the way back.
            combine(visibility.visible, settings.settings) { visible, prefs ->
                val wants = prefs ?: AppSettings()
                !visible && (wants.notifyRequests || wants.notifyReplies) && notifications.canPost
            }.transformLatest { on ->
                if (on) delay(FOLLOW_AFTER_MS)
                emit(on)
            }.collect(watcher::follow)
        }
        scope.launch {
            // A new turn there makes the last reply old news, as ChatNotifier does for the open chat.
            watcher.turnStarts.collect(notifications::cancelReply)
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
                val text = (if (failed) end.error ?: end.text else end.text).ifBlank { "Finished." }
                notifications.postReply(end.storedId, end.title.ifBlank { null }, text, failed)
            }
        }
    }

    private fun openChat(): String? = host.session.value?.state?.value?.storedSessionId

    private companion object {
        /** How long Herald stays out of sight before running chats are attached. */
        const val FOLLOW_AFTER_MS = 15_000L
    }
}
