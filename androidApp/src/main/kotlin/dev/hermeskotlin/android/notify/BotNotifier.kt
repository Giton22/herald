package dev.hermeskotlin.android.notify

import android.content.Context
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.bots.forRoster
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.TranscriptRows
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tells the user when a bot writes in its chat while Herald is out of sight, as a conversation
 * notification from that bot. Every bot is watched, not only the open chat ([ChatNotifier] covers that
 * one): the gateway reports chat changes (`sessions.changed`) for the bots it was asked to watch, and the
 * roster then shows which chat moved. A notification is only posted when the newest message really is the
 * bot's, never for something the user wrote on another device. Hidden bots stay quiet, as on Desktop.
 * Also keeps the launcher's bot shortcuts current. Only hears anything while the socket is up, which in
 * the background means while a turn runs or Stay connected is on.
 */
@OptIn(FlowPreview::class)
class BotNotifier(
    private val context: Context,
    private val api: BotsApi,
    private val connection: GatewayConnection,
    private val visibility: AppVisibility,
    private val host: ChatHost,
    private val sessions: SessionsApi,
    private val gateways: GatewayRepository,
    private val settings: SettingsStore,
    private val notifications: ChatNotifications,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()

    /** Each bot's chat as last seen (which session, how many messages, the newest line), so only a change notifies. */
    private val seen = mutableMapOf<String, String>()
    private val pictures = mutableMapOf<String, ByteArray>()
    private var roster: List<Bot> = emptyList()

    /** The bot whose permanent chat [storedSessionId] is, as the last roster read knew it. */
    fun botWithChat(storedSessionId: String): Bot? =
        roster.firstOrNull { bot -> bot.canonicalSession?.let { storedSessionId == it.id || storedSessionId == it.openId } == true }

    fun picture(bot: Bot): ByteArray? = pictures[bot.name]

    fun start() {
        // A new socket: take stock without notifying, and ask the gateway to watch every bot's chats.
        scope.launch {
            connection.state.map { it is ConnectionState.Connected }.distinctUntilChanged().collectLatest { connected ->
                if (connected) runCatching { check(notify = false, watch = true) }
            }
        }
        scope.launch {
            connection.events.filter { it.type == "sessions.changed" }.debounce(DEBOUNCE_MS).collect {
                runCatching { check(notify = !visibility.visible.value, watch = false) }
            }
        }
    }

    private suspend fun check(notify: Boolean, watch: Boolean) = lock.withLock {
        val bots = try {
            api.roster().bots.forRoster()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withLock
        }
        roster = bots
        if (watch) {
            bots.forEach { bot -> scope.launch { runCatching { api.watch(bot.name) } } }
            loadPictures(bots)
            BotShortcuts.publish(context, bots.filterNot { it.meta.hidden }, pictures)
        }
        val prefs = settings.settings.value ?: AppSettings()
        val open = host.session.value?.state?.value?.storedSessionId
        for (bot in bots) {
            val chat = bot.canonicalSession ?: continue
            // last_active lags behind new messages on the gateway; the count and newest line don't.
            val now = "${chat.openId}|${chat.messageCount}|${chat.preview}|${chat.activityAt}"
            val before = seen.put(bot.name, now)
            if (!notify || before == null || before == now) continue
            if (bot.meta.hidden || !prefs.notifyReplies) continue
            // The open chat's reply is ChatNotifier's to tell.
            if (open != null && (open == chat.id || open == chat.openId)) continue
            val storedId = chat.openId ?: continue
            val text = botsLastWords(bot, storedId) ?: continue
            notifications.postBotMessage(bot, pictures[bot.name], storedId, text)
        }
    }

    /** The newest message of the chat when it is the bot's own reply (not the user's, not machinery). */
    private suspend fun botsLastWords(bot: Bot, storedId: String): String? {
        val url = gateways.current()?.gatewayUrl ?: return null
        val page = sessions.messages(url, storedId, limit = LAST_ROWS, profile = bot.name) as? ApiResult.Success ?: return null
        val last = page.value.messages.lastOrNull { it.role == "user" || it.role == "assistant" } ?: return null
        if (last.role != "assistant" || last.isHidden || last.displayKind == "failed_turn") return null
        return last.text.trim().takeUnless { it.isEmpty() || TranscriptRows.isSilence(it) }
    }

    private suspend fun loadPictures(bots: List<Bot>) {
        bots.filter { it.hasAvatar && it.name !in pictures }.forEach { bot ->
            runCatching { api.avatar(bot.name) }.getOrNull()?.let { pictures[bot.name] = it }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 1_500L
        const val LAST_ROWS = 4
    }
}
