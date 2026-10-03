package dev.hermeskotlin.ui.journey

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.journey.JourneyApi
import dev.hermeskotlin.core.journey.JourneyGraph
import dev.hermeskotlin.core.journey.JourneyNode
import dev.hermeskotlin.core.journey.JourneyNodeDetail
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JourneyState(
    val graph: JourneyGraph? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** `/journey`: what the profile's agent has learned, newest first, read from the gateway on demand. */
class JourneyController(private val api: JourneyApi, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(JourneyState())
    val state: StateFlow<JourneyState> = _state.asStateFlow()

    private var gateway: GatewayUrl? = null
    private var profile: String? = null

    fun load(gateway: GatewayUrl, profile: String?) {
        if (gateway != this.gateway || profile != this.profile) _state.value = JourneyState()
        this.gateway = gateway
        this.profile = profile
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            when (val result = api.graph(gateway, profile)) {
                is ApiResult.Success -> _state.update { it.copy(graph = result.value, loading = false) }
                else -> _state.update { it.copy(loading = false, error = result.errorMessage ?: "Couldn't load the journey.") }
            }
        }
    }

    suspend fun detail(node: JourneyNode): JourneyNodeDetail? {
        val gateway = gateway ?: return null
        return (api.node(gateway, node.id, profile) as? ApiResult.Success)?.value
    }
}
