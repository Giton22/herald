package dev.hermeskotlin.ui.connect

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.gateway.AccessToken
import dev.hermeskotlin.core.gateway.AccessTokens
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
    val accessError: String? = null,
    val result: ProbeResult? = null,
)

class ConnectViewModel(private val probe: GatewayProbe, private val access: AccessTokens) : ViewModel() {

    val url = TextFieldState()

    /** A Cloudflare Access service token, for a gateway behind Access. Both blank: keep the one saved for the host. */
    val accessClientId = TextFieldState()
    val accessClientSecret = TextFieldState()

    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    private val _accessSaved = MutableStateFlow(false)

    /** Whether a service token is saved for the address typed, so blank fields keep using it. */
    val accessSaved: StateFlow<Boolean> = _accessSaved.asStateFlow()

    private var probeJob: Job? = null
    private var lookupJob: Job? = null

    fun forgetAccessToken() {
        val host = GatewayUrl.parseOrNull(url.text.toString())?.host ?: return
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            access.set(host, null)
            _accessSaved.value = false
        }
    }

    fun testConnection() {
        val gatewayUrl = try {
            GatewayUrl.parse(url.text.toString())
        } catch (e: InvalidGatewayUrlException) {
            _state.value = ConnectUiState(urlError = e.message)
            return
        }
        val clientId = accessClientId.text.trim().toString()
        val clientSecret = accessClientSecret.text.trim().toString()
        if (clientId.isEmpty() != clientSecret.isEmpty()) {
            _state.value = ConnectUiState(accessError = "Enter both the Client ID and the Client Secret, or neither.")
            return
        }

        probeJob?.cancel()
        _state.value = ConnectUiState(testing = true)
        probeJob = viewModelScope.launch {
            // Saved before the probe, so the probe, the sign-in and the chat all go through Access with it.
            if (clientId.isNotEmpty()) {
                lookupJob?.cancel()
                access.set(gatewayUrl.host, AccessToken(clientId, clientSecret))
                _accessSaved.value = true
                accessClientId.clearText()
                accessClientSecret.clearText()
            }
            val result = probe.probe(gatewayUrl)
            _state.update { it.copy(testing = false, result = result) }
        }
    }

    /** Typing invalidates the previous result, and looks up whether the new address has a token saved. */
    fun onUrlEdited() {
        if (_state.value.result != null || _state.value.urlError != null || _state.value.accessError != null) {
            probeJob?.cancel()
            _state.value = ConnectUiState()
        }
        val host = GatewayUrl.parseOrNull(url.text.toString())?.host
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch { _accessSaved.value = host != null && access.get(host) != null }
    }
}
