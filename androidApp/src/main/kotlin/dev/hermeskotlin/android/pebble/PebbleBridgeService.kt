package dev.hermeskotlin.android.pebble

import android.util.Log
import dev.hermeskotlin.android.notify.ChatNotifier
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.wear.lastExchange
import dev.hermeskotlin.core.wear.WatchRelay
import dev.hermeskotlin.ui.voice.watchAccent
import dev.hermeskotlin.wearlink.PebbleIncoming
import dev.hermeskotlin.wearlink.PebbleKeys
import dev.hermeskotlin.wearlink.PebbleLink
import dev.hermeskotlin.wearlink.PebbleLink.toWatchAsk
import dev.hermeskotlin.wearlink.ReplyPhase
import dev.hermeskotlin.wearlink.ReplyUpdate
import dev.hermeskotlin.wearlink.WatchScope
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.UUID

/**
 * Where Kyllene's Pebble watchapp lands on the phone, even with Kyllene closed: the Pebble phone app binds this while
 * the watchapp is open, and hands it what the watch sends. Each message goes to [WatchRelay], as a Wear OS watch's
 * does. Only the Pebble phone app Kyllene picked ([PebbleApps]) gets through: the base class checks the caller.
 */
class PebbleBridgeService : BasePebbleListenerService() {

    private val relay: WatchRelay by inject()
    private val outbox: PebbleOutbox by inject()
    private val requests: PebbleRequests by inject()
    private val ringer: PebbleRinger by inject()
    private val notifier: ChatNotifier by inject()
    private val settings: SettingsStore by inject()
    private val scope: CoroutineScope by inject()
    private val gateways: GatewayRepository by inject()
    private val auth: AuthApi by inject()
    private val sessions: SessionsApi by inject()
    private val profiles: ProfileStore by inject()

    // Called on the main thread.
    override suspend fun onMessageReceived(watchappUUID: UUID, data: PebbleDictionary, watch: WatchIdentifier): ReceiveResult {
        if (watchappUUID != PebbleOutbox.APP_UUID) return ReceiveResult.Nack
        val node = PebbleOutbox.nodeOf(watch)
        // One this phone doesn't understand is still taken, so the watch doesn't send it again and again.
        val dict = data.toPebbleLink()
        val incoming = PebbleLink.decode(dict) ?: return ReceiveResult.Ack
        // By type number: R8 renames the classes in a release build.
        Log.d(TAG, "From the Pebble: type ${dict[PebbleKeys.TYPE]}")
        if (settings.settings.value?.pebble == false) {
            // Switched off in Settings: the watch says so instead of waiting on an answer.
            outbox.watch(node).message(PebbleLink.off())
            return ReceiveResult.Ack
        }
        ringer.heardFrom(node)
        when (incoming) {
            is PebbleIncoming.Ask -> {
                requests.askedFrom(node)
                relay.ask(node, incoming.toWatchAsk())
                // Started now, while the message is being delivered: the turn may outlast the watchapp's binding.
                notifier.startServiceNow()
            }
            is PebbleIncoming.Stop -> {
                relay.stop(incoming.askId)
                outbox.watch(node).stopped(incoming.askId)
            }
            PebbleIncoming.ChatsRequest -> relay.chats(node)
            is PebbleIncoming.StatusRequest -> scope.launch {
                // Waited for: the watch's first message often starts Kyllene, and the stored settings are still being read then.
                val current = settings.settings.filterNotNull().first()
                val accent = watchAccent(current.accent)
                relay.status(node, accent = accent.updateFor(incoming.accentRevision))
                // Its prompts, once they changed here: the watch keeps them for when the phone is out of reach.
                if (incoming.promptsRevision != PebbleLink.promptsRevision(current.pebblePrompts)) {
                    outbox.watch(node).message(PebbleLink.prompts(current.pebblePrompts))
                }
            }
            is PebbleIncoming.Resync -> outbox.watch(node).resync(incoming.askId)
            is PebbleIncoming.HistoryRequest -> scope.launch { sendHistory(node, incoming.chatId, incoming.scope) }
            is PebbleIncoming.Answer -> requests.answer(node, incoming)
            is PebbleIncoming.RingOn -> ringer.ringsOn(node, incoming)
            is PebbleIncoming.RingEnd -> ringer.endedOnWatch(node, incoming)
        }
        return ReceiveResult.Ack
    }

    /**
     * [chatId]'s last exchange, for the watch opening it, as a finished turn; why not, when it can't be read. Read in
     * the gateway and profile the watch has it in ([owner]), and only when that's the gateway this phone is on.
     */
    private suspend fun sendHistory(node: String, chatId: String, owner: WatchScope?) {
        val saved = gateways.current()
        val update = if (saved == null || !auth.hasStoredSession(saved.gatewayUrl)) {
            failedHistory(chatId, WatchRelay.SIGNED_OUT)
        } else if (owner != null && owner.gateway != saved.gatewayUrl.value) {
            failedHistory(chatId, CHAT_ELSEWHERE)
        } else {
            val profile = if (owner != null) owner.profile else profiles.get(saved.gatewayUrl)
            val scope = WatchScope(saved.gatewayUrl.value, profile)
            when (val result = sessions.messages(saved.gatewayUrl, chatId, limit = HISTORY_ROWS, profile = profile)) {
                is ApiResult.Success -> lastExchange(result.value.messages)
                    ?.let { PebbleLink.history(chatId, null, it.said, it.reply, scope) }
                    ?: PebbleLink.history(chatId, null, null, "Nothing here yet. Press Select to ask.", scope)
                else -> failedHistory(chatId, result.errorMessage ?: "Couldn't read this chat.")
            }
        }
        outbox.watch(node).reply(update)
    }

    private fun failedHistory(chatId: String, error: String) =
        ReplyUpdate(PebbleLink.historyAskId(chatId), ReplyPhase.Failed, chatId = chatId, error = error)

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != PebbleOutbox.APP_UUID) return
        val node = PebbleOutbox.nodeOf(watch)
        outbox.watch(node).opened()
        requests.reopened(node)
        ringer.opened(node)
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == PebbleOutbox.APP_UUID) outbox.watch(PebbleOutbox.nodeOf(watch)).closed()
    }

    private companion object {
        const val TAG = "HeraldPebble"

        /** The relay's words for an ask in a chat from another gateway: the watch picks one again. */
        const val CHAT_ELSEWHERE = "Conversation changed. Choose it again on your watch."

        /** Enough of a chat's newest rows to reach back past a long turn's tool steps to its prompt. */
        const val HISTORY_ROWS = 40
    }
}
