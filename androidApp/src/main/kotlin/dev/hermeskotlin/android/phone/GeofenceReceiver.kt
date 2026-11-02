package dev.hermeskotlin.android.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.phone.WatchedPlace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * A watched place fired: sends its message to the chat it was added from, as the user and marked as coming from the
 * place, and says so in a notification. Only while the Geofences tool is on, and only on the gateway the place was
 * added on.
 */
class GeofenceReceiver : BroadcastReceiver(), KoinComponent {

    private val places: Geofences by inject()
    private val host: ChatHost by inject()
    private val connection: GatewayConnection by inject()
    private val gateways: GatewayRepository by inject()
    private val confirmations: PhoneConfirmations by inject()
    private val scope: CoroutineScope by inject()
    private val chats: BotsApi by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Geofences.ACTION) return
        val place = intent.getStringExtra(Geofences.EXTRA_ID)?.let(places::find) ?: return
        if (!intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        val pending = goAsync()
        scope.launch {
            try {
                // Recorded both ways, so a re-armed alert reporting where the phone already was isn't sent as news.
                val news = places.moved(place.id, entering)
                if (news && entering == place.arriving && places.enabled()) fire(place)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fire(place: WatchedPlace) {
        val event = if (place.arriving) "Arrived at ${place.name}" else "Left ${place.name}"
        val text = place.sent
        val chat = place.chatTitle?.takeIf { it.isNotBlank() } ?: "the chat"
        val sent = withTimeoutOrNull(BUDGET_MS) { send(place, text) } ?: "the gateway didn't answer in time"
        if (sent == SENT) {
            confirmations.tell(place.id, event, "Sent to $chat: ${place.message}")
        } else {
            confirmations.tell(place.id, event, "Couldn't send it to $chat: $sent. Open Kyllene to send it yourself.")
        }
    }

    /** [SENT], or why it couldn't go. */
    private suspend fun send(place: WatchedPlace, text: String): String {
        val gateway = gateways.current()?.gatewayUrl
        if (gateway?.value != place.gateway) return "Kyllene is signed in to another gateway now"
        val open = host.session.value
        if (open != null && open.state.value.storedSessionId == place.storedSessionId && open.profile == place.profile) {
            awaitConnection(gateway)
            return if (open.send(text)) SENT else open.state.value.error ?: "it wasn't accepted"
        }
        if (awaitConnection(gateway) !is ConnectionState.Connected) return "Kyllene couldn't reach the gateway"
        return try {
            chats.sendToChat(place.profile, place.storedSessionId, text)
            SENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message?.takeIf { it.isNotBlank() } ?: "the gateway refused it"
        }
    }

    /** Kyllene may not be running: brings the connection up on [gateway] and gives it a while. */
    private suspend fun awaitConnection(gateway: GatewayUrl): ConnectionState {
        connection.state.value.let { if (it is ConnectionState.Connected) return it }
        connection.start(gateway)
        connection.retry()
        return withTimeoutOrNull(CONNECT_MS) { connection.state.first { it is ConnectionState.Connected } } ?: connection.state.value
    }

    private companion object {
        const val SENT = ""

        /** A background broadcast gets about a minute; the notification goes out well inside it. */
        const val BUDGET_MS = 40_000L
        const val CONNECT_MS = 20_000L
    }
}
