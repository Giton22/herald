package dev.hermeskotlin.android.notify

import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.SessionWatcher
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.wear.WatchRelay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * Answers an approval or a question wherever it waits: the chat open in Kyllene, the watch's chat (it has its own
 * session), or a chat [SessionWatcher] follows. For a notification's buttons and for a Pebble.
 */
class RequestAnswers(
    private val host: ChatHost,
    private val relay: WatchRelay,
    private val watcher: SessionWatcher,
    private val connection: GatewayConnection,
    private val notifications: ChatNotifications,
) {
    enum class Outcome {
        Answered,

        /** Answered elsewhere already, or its turn is over. */
        Gone,

        /** The answer didn't fit the request. */
        Refused,

        /** Not connected to the gateway, even after a quick try. */
        NotConnected,
    }

    /** Request [requestId] as it waits; null once it's answered or its turn is over. */
    fun find(requestId: String): InputRequest? {
        for (chat in listOfNotNull(host.session.value, relay.session.value)) {
            chat.state.value.inputRequests.firstOrNull { it.id == requestId }?.let { return it }
        }
        return watcher.find(requestId)?.second
    }

    /** Gives request [requestId] the answer [result] makes of it; its notification goes once it's answered or gone. */
    suspend fun answer(requestId: String, result: (InputRequest) -> JsonObject?): Outcome {
        // The open chat's, else the watch's chat's.
        val session = listOfNotNull(host.session.value, relay.session.value)
            .firstOrNull { chat -> chat.state.value.inputRequests.any { it.id == requestId } }
        val request = session?.state?.value?.inputRequests?.firstOrNull { it.id == requestId }
        if (session == null || request == null) {
            // A chat that isn't open but is watched; else answered elsewhere, or the turn is over.
            val watched = watcher.find(requestId)?.second
            val answer = watched?.let(result)
            if (watched == null || answer == null) {
                notifications.cancelRequest(requestId)
                return if (watched == null) Outcome.Gone else Outcome.Refused
            }
            awaitConnection()
            if (!watcher.answer(requestId, answer)) return Outcome.NotConnected
            notifications.cancelRequest(requestId)
            return Outcome.Answered
        }
        val answer = result(request) ?: return Outcome.Refused
        awaitConnection()
        if (!session.answer(request, answer)) return Outcome.NotConnected
        notifications.cancelRequest(requestId)
        return Outcome.Answered
    }

    /** After a while in the background the socket may be down; give it one quick chance to come back. */
    suspend fun awaitConnection() {
        if (connection.state.value is ConnectionState.Connected) return
        connection.retry()
        withTimeoutOrNull(RECONNECT_BUDGET_MS) { connection.state.first { it is ConnectionState.Connected } }
    }

    private companion object {
        const val RECONNECT_BUDGET_MS = 5_000L
    }
}
