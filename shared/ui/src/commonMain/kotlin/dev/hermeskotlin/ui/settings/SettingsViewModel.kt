package dev.hermeskotlin.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.gateway.GatewayProbe
import dev.hermeskotlin.core.gateway.ProbeResult
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.ApiResult
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

class SettingsViewModel(
    private val store: SettingsStore,
    private val auth: AuthApi,
    private val probe: GatewayProbe,
) : ViewModel() {

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
}
