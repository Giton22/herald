package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.capabilities.CapabilitiesApi
import dev.hermeskotlin.core.capabilities.McpServer
import dev.hermeskotlin.core.capabilities.McpTestResult
import dev.hermeskotlin.core.capabilities.Skill
import dev.hermeskotlin.core.capabilities.Toolset
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CapabilityTab(val label: String) { Skills("Skills"), Tools("Toolsets"), Mcp("MCP") }

/** One list's load state; [items] stays null until the first answer. */
data class Loadable<T>(val items: List<T>? = null, val loading: Boolean = false, val error: String? = null)

data class CapabilitiesUiState(
    val tab: CapabilityTab = CapabilityTab.Skills,
    val skills: Loadable<Skill> = Loadable(),
    val toolsets: Loadable<Toolset> = Loadable(),
    val servers: Loadable<McpServer> = Loadable(),
    /** Connection tests by server name; a null value is a test in flight. */
    val tests: Map<String, McpTestResult?> = emptyMap(),
    val message: String? = null,
    val sessionExpired: Boolean = false,
)

/**
 * The profile's skills, toolsets and MCP servers, each with its switch. A switch flips straight
 * away and flips back if the gateway refuses; either way the change applies from the next chat.
 */
class CapabilitiesViewModel(private val api: CapabilitiesApi) : ViewModel() {

    val skillQuery = TextFieldState()

    private val _state = MutableStateFlow(CapabilitiesUiState())
    val state: StateFlow<CapabilitiesUiState> = _state.asStateFlow()

    private var gateway: SavedGateway? = null
    private var profile: String? = null

    /** Parent of every call for the bound gateway and profile; cancelled on a switch so a late answer can't land in the next profile's lists. */
    private var binding: Job = newBinding()

    fun bind(gateway: SavedGateway, profile: String?) {
        if (this.gateway == gateway && this.profile == profile) return
        this.gateway = gateway
        this.profile = profile
        binding.cancel()
        binding = newBinding()
        _state.value = CapabilitiesUiState(tab = _state.value.tab)
        refresh()
    }

    fun selectTab(tab: CapabilityTab) {
        _state.update { it.copy(tab = tab) }
        if (current(tab).items == null) refresh()
    }

    /** Refetches the open tab. */
    fun refresh() {
        val url = gateway?.gatewayUrl ?: return
        val profile = profile
        when (_state.value.tab) {
            CapabilityTab.Skills -> load({ api.skills(url, profile) }) { s, l -> s.copy(skills = l) }
            CapabilityTab.Tools -> load({ api.toolsets(url, profile) }) { s, l -> s.copy(toolsets = l) }
            CapabilityTab.Mcp -> load({ api.mcpServers(url, profile) }) { s, l -> s.copy(servers = l) }
        }
    }

    fun setSkillEnabled(skill: Skill, enabled: Boolean) = toggle(
        apply = { s, on -> s.copy(skills = s.skills.mapItems { if (it.name == skill.name) it.copy(enabled = on) else it }) },
        enabled = enabled,
        label = skill.name,
    ) { url -> api.setSkillEnabled(url, profile, skill.name, enabled) }

    fun setToolsetEnabled(toolset: Toolset, enabled: Boolean) = toggle(
        apply = { s, on -> s.copy(toolsets = s.toolsets.mapItems { if (it.name == toolset.name) it.copy(enabled = on) else it }) },
        enabled = enabled,
        label = toolset.label,
    ) { url -> api.setToolsetEnabled(url, profile, toolset.name, enabled) }

    fun setServerEnabled(server: McpServer, enabled: Boolean) = toggle(
        apply = { s, on -> s.copy(servers = s.servers.mapItems { if (it.name == server.name) it.copy(enabled = on) else it }) },
        enabled = enabled,
        label = server.name,
    ) { url -> api.setMcpServerEnabled(url, profile, server.name, enabled) }

    fun testServer(server: McpServer) {
        val url = gateway?.gatewayUrl ?: return
        _state.update { it.copy(tests = it.tests + (server.name to null)) }
        viewModelScope.launch(binding) {
            val result = api.testMcpServer(url, profile, server.name)
            val outcome = when (result) {
                is ApiResult.Success -> result.value
                else -> McpTestResult(ok = false, error = result.errorMessage)
            }
            _state.update { it.copy(tests = it.tests + (server.name to outcome), sessionExpired = result == ApiResult.SessionExpired) }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun newBinding(): Job = SupervisorJob(viewModelScope.coroutineContext[Job])

    private fun current(tab: CapabilityTab): Loadable<*> = when (tab) {
        CapabilityTab.Skills -> _state.value.skills
        CapabilityTab.Tools -> _state.value.toolsets
        CapabilityTab.Mcp -> _state.value.servers
    }

    private fun <T> load(
        call: suspend () -> ApiResult<List<T>>,
        put: (CapabilitiesUiState, Loadable<T>) -> CapabilitiesUiState,
    ) {
        @Suppress("UNCHECKED_CAST")
        val before = current(_state.value.tab) as Loadable<T>
        _state.update { put(it, before.copy(loading = true)) }
        viewModelScope.launch(binding) {
            val result = call()
            _state.update { state ->
                when (result) {
                    is ApiResult.Success -> put(state, Loadable(items = result.value))
                    else -> put(state, before.copy(loading = false, error = result.errorMessage))
                        .copy(sessionExpired = result == ApiResult.SessionExpired)
                }
            }
        }
    }

    private fun toggle(
        apply: (CapabilitiesUiState, Boolean) -> CapabilitiesUiState,
        enabled: Boolean,
        label: String,
        call: suspend (GatewayUrl) -> ApiResult<Unit>,
    ) {
        val url = gateway?.gatewayUrl ?: return
        _state.update { apply(it, enabled) }
        viewModelScope.launch(binding) {
            val result = call(url)
            if (result !is ApiResult.Success) {
                _state.update {
                    apply(it, !enabled).copy(
                        message = listOfNotNull("Couldn't ${if (enabled) "turn on" else "turn off"} $label", result.errorMessage).joinToString(": "),
                        sessionExpired = result == ApiResult.SessionExpired,
                    )
                }
            }
        }
    }
}

private fun <T> Loadable<T>.mapItems(transform: (T) -> T): Loadable<T> = copy(items = items?.map(transform))
