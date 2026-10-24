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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Notifications anywhere": keeps this phone registered with the gateway's herald-push plugin while the
 * setting is on. Registration only ever happens over the signed-in dashboard socket; each time the socket
 * comes up it is repeated (cheap and idempotent), so the gateway's copy and the pinned keys stay current.
 *
 * One identity belongs to one gateway. Moving to another gateway, signing out, or turning this off retires
 * it: new keys and topics, so the old gateway's pushes land where nobody listens, and it is told to forget
 * the phone as soon as it can be reached.
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
                    if (connected) runCatching { cleanUp() }
                    when {
                        !on -> _status.value = PushStatus(State.Off)
                        connected -> runCatching { refresh(install = false) }
                        else -> if (_status.value.state == State.Working) _status.value = PushStatus(State.Unknown)
                    }
                }
        }
        scope.launch {
            // Phone tools updated the shared plugin: look again, so the update isn't still offered here.
            api.updates.drop(1).collect {
                val offered = _status.value.update != null
                if (offered && settings.settings.value?.pushAnywhere == true && connection.state.value is ConnectionState.Connected) {
                    runCatching { refresh(install = false) }
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
        _status.value = PushStatus(State.Off)
        retireNow()
    }

    /**
     * Signing out or leaving this gateway: the identity is retired (new keys and topics next time) and the
     * gateway told while the socket is still up. The setting itself stays as the user left it.
     */
    suspend fun forget() = lock.withLock {
        retireNow()
        if (_status.value.state != State.Off) _status.value = PushStatus(State.Unknown)
    }

    /** The user asked for the newer plugin the status offers: install it on the gateway, then register again. */
    suspend fun updatePlugin() = refresh(install = true, update = true)

    suspend fun test(): Boolean = runCatching { api.test(keys.deviceId) }.getOrDefault(false)

    private suspend fun retireNow() {
        val url = keys.pinnedGatewayUrl() ?: return
        // Retired first: if the gateway can't be told now, it's told on the next connection.
        keys.retire(url)
        api.registrationChanged()
        withTimeoutOrNull(FORGET_BUDGET_MS) { runCatching { cleanUp() } }
    }

    /** Tells the connected gateway to forget identities this phone gave up while it was out of reach. */
    private suspend fun cleanUp() {
        val url = gateways.current()?.gatewayUrl?.value ?: return
        if (connection.state.value !is ConnectionState.Connected) return
        for (id in keys.retired(url)) {
            if (runCatching { api.unregister(id) }.isSuccess) keys.forgetRetired(url, id)
        }
    }

    private suspend fun refresh(install: Boolean, update: Boolean = false) = lock.withLock {
        // Turned off while this waited for the lock: don't register again.
        if (settings.settings.value?.pushAnywhere != true) return@withLock
        val url = gateways.current()?.gatewayUrl?.value ?: return@withLock
        _status.value = PushStatus(State.Working)
        try {
            // An identity registered with another gateway is retired, never shared.
            keys.pinnedGatewayUrl()?.takeIf { it != url }?.let(keys::retire)
            val ready = plugin(install, update) ?: return@withLock
            val info = ready.info
            // Refused keys would leave a registration the phone can't retire: check them first.
            val announced = info.gateway?.takeIf(keys::accepts)
            if (announced == null) {
                _status.value = PushStatus(State.Failed, "The gateway's plugin sent unusable keys.")
                return@withLock
            }
            val reply = api.register(keys.registration(deviceName()))
            val gateway = reply.gateway
            if (!reply.ok || gateway == null) {
                _status.value = PushStatus(State.Failed, reply.error ?: "The gateway refused.")
                return@withLock
            }
            // Learned over the authenticated socket: the keys every push must be signed with.
            try {
                keys.pin(url, gateway)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Registered but untrusted: give the identity up and have the gateway forget it.
                retireNow()
                _status.value = PushStatus(State.Failed, "The gateway's plugin sent unusable keys.")
                return@withLock
            }
            api.registrationChanged()
            _status.value = PushStatus(State.On, listOfNotNull(ready.detail,
                if (reply.watching) null else "Restart the gateway's dashboard to start sending.",
            ).joinToString("\n").ifEmpty { null }, ready.update)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = PushStatus(State.Failed, e.message)
        }
    }

    /**
     * The plugin, ready: switched on when it was only disabled, installed when missing (only with [install]),
     * and updated only with [update], the user's ask. Null with the status set.
     */
    private suspend fun plugin(install: Boolean, update: Boolean): PluginState.Ready? =
        when (val plugin = api.prepare(install, update = update) { _status.value = PushStatus(State.Working, it) }) {
            is PluginState.Ready -> plugin
            PluginState.Missing -> {
                _status.value = PushStatus(State.NotInstalled)
                null
            }
            is PluginState.Outdated -> {
                _status.value = PushStatus(State.Outdated, update = plugin.update)
                null
            }
            is PluginState.Failed -> {
                _status.value = PushStatus(State.Failed, plugin.reason)
                null
            }
        }

    private companion object {
        /** How long leaving a gateway may wait on telling it; the retirement itself is already saved. */
        const val FORGET_BUDGET_MS = 4_000L
    }
}
