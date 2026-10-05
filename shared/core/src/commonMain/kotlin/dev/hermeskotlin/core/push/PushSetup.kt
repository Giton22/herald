package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.push.PushStatus.State
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * "Notifications anywhere": keeps this phone registered with the gateway's herald-push plugin while the
 * setting is on. Registration only ever happens over the signed-in dashboard socket; each time the socket
 * comes up it is repeated (cheap and idempotent), so the gateway's copy and the pinned keys stay current.
 */
class PushSetup(
    private val api: PushApi,
    private val keys: PushKeys,
    private val connection: GatewayConnection,
    private val gateways: GatewayRepository,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val deviceName: () -> String,
) {
    private val _status = MutableStateFlow(PushStatus())
    val status: StateFlow<PushStatus> = _status.asStateFlow()
    private val lock = Mutex()

    fun start() {
        scope.launch {
            combine(connection.state, settings.settings) { state, prefs -> (state is ConnectionState.Connected) to (prefs?.pushAnywhere == true) }
                .distinctUntilChanged()
                .collectLatest { (connected, on) ->
                    when {
                        !on -> _status.value = PushStatus(State.Off)
                        connected -> runCatching { refresh(install = false) }
                        else -> if (_status.value.state == State.Working) _status.value = PushStatus(State.Unknown)
                    }
                }
        }
    }

    /** Turns it on: installs the plugin when the gateway lacks it, then registers this phone. */
    suspend fun enable() {
        settings.update { it.copy(pushAnywhere = true) }
        refresh(install = true)
    }

    /** Turns it off here and on the gateway, which forgets this phone and its topics. */
    suspend fun disable() = lock.withLock {
        settings.update { it.copy(pushAnywhere = false) }
        val url = gateways.current()?.gatewayUrl?.value
        runCatching { api.unregister(keys.deviceId) }
        url?.let(keys::unpin)
        _status.value = PushStatus(State.Off)
    }

    suspend fun test(): Boolean = runCatching { api.test(keys.deviceId) }.getOrDefault(false)

    private suspend fun refresh(install: Boolean) = lock.withLock {
        val url = gateways.current()?.gatewayUrl?.value ?: return@withLock
        _status.value = PushStatus(State.Working)
        try {
            var info = api.info()
            if (info == null && install) {
                _status.value = PushStatus(State.Working, "Installing on the gateway…")
                api.install()
                info = api.info()
            }
            if (info == null) {
                _status.value = PushStatus(State.NotInstalled)
                return@withLock
            }
            if (!info.ok) {
                _status.value = PushStatus(State.Failed, info.error)
                return@withLock
            }
            val reply = api.register(keys.registration(deviceName()))
            val gateway = reply.gateway
            if (!reply.ok || gateway == null) {
                _status.value = PushStatus(State.Failed, reply.error ?: "The gateway refused.")
                return@withLock
            }
            // Learned over the authenticated socket: the keys every push must be signed with.
            keys.pin(url, gateway)
            _status.value = PushStatus(State.On, if (reply.watching) null else "Restart the gateway's dashboard to start sending.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = PushStatus(State.Failed, e.message)
        }
    }
}
