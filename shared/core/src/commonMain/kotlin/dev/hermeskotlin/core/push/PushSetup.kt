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

    suspend fun test(): Boolean = runCatching { api.test(keys.deviceId) }.getOrDefault(false)

    private suspend fun retireNow() {
        val url = keys.pinnedGatewayUrl() ?: return
        // Retired first: if the gateway can't be told now, it's told on the next connection.
        keys.retire(url)
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

    private suspend fun refresh(install: Boolean) = lock.withLock {
        // Turned off while this waited for the lock: don't register again.
        if (settings.settings.value?.pushAnywhere != true) return@withLock
        val url = gateways.current()?.gatewayUrl?.value ?: return@withLock
        // Nothing here could receive them: never install or register on the gateway.
        keys.unavailable?.let {
            _status.value = PushStatus(State.Failed, it)
            return@withLock
        }
        _status.value = PushStatus(State.Working)
        try {
            // An identity registered with another gateway is retired, never shared.
            keys.pinnedGatewayUrl()?.takeIf { it != url }?.let(keys::retire)
            val info = plugin(install) ?: return@withLock
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
            _status.value = PushStatus(State.On, if (reply.watching) null else "Restart the gateway's dashboard to start sending.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = PushStatus(State.Failed, e.message)
        }
    }

    /**
     * The plugin, ready: switched on when it was only disabled, installed when missing (only with [install]),
     * and reinstalled at the reviewed commit when older than this Herald needs. Null with the status set.
     */
    private suspend fun plugin(install: Boolean): PushPluginReply? {
        var info = api.info()
        if (info == null && install) {
            // Present but switched off (in Desktop's Plugins hub, say): a toggle is enough, and an
            // install would be refused as "already exists".
            _status.value = PushStatus(State.Working, "Setting up on the gateway…")
            if (api.enable()) info = api.info()
            if (info == null) {
                _status.value = PushStatus(State.Working, "Installing on the gateway…")
                api.install()
                info = api.info()
            }
        }
        if (info != null && info.ok && !info.versionAtLeast(PushApi.MIN_PLUGIN_VERSION)) {
            _status.value = PushStatus(State.Working, "Updating the gateway's plugin…")
            api.install(force = true)
            info = api.info()
            if (info != null && info.ok && !info.versionAtLeast(PushApi.MIN_PLUGIN_VERSION)) {
                _status.value = PushStatus(State.Failed, "The gateway's herald-push plugin is too old (${info.version}). Update it there.")
                return null
            }
        }
        if (info == null) {
            _status.value = PushStatus(State.NotInstalled)
            return null
        }
        if (!info.ok) {
            _status.value = PushStatus(State.Failed, info.error)
            return null
        }
        return info
    }

    private companion object {
        /** How long leaving a gateway may wait on telling it; the retirement itself is already saved. */
        const val FORGET_BUDGET_MS = 4_000L
    }
}
