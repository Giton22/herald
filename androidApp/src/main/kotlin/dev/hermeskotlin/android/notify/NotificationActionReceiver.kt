package dev.hermeskotlin.android.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dev.hermeskotlin.android.R
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.InputAnswers
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.SessionWatcher
import dev.hermeskotlin.core.inbox.InboxStore
import dev.hermeskotlin.core.inbox.markNoticeRead
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.wear.WatchRelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** The buttons and inline replies of [ChatNotifications], applied to the chat [ChatHost] has open or to one [SessionWatcher] follows. */
class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {

    private val host: ChatHost by inject()
    private val notifications: ChatNotifications by inject()
    private val scope: CoroutineScope by inject()
    private val bots: BotsApi by inject()
    private val push: PushSetup by inject()
    private val watcher: SessionWatcher by inject()
    private val relay: WatchRelay by inject()
    private val answers: RequestAnswers by inject()
    private val inbox: InboxStore by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                handle(intent)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(intent: Intent) {
        val session = host.session.value
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
        when (intent.action) {
            ACTION_DISCONNECT -> {
                // Notifications anywhere is what keeps the quiet notification up between turns.
                withTimeoutOrNull(RECEIVER_BUDGET_MS) { push.disable() }
            }
            ACTION_APPROVE -> {
                val choice = ApprovalChoice.fromWire(intent.getStringExtra(EXTRA_CHOICE).orEmpty()) ?: return
                answer(intent.getStringExtra(EXTRA_REQUEST_ID) ?: return) { InputAnswers.approval(choice) }
            }
            ACTION_CLARIFY -> {
                if (text.isNullOrEmpty()) return
                answer(intent.getStringExtra(EXTRA_REQUEST_ID) ?: return) { request ->
                    (request as? InputRequest.Clarify)?.let { InputAnswers.clarify(it, listOf(listOf(text))) }
                }
            }
            ACTION_DECLINE_CALL -> intent.getStringExtra(EXTRA_REQUEST_ID)?.let(notifications::decline)
            ACTION_INBOX_READ -> {
                // The run this notification showed, of the gateway that sent it: not whichever the app is on now.
                val key = intent.getStringExtra(ChatNotifications.EXTRA_OPEN_INBOX) ?: return
                val gateway = intent.getStringExtra(ChatNotifications.EXTRA_INBOX_GATEWAY) ?: return
                if (inbox.markNoticeRead(gateway, key)) notifications.runsRead(gateway, listOf(key))
            }
            ACTION_BOT_CLEARED -> intent.getStringExtra(EXTRA_BOT)?.let(notifications::forgetBot)
            ACTION_ROOM_CLEARED -> intent.getStringExtra(ChatNotifications.EXTRA_OPEN_ROOM)?.let(notifications::forgetRoom)
            ACTION_BOT_REPLY -> {
                val bot = intent.getStringExtra(EXTRA_BOT) ?: return
                val storedId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
                if (text.isNullOrEmpty()) return
                notifications.cancelBot(bot)
                awaitConnection()
                // The bot's answer comes back as its next notification.
                val sent = scope.async { runCatching { bots.sendToBot(bot, storedId, text) }.isSuccess }
                if (withTimeoutOrNull(RECEIVER_BUDGET_MS) { sent.await() } == false) {
                    notifications.postFailure(bot, ChatNotifications.BOT_ID, R.string.notification_bot_send_failed)
                }
            }
            ACTION_REPLY -> {
                val storedId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
                if (text.isNullOrEmpty()) return
                // Clears the inline reply's spinner; a failure posts in its place.
                notifications.cancelReply(storedId)
                // The open chat's, else the watch's chat's (a Pebble's reply notification is for it).
                val chat = listOfNotNull(session, relay.session.value).firstOrNull { it.state.value.storedSessionId == storedId }
                if (chat == null) {
                    // A chat followed without being open takes the reply straight to its live session.
                    if (storedId !in watcher.chats.value) {
                        notifications.postFailure(storedId, ChatNotifications.REPLY_ID, R.string.notification_chat_closed)
                        return
                    }
                    awaitConnection()
                    val sent = scope.async { watcher.reply(storedId, text) }
                    if (withTimeoutOrNull(RECEIVER_BUDGET_MS) { sent.await() } == false) {
                        notifications.postFailure(storedId, ChatNotifications.REPLY_ID, R.string.notification_reply_send_failed)
                    }
                    return
                }
                awaitConnection()
                // The send itself outlives this receiver's few seconds; only the verdict is waited for.
                val sent = scope.async { chat.send(text) }
                if (withTimeoutOrNull(RECEIVER_BUDGET_MS) { sent.await() } == false) {
                    val error = chat.state.value.error
                    if (error != null) {
                        notifications.postFailure(storedId, ChatNotifications.REPLY_ID, error)
                    } else {
                        notifications.postFailure(storedId, ChatNotifications.REPLY_ID, R.string.notification_reply_open_app)
                    }
                }
            }
        }
    }

    private suspend fun answer(requestId: String, result: (InputRequest) -> kotlinx.serialization.json.JsonObject?) {
        if (answers.answer(requestId, result) == RequestAnswers.Outcome.NotConnected) {
            notifications.postFailure(requestId, ChatNotifications.REQUEST_ID, R.string.notification_answer_not_connected)
        }
    }

    private suspend fun awaitConnection() = answers.awaitConnection()

    companion object {
        const val ACTION_APPROVE = "dev.hermeskotlin.action.APPROVE"
        const val ACTION_CLARIFY = "dev.hermeskotlin.action.CLARIFY"
        const val ACTION_REPLY = "dev.hermeskotlin.action.REPLY"
        const val ACTION_DISCONNECT = "dev.hermeskotlin.action.DISCONNECT"
        const val ACTION_BOT_REPLY = "dev.hermeskotlin.action.BOT_REPLY"
        const val ACTION_BOT_CLEARED = "dev.hermeskotlin.action.BOT_CLEARED"
        const val ACTION_ROOM_CLEARED = "dev.hermeskotlin.action.ROOM_CLEARED"
        const val ACTION_DECLINE_CALL = "dev.hermeskotlin.action.DECLINE_CALL"
        const val ACTION_INBOX_READ = "dev.hermeskotlin.action.INBOX_READ"
        const val EXTRA_BOT = "bot"

        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_CHOICE = "choice"
        const val EXTRA_SESSION_ID = "session_id"
        const val KEY_TEXT = "text"

        private const val RECEIVER_BUDGET_MS = 4_000L
    }
}
