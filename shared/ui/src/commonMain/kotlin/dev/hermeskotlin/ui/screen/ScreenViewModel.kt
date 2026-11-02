package dev.hermeskotlin.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.screen.DisplayLease
import dev.hermeskotlin.core.screen.DisplayStatus
import dev.hermeskotlin.core.screen.ScreenSession
import dev.hermeskotlin.core.screen.ScreenStage
import dev.hermeskotlin.core.screen.ScreenState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** What the pane shows: the gateway's own snapshot of a bot's screen, and this app's hold on it. */
data class ScreenUiState(
    /** The gateway's snapshot of the screen, or null before it answers. */
    val status: DisplayStatus? = null,
    /** Who holds the screen, as the gateway last said. */
    val lease: DisplayLease? = null,
    /** Where the viewer opens the screen, or null when nothing is open. */
    val streamUrl: String? = null,
    /** How far the screen has come. */
    val stage: ScreenStage = ScreenStage.Idle,
    /** False when the gateway is too old to have a screen at all. */
    val supported: Boolean = true,
    /** Why something didn't work, in the user's words. */
    val error: String? = null,
    /** Whether this app holds the screen, rather than the agent or another viewer. */
    val holding: Boolean = false,
)

/**
 * A bot's screen, as this app watches it.
 *
 * The view model owns the [ScreenSession] and nothing else. The gateway decides what reaches the screen, so
 * the viewer is told whether the user may drive it and the gateway has the last word; the app only says who
 * holds it. The session is made for the gateway and the profile in hand and put away when either changes,
 * so an answer about the last bot never shows for this one.
 */
class ScreenViewModel(
    private val connection: GatewayConnection,
) : ViewModel() {

    private val sessions = MutableStateFlow<ScreenSession?>(null)
    private var boundGateway: SavedGateway? = null
    private var boundProfile: String? = null
    private var core = ScreenState()

    private val _state = MutableStateFlow(ScreenUiState())
    val state: StateFlow<ScreenUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sessions.collectLatest { session ->
                session?.state?.collect { core = it; publish() }
            }
        }
    }

    /**
     * Points the pane at a bot's screen on [gateway], and watches it.
     *
     * A screen that is already running is watched at once; one that is stopped is left alone until the user
     * asks for it.
     */
    fun bind(gateway: SavedGateway, profile: String) {
        if (boundGateway != gateway || boundProfile != profile) {
            sessions.value?.close()
            sessions.value = null
            core = ScreenState()
            boundGateway = gateway
            boundProfile = profile
            publish()
        }
        val session = sessions.value ?: newSession(gateway, profile) ?: return
        viewModelScope.launch {
            session.refresh()
            // A screen that is already running is watched at once; one that is stopped waits for the user.
            if (session.state.value.status?.running == true && session.state.value.connection == null) {
                session.open()
            }
        }
    }

    /** Puts the screen away when the pane goes. The session stays, so coming back reopens it. */
    fun close() {
        sessions.value?.close()
    }

    /** Starts the bot's screen on the gateway and opens it. */
    fun start() {
        viewModelScope.launch { sessions.value?.start() }
    }

    /** Opens the screen again, with a new ticket: the old one has been used. */
    fun reopen() {
        sessions.value?.open()
    }

    /** Asks the gateway again about the screen, e.g. after the user installed it on the host. */
    fun refresh() {
        viewModelScope.launch { sessions.value?.refresh() }
    }

    /** Takes the screen over from the agent, so the user's taps and keys reach it. */
    fun takeOver() {
        viewModelScope.launch { sessions.value?.takeOver() }
    }

    /** Gives the screen back to the agent. */
    fun handBack() {
        viewModelScope.launch { sessions.value?.handBack() }
    }

    /** What the viewer says about the screen it was opened for. */
    fun viewerEvent(event: ScreenViewerEvent) {
        val session = sessions.value ?: return
        when (event) {
            ScreenViewerEvent.Connected -> session.viewerConnected()
            is ScreenViewerEvent.Failed -> session.viewerFailed(event.reason)
            ScreenViewerEvent.Closed -> session.viewerClosed()
        }
    }

    private fun newSession(gateway: SavedGateway, profile: String): ScreenSession? {
        val url = runCatching { gateway.gatewayUrl }.getOrNull() ?: run {
            core = core.copy(error = "This gateway's address isn't one the app can read.")
            publish()
            return null
        }
        val session = ScreenSession(profile, connection, url, viewModelScope)
        sessions.value = session
        return session
    }

    private fun publish() {
        _state.value = ScreenUiState(
            status = core.status,
            lease = core.lease,
            streamUrl = core.connection?.url,
            stage = core.stage,
            supported = core.supported,
            error = core.error,
            holding = core.holding,
        )
    }

    override fun onCleared() {
        sessions.value?.close()
        sessions.value = null
        super.onCleared()
    }
}
