package dev.hermeskotlin.core.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Subset of the public `GET /api/status` probe (hermes_cli/web_routers/status.py). */
@Serializable
data class GatewayStatus(
    val version: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("gateway_running") val gatewayRunning: Boolean = false,
    @SerialName("gateway_state") val gatewayState: String? = null,
    @SerialName("active_agents") val activeAgents: Int = 0,
    @SerialName("active_sessions") val activeSessions: Int = 0,
    @SerialName("auth_required") val authRequired: Boolean = false,
    @SerialName("auth_providers") val authProviders: List<String> = emptyList(),
    @SerialName("auth_flows") val authFlows: List<String> = emptyList(),
    val profiles: List<String> = emptyList(),
    val overall: String? = null,
) {
    /** The bundled username/password provider is registered. */
    val supportsPasswordLogin: Boolean get() = "basic" in authProviders

    /** RFC 8252 native PKCE sign-in is advertised. */
    val supportsNativeSignIn: Boolean get() = "native_pkce" in authFlows
}
