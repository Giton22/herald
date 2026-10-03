package dev.hermeskotlin.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.AuthResult
import dev.hermeskotlin.core.auth.AuthUser
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.SavedGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Temporary home: shows the live connection. Sessions and chat replace it next. */
class HomeViewModel(
    private val auth: AuthApi,
    private val connection: GatewayConnection,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connection.state

    private val _user = MutableStateFlow<AuthUser?>(null)
    val user: StateFlow<AuthUser?> = _user.asStateFlow()

    fun load(gateway: SavedGateway) {
        viewModelScope.launch {
            (auth.me(gateway.gatewayUrl) as? AuthResult.Success)?.let { _user.value = it.value }
        }
    }

    fun retry() = connection.retry()
}
