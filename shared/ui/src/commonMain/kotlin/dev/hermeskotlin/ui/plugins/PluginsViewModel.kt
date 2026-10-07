package dev.hermeskotlin.ui.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.plugins.DashboardPlugin
import dev.hermeskotlin.core.plugins.PluginsApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PluginsUiState(
    /** The gateway's dashboard plugins; null until the first answer. */
    val plugins: List<DashboardPlugin>? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val sessionExpired: Boolean = false,
) {
    /** The plugins with a page to open, in the gateway's own order. */
    val openable: List<DashboardPlugin> get() = plugins.orEmpty().filter { it.openPath != null }
}

/** The gateway's dashboard plugins with a page — OFM Pipeline, Kanban, Dockyard — one tap from a view. */
class PluginsViewModel(private val api: PluginsApi) : ViewModel() {

    private val _state = MutableStateFlow(PluginsUiState())
    val state: StateFlow<PluginsUiState> = _state.asStateFlow()

    private var gateway: SavedGateway? = null
    private var profile: String? = null
    private var loadJob: Job? = null

    fun bind(gateway: SavedGateway, profile: String?) {
        if (this.gateway == gateway && this.profile == profile) return
        this.gateway = gateway
        this.profile = profile
        _state.value = PluginsUiState()
        refresh()
    }

    fun refresh() {
        val url = gateway?.gatewayUrl ?: return
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            val result = api.list(url, profile)
            _state.update { state ->
                when (result) {
                    is ApiResult.Success -> state.copy(plugins = result.value, loading = false)
                    else -> state.copy(
                        loading = false,
                        error = result.errorMessage,
                        sessionExpired = result == ApiResult.SessionExpired,
                    )
                }
            }
        }
    }

    /** Clears the expiry flag once a screen has acted on it; the view model outlives the screen, so
     *  leaving it set would bounce the next mount after a fresh sign-in. */
    fun consumeSessionExpired() = _state.update { it.copy(sessionExpired = false) }
}
