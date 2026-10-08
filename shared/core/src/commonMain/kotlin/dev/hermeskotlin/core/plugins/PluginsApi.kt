package dev.hermeskotlin.core.plugins

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The page a dashboard plugin adds, as its manifest declares it (`tab`). */
@Serializable
data class PluginTab(
    /** Where the page lives on the gateway, e.g. `/kanban`. */
    val path: String,
    /** Where the dashboard places it (`end`, `after:skills`, …); the app lists flat, in its order. */
    val position: String? = null,
    /** Hidden tabs are bare routes the dashboard keeps for itself — machinery, not pages to read. */
    val hidden: Boolean = false,
)

/**
 * One plugin the gateway serves to its dashboard, from `GET /api/dashboard/plugins`: what it is called,
 * what it does, and where its page lives. Only plugins with a page appear meaningfully: one without a
 * `tab` has nothing to open (a command, a widget), and a hidden one is the dashboard's own plumbing.
 */
@Serializable
data class DashboardPlugin(
    val name: String,
    val label: String = name,
    val description: String = "",
    val version: String? = null,
    /** `user` (installed on this gateway) or `bundled` (ships with Hermes). */
    val source: String = "user",
    val tab: PluginTab? = null,
) {
    /** The page's path when there is one a person would open; null for the rest. */
    val openPath: String? get() = tab?.takeUnless { it.hidden }?.path?.takeIf { it.isNotBlank() }
}

/**
 * The gateway's dashboard plugins, through the same routes the dashboard's own pages use — the pages
 * themselves are opened in-app at the gateway's origin ([openPath]).
 */
class PluginsApi(private val client: HttpClient) {

    suspend fun list(url: GatewayUrl, profile: String?): ApiResult<List<DashboardPlugin>> = apiCall {
        client.get(url.resolve("api/dashboard/plugins")) {
            profile?.let { parameter("profile", it) }
        }
    }.map { response -> lenientJson.decodeFromString<List<DashboardPlugin>>(response.bodyAsText()) }

    private companion object {
        /** The gateway writes `null` for what a plugin doesn't have (a version, a description); that reads as the field's default. */
        val lenientJson = Json(HermesJson) { coerceInputValues = true }
    }
}
