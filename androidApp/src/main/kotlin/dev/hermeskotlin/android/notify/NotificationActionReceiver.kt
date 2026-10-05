package dev.hermeskotlin.android.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.InputAnswers
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** The buttons and inline replies of [ChatNotifications], applied to the chat [ChatHost] has open. */
class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {

    private val host: ChatHost by inject()
    private val connection: GatewayConnection by inject()
    private val notifications: ChatNotifications by inject()
    private val scope: CoroutineScope by inject()
    private val settings: SettingsStore by inject()
    private val bots: BotsApi by inject()
    private val push: PushSetup by inject()

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
                // Whichever keeps the quiet notification up goes: the socket, and push with it.
                val pushOn = settings.settings.value?.pushAnywhere == true
                settings.update { it.copy(stayConnected = false) }
                if (pushOn) withTimeoutOrNull(RECEIVER_BUDGET_MS) { push.disable() }
            }
            ACTION_APPROVE -> {
                val choice = ApprovalChoice.fromWire(intent.getStringExtra(EXTRA_CHOICE).orEmpty()) ?: return
                answer(session, intent.getStringExtra(EXTRA_REQUEST_ID) ?: return) { InputAnswers.approval(choice) }
            }
            ACTION_CLARIFY -> {
                if (text.isNullOrEmpty()) return
                answer(session, intent.getStringExtra(EXTRA_REQUEST_ID) ?: return) { request ->
                    (request as? InputRequest.Clarify)?.let { InputAnswers.clarify(it, listOf(listOf(text))) }
                }
            }
            ACTION_BOT_CLEARED -> intent.getStringExtra(EXTRA_BOT)?.let(notifications::forgetBot)
            ACTION_BOT_REPLY -> {
                val bot = intent.getStringExtra(EXTRA_BOT) ?: return
                val storedId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
                if (text.isNullOrEmpty()) return
                notifications.cancelBot(bot)
                awaitConnection()
                // The bot's answer comes back as its next notification.
                val sent = scope.async { runCatching { bots.sendToBot(bot, storedId, text) }.isSuccess }
                if (withTimeoutOrNull(RECEIVER_BUDGET_MS) { sent.await() } == false) {
                    notifications.postFailure(bot, ChatNotifications.BOT_ID, "Couldn't send it. Open Herald to try again.")
                }
            }
            ACTION_REPLY -> {
                val storedId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
                if (text.isNullOrEmpty()) return
                // Clears the inline reply's spinner; a failure posts in its place.
                notifications.cancelReply(storedId)
                if (session == null || session.state.value.storedSessionId != storedId) {
                    notifications.postFailure(storedId, ChatNotifications.REPLY_ID, "That chat isn't open anymore. Open Herald to reply.")
                    return
                }
                awaitConnection()
                // The send itself outlives this receiver's few seconds; only the verdict is waited for.
                val sent = scope.async { session.send(text) }
                if (withTimeoutOrNull(RECEIVER_BUDGET_MS) { sent.await() } == false) {
                    notifications.postFailure(storedId, ChatNotifications.REPLY_ID, session.state.value.error ?: "Open Herald to send it again.")
                }
            }
        }
    }

    private suspend fun answer(session: ChatSession?, requestId: String, result: (InputRequest) -> kotlinx.serialization.json.JsonObject?) {
        val request = session?.state?.value?.inputRequests?.firstOrNull { it.id == requestId }
        if (request == null) {
            // Answered elsewhere, or the turn is over.
            notifications.cancelRequest(requestId)
            return
        }
        val answer = result(request) ?: return
        awaitConnection()
        if (session.answer(request, answer)) {
            notifications.cancelRequest(requestId)
        } else {
            notifications.postFailure(requestId, ChatNotifications.REQUEST_ID, "Not connected to the gateway. Open Herald to answer.")
        }
    }

    /** After a while in the background the socket may be down; give it one quick chance to come back. */
    private suspend fun awaitConnection() {
        if (connection.state.value is ConnectionState.Connected) return
        connection.retry()
        withTimeoutOrNull(RECONNECT_BUDGET_MS) { connection.state.first { it is ConnectionState.Connected } }
    }

    companion object {
        const val ACTION_APPROVE = "dev.hermeskotlin.action.APPROVE"
        const val ACTION_CLARIFY = "dev.hermeskotlin.action.CLARIFY"
        const val ACTION_REPLY = "dev.hermeskotlin.action.REPLY"
        const val ACTION_DISCONNECT = "dev.hermeskotlin.action.DISCONNECT"
        const val ACTION_BOT_REPLY = "dev.hermeskotlin.action.BOT_REPLY"
        const val ACTION_BOT_CLEARED = "dev.hermeskotlin.action.BOT_CLEARED"
        const val EXTRA_BOT = "bot"

        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_CHOICE = "choice"
        const val EXTRA_SESSION_ID = "session_id"
        const val KEY_TEXT = "text"

        private const val RECONNECT_BUDGET_MS = 5_000L
        private const val RECEIVER_BUDGET_MS = 4_000L
    }
}
