package dev.hermeskotlin.android.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.connection.GatewayConnection
import org.koin.android.ext.android.inject

/**
 * Keeps the process in the foreground while a turn runs (or all the time with Stay connected), so
 * Android doesn't cut the gateway socket when the app is in the background. Its notification is kept
 * current by [ChatNotifier].
 *
 * A special-use service: data sync, the closest standard type, is capped at six hours a day.
 */
class ChatService : Service() {

    private val host: ChatHost by inject()
    private val notifications: ChatNotifications by inject()
    private val connection: GatewayConnection by inject()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            ChatNotifications.WORKING_ID,
            notifications.working(host.session.value?.state?.value, connection.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Returns false when Android refused to start it. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, ChatService::class.java))
            true
        } catch (e: Exception) {
            // Starting from the background is refused (e.g. a turn another client began while we
            // were away); the turn still runs, just without keeping the socket alive.
            Log.w("ChatService", "Couldn't start the foreground service", e)
            false
        }

        fun stop(context: Context) = context.stopService(Intent(context, ChatService::class.java))
    }
}
