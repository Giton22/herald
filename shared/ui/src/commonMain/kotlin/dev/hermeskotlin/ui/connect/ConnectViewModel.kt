package dev.hermeskotlin.ui.connect

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.gateway.GatewayProbe
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.InvalidGatewayUrlException
import dev.hermeskotlin.core.gateway.ProbeResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectUiState(
    val testing: Boolean = false,
    val urlError: String? = null,
    val result: ProbeResult? = null,
)

class ConnectViewModel(private val probe: GatewayProbe) : ViewModel() {

    val url = TextFieldState()

    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    private var probeJob: Job? = null

    fun testConnection() {
        val gatewayUrl = try {
            GatewayUrl.parse(url.text.toString())
        } catch (e: InvalidGatewayUrlException) {
            _state.value = ConnectUiState(urlError = e.message)
            return
        }

        probeJob?.cancel()
        _state.value = ConnectUiState(testing = true)
        probeJob = viewModelScope.launch {
            val result = probe.probe(gatewayUrl)
            _state.update { it.copy(testing = false, result = result) }
        }
    }

    /** Typing invalidates the previous result. */
    fun onUrlEdited() {
        if (_state.value.result != null || _state.value.urlError != null) {
            probeJob?.cancel()
            _state.value = ConnectUiState()
        }
    }
}
