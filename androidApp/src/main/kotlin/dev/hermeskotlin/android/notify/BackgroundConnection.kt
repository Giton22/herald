package dev.hermeskotlin.android.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * With Stay connected on, the gateway connection comes back by itself whenever the app's process starts
 * without its screens: after the phone restarts or Herald is updated ([RestartReceiver] wakes it then).
 * Until now only opening the app connected, so bot and reply notifications stopped after either.
 * [ChatNotifier] then keeps the foreground service up even with no chat open.
 */
class BackgroundConnection(
    private val settings: SettingsStore,
    private val gateways: GatewayRepository,
    private val auth: AuthApi,
    private val connection: GatewayConnection,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start() {
        scope.launch {
            if (!settings.settings.filterNotNull().first().stayConnected) return@launch
            val gateway = gateways.current() ?: return@launch
            if (!auth.hasStoredSession(gateway.gatewayUrl)) return@launch
            connection.start(gateway.gatewayUrl)
        }
    }
}

/**
 * Wakes the app after a restart of the phone or an update of Herald, so [BackgroundConnection] can
 * reconnect; the work happens as the process starts, nothing is left to do here.
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
