package dev.hermeskotlin.ui.signin

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.BrowserSignIn
import dev.hermeskotlin.core.gateway.GatewayProbe
import dev.hermeskotlin.core.gateway.ProbeResult
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.ApiResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The ways in the gateway offers. Until its status answers, the password form shows, as before browser sign-in. */
data class SignInMethods(val password: Boolean = true, val browser: Boolean = false)

data class SignInUiState(
    val signingIn: Boolean = false,
    /** The browser is open on the gateway's sign-in page and the app waits for it to come back. */
    val waitingForBrowser: Boolean = false,
    val error: String? = null,
    val signedIn: Boolean = false,
    val methods: SignInMethods = SignInMethods(),
)

class SignInViewModel(
    private val auth: AuthApi,
    private val probe: GatewayProbe,
    private val browserSignIn: BrowserSignIn,
) : ViewModel() {

    val username = TextFieldState()
    val password = TextFieldState()

    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    private var loadedUrl: String? = null
    private var browserJob: Job? = null

    /** Asks the gateway which sign-ins it offers. */
    fun load(gateway: SavedGateway) {
        if (loadedUrl == gateway.url) return
        loadedUrl = gateway.url
        viewModelScope.launch {
            val status = (probe.probe(gateway.gatewayUrl) as? ProbeResult.Reachable)?.status ?: return@launch
            if (!status.supportsPasswordLogin && !status.supportsNativeSignIn) return@launch
            _state.update { it.copy(methods = SignInMethods(password = status.supportsPasswordLogin, browser = status.supportsNativeSignIn)) }
        }
    }

    fun signIn(gateway: SavedGateway) {
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            _state.update { it.copy(error = "Enter your username and password.") }
            return
        }
        if (_state.value.signingIn) return

        _state.update { it.copy(signingIn = true, error = null) }
        viewModelScope.launch {
            val result = auth.signIn(gateway.gatewayUrl, gateway.provider, user, pass)
            if (result is ApiResult.Success) password.clearText()
            finish(result, wrongCredentials = "Wrong username or password.")
        }
    }

    /** Opens the gateway's sign-in page through [openBrowser] and waits for it to send the browser back. */
    fun signInWithBrowser(gateway: SavedGateway, openBrowser: (String) -> Unit) {
        if (_state.value.signingIn) return
        _state.update { it.copy(signingIn = true, waitingForBrowser = true, error = null) }
        browserJob = viewModelScope.launch {
            // The gateway picks the provider, or asks in the page when it has several.
            finish(browserSignIn.signIn(gateway.gatewayUrl, provider = null, openBrowser), wrongCredentials = "Sign-in was rejected.")
        }
    }

    /** The user closed the browser without finishing: stop waiting for it. */
    fun cancelBrowser() {
        browserJob?.cancel()
        browserJob = null
        _state.update { it.copy(signingIn = false, waitingForBrowser = false) }
    }

    private fun finish(result: ApiResult<Unit>, wrongCredentials: String) {
        _state.update {
            val done = it.copy(signingIn = false, waitingForBrowser = false)
            when (result) {
                is ApiResult.Success -> done.copy(signedIn = true)
                ApiResult.InvalidCredentials -> done.copy(error = wrongCredentials)
                ApiResult.RateLimited -> done.copy(error = "Too many attempts. Wait a moment and try again.")
                is ApiResult.Unavailable -> done.copy(error = "Couldn't reach the gateway: ${result.message}")
                is ApiResult.Failed -> done.copy(error = result.message)
                ApiResult.SessionExpired -> done.copy(error = "Sign-in was rejected.")
            }
        }
    }

    fun consumeSignedIn() {
        _state.update { it.copy(signedIn = false) }
    }
}
