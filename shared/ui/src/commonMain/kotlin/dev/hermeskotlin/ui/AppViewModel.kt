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
    data class Sessions(val gateway: SavedGateway) : Route

    /** A stored session opened from [Sessions]; Back returns there. */
    data class Transcript(val gateway: SavedGateway, val sessionId: String, val title: String) : Route
}

/** Top-level flow: pick gateway → sign in → sessions → transcript. Owns the gateway connection lifecycle. */
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
                auth.hasStoredSession(saved.gatewayUrl) -> Route.Sessions(saved).also { connection.start(saved.gatewayUrl) }
                else -> Route.SignIn(saved)
            }
        }
        viewModelScope.launch {
            connection.state.collect { state ->
                if (state is ConnectionState.SessionExpired) onSessionExpired()
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
        _route.value = Route.Sessions(gateway)
    }

    fun openSession(sessionId: String, title: String) {
        val gateway = signedInGateway() ?: return
        _route.value = Route.Transcript(gateway, sessionId, title)
    }

    /** The dashboard rejected our cookies (socket or REST): back to sign-in with a notice. */
    fun onSessionExpired() {
        val gateway = signedInGateway() ?: return
        connection.stop()
        _route.value = Route.SignIn(gateway, notice = "Your session expired. Sign in again.")
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
    fun back(): Boolean = when (val r = _route.value) {
        is Route.Transcript -> {
            _route.value = Route.Sessions(r.gateway)
            true
        }
        is Route.SignIn -> {
            changeGateway()
            true
        }
        else -> false
    }

    private fun signedInGateway(): SavedGateway? = when (val r = _route.value) {
        is Route.Sessions -> r.gateway
        is Route.Transcript -> r.gateway
        else -> null
    }

    private fun currentGateway(): SavedGateway? = (_route.value as? Route.SignIn)?.gateway ?: signedInGateway()
}
