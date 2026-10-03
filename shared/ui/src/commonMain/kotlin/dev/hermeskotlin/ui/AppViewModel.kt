package dev.hermeskotlin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.ui.chat.ChatTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface Route {
    data object Loading : Route
    data object Connect : Route
    data class SignIn(val gateway: SavedGateway, val notice: String? = null) : Route

    /** The signed-in home: one chat (stored, or new when `target.storedSessionId` is null) with the sessions sidebar beside it. */
    data class Chat(val target: ChatTarget) : Route {
        val gateway: SavedGateway get() = target.gateway
    }
}

/** Top-level flow: pick gateway → sign in → chat, reopening where the user left off. Owns the gateway connection lifecycle. */
class AppViewModel(
    private val gateways: GatewayRepository,
    private val auth: AuthApi,
    private val connection: GatewayConnection,
    private val lastChats: LastChatStore,
) : ViewModel() {

    private val _route = MutableStateFlow<Route>(Route.Loading)
    val route: StateFlow<Route> = _route.asStateFlow()

    private var newChatCount = 0L

    init {
        viewModelScope.launch {
            val saved = gateways.current()
            _route.value = when {
                saved == null -> Route.Connect
                auth.hasStoredSession(saved.gatewayUrl) -> home(saved).also { connection.start(saved.gatewayUrl) }
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
        viewModelScope.launch { _route.value = home(gateway) }
    }

    fun openSession(sessionId: String, title: String) {
        val gateway = signedInGateway() ?: return
        _route.value = Route.Chat(ChatTarget(gateway, sessionId, title))
    }

    fun newChat() {
        val gateway = signedInGateway() ?: return
        _route.value = Route.Chat(newChatTarget(gateway))
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
    fun back(): Boolean = when (_route.value) {
        is Route.SignIn -> {
            changeGateway()
            true
        }
        else -> false
    }

    /** The last chat the user had open on [gateway], or a fresh one. */
    private suspend fun home(gateway: SavedGateway): Route.Chat {
        val last = lastChats.get(gateway.gatewayUrl)
        return Route.Chat(last?.let { ChatTarget(gateway, it.sessionId, it.title) } ?: newChatTarget(gateway))
    }

    // The nonce makes every new chat a fresh target, even right after another empty one.
    private fun newChatTarget(gateway: SavedGateway) = ChatTarget(gateway, storedSessionId = null, title = null, nonce = ++newChatCount)

    private fun signedInGateway(): SavedGateway? = (_route.value as? Route.Chat)?.gateway

    private fun currentGateway(): SavedGateway? = (_route.value as? Route.SignIn)?.gateway ?: signedInGateway()
}
