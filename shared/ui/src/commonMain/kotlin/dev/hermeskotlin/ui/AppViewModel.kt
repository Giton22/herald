package dev.hermeskotlin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface Route {
    data object Loading : Route
    data object Connect : Route
    data class SignIn(val gateway: SavedGateway, val notice: String? = null) : Route
    data class Home(val gateway: SavedGateway) : Route
}

/** Top-level flow: pick gateway → sign in → connected home. Owns the gateway connection lifecycle. */
class AppViewModel(
    private val gateways: GatewayRepository,
    private val auth: AuthApi,
    private val connection: GatewayConnection,
) : ViewModel() {

    private val _route = MutableStateFlow<Route>(Route.Loading)
    val route: StateFlow<Route> = _route.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = gateways.current()
            _route.value = when {
                saved == null -> Route.Connect
                auth.hasStoredSession(saved.gatewayUrl) -> Route.Home(saved).also { connection.start(saved.gatewayUrl) }
                else -> Route.SignIn(saved)
            }
        }
        viewModelScope.launch {
            connection.state.collect { state ->
                val home = _route.value as? Route.Home ?: return@collect
                if (state is ConnectionState.SessionExpired) {
                    connection.stop()
                    _route.value = Route.SignIn(home.gateway, notice = "Your session expired. Sign in again.")
                }
            }
        }
    }

    fun onGatewayChosen(gateway: SavedGateway) {
        viewModelScope.launch {
            gateways.save(gateway)
            _route.value = Route.SignIn(gateway)
        }
    }

    fun onSignedIn(gateway: SavedGateway) {
        connection.start(gateway.gatewayUrl)
        _route.value = Route.Home(gateway)
    }

    fun signOut() {
        val gateway = currentGateway() ?: return
        connection.stop()
        viewModelScope.launch {
            auth.signOut(gateway.gatewayUrl)
            _route.value = Route.SignIn(gateway)
        }
    }

    fun changeGateway() {
        val gateway = currentGateway()
        connection.stop()
        viewModelScope.launch {
            gateway?.let { auth.signOut(it.gatewayUrl) }
            gateways.clear()
            _route.value = Route.Connect
        }
    }

    /** System back. Returns false when there is nowhere to go back to (let the platform close the app). */
    fun back(): Boolean = when (_route.value) {
        is Route.SignIn -> {
            changeGateway()
            true
        }
        else -> false
    }

    private fun currentGateway(): SavedGateway? = when (val r = _route.value) {
        is Route.SignIn -> r.gateway
        is Route.Home -> r.gateway
        else -> null
    }
}
