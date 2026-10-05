package dev.hermeskotlin.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.gateway.CheckStage
import dev.hermeskotlin.core.gateway.ConnectionCheck
import dev.hermeskotlin.core.gateway.GatewayProbe
import dev.hermeskotlin.core.gateway.StageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import dev.hermeskotlin.core.gateway.ProbeResult
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.push.PushStatus
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Who is signed in where, for the Settings account and about sections. */
data class GatewayInfo(val userLabel: String? = null, val version: String? = null)

/** "Check connection": the stages done so far, and whether it's still going. */
data class ConnectionCheckState(val results: Map<CheckStage, StageResult> = emptyMap(), val running: Boolean = false)

class SettingsViewModel(
    private val store: SettingsStore,
    private val auth: AuthApi,
    private val probe: GatewayProbe,
    private val connectionCheck: ConnectionCheck,
    private val push: PushSetup,
) : ViewModel() {

    private val _check = MutableStateFlow(ConnectionCheckState())
    val check: StateFlow<ConnectionCheckState> = _check.asStateFlow()
    private var checkJob: Job? = null

    /** Tests server access, sign-in and the live connection one after another. */
    fun runConnectionCheck() {
        val url = bound?.gatewayUrl ?: return
        checkJob?.cancel()
        _check.value = ConnectionCheckState(running = true)
        checkJob = viewModelScope.launch {
            val report = connectionCheck.run(url) { partial -> _check.value = ConnectionCheckState(partial.results, running = true) }
            _check.value = ConnectionCheckState(report.results, running = false)
        }
    }

    val settings: StateFlow<AppSettings> =
        store.settings.filterNotNull().stateIn(viewModelScope, SharingStarted.Eagerly, store.settings.value ?: AppSettings())

    private val _gateway = MutableStateFlow(GatewayInfo())
    val gateway: StateFlow<GatewayInfo> = _gateway.asStateFlow()

    private var bound: SavedGateway? = null

    fun bind(gateway: SavedGateway) {
        if (bound == gateway) return
        bound = gateway
        _gateway.value = GatewayInfo()
        viewModelScope.launch {
            val me = auth.me(gateway.gatewayUrl) as? ApiResult.Success ?: return@launch
            _gateway.update { it.copy(userLabel = me.value.label) }
        }
        viewModelScope.launch {
            val status = probe.probe(gateway.gatewayUrl) as? ProbeResult.Reachable ?: return@launch
            _gateway.update { it.copy(version = status.status.version) }
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) = store.update(transform)

    /** Notifications anywhere: where its setup stands on this gateway. */
    val pushStatus: StateFlow<PushStatus> = push.status

    private val _pushTest = MutableStateFlow<Boolean?>(null)
    /** The last "Send a test": true when the gateway sent it, false when it couldn't, null before or while sending. */
    val pushTest: StateFlow<Boolean?> = _pushTest.asStateFlow()

    // Off the main thread: the first time, the phone's push keys are made and wrapped in the keystore.
    fun setPushAnywhere(on: Boolean) {
        _pushTest.value = null
        viewModelScope.launch(Dispatchers.Default) { if (on) push.enable() else push.disable() }
    }

    fun sendPushTest() {
        _pushTest.value = null
        viewModelScope.launch(Dispatchers.Default) { _pushTest.value = push.test() }
    }
}
