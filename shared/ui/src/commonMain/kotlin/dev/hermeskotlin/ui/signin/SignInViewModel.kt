package dev.hermeskotlin.ui.signin

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SignInUiState(
    val signingIn: Boolean = false,
    val error: String? = null,
    val signedIn: Boolean = false,
)

class SignInViewModel(private val auth: AuthApi) : ViewModel() {

    val username = TextFieldState()
    val password = TextFieldState()

    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    fun signIn(gateway: SavedGateway) {
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            _state.value = SignInUiState(error = "Enter your username and password.")
            return
        }
        if (_state.value.signingIn) return

        _state.value = SignInUiState(signingIn = true)
        viewModelScope.launch {
            val result = auth.signIn(gateway.gatewayUrl, gateway.provider, user, pass)
            _state.value = when (result) {
                is ApiResult.Success -> {
                    password.clearText()
                    SignInUiState(signedIn = true)
                }
                ApiResult.InvalidCredentials -> SignInUiState(error = "Wrong username or password.")
                ApiResult.RateLimited -> SignInUiState(error = "Too many attempts. Wait a moment and try again.")
                is ApiResult.Unavailable -> SignInUiState(error = "Couldn't reach the gateway: ${result.message}")
                is ApiResult.Failed -> SignInUiState(error = result.message)
                ApiResult.SessionExpired -> SignInUiState(error = "Sign-in was rejected.")
            }
        }
    }

    fun consumeSignedIn() {
        _state.value = _state.value.copy(signedIn = false)
    }
}
