package dev.hermeskotlin.core.plugins

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.rpc.RpcException
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The reply the Next Prompt plugin expects the user to send next in a chat. */
@Serializable
data class NextPrompt(
    val text: String,
    /** The stored session it belongs to. */
    @SerialName("session_id") val sessionId: String = "",
    /** When the gateway stored it (epoch seconds). */
    val timestamp: Double = 0.0,
)

/** Why there is no suggestion for the last turn: a coarse kind (`auth`, `rate_limit`, `timeout`, `other`). */
@Serializable
data class NextPromptFailure(val kind: String = "other")

/**
 * One chat's state on the gateway: a suggestion, the reason the last one couldn't be made, or neither
 * (none made yet, the model had nothing to suggest, or the user moved on).
 */
@Serializable
data class NextPromptState(val suggestion: NextPrompt? = null, val error: NextPromptFailure? = null)

/**
 * The [Next Prompt](https://github.com/soymarketing/hermes-plugin-next-prompt) plugin's routes. After each
 * turn the plugin's gateway hook asks a model for the reply the user would most likely send next and keeps
 * it per stored session, until it's used, dismissed, or the user sends their own message.
 */
class NextPromptApi(
    private val client: HttpClient,
    private val plugins: PluginsApi,
    /** The dashboard socket, which installs plugins; without it, [install] can't. */
    private val connection: GatewayConnection? = null,
) {

    /**
     * Whether the gateway serves the plugin to [profile]. Its tab is hidden, so the Plugins page leaves it out,
     * but the gateway still lists it.
     */
    suspend fun installed(url: GatewayUrl, profile: String?): ApiResult<Boolean> = plugin(url, profile).map { it != null }

    /** The plugin as the gateway lists it for [profile] (its version, say), or null when it isn't there. */
    suspend fun plugin(url: GatewayUrl, profile: String?): ApiResult<DashboardPlugin?> =
        plugins.list(url, profile).map { list -> list.firstOrNull { it.name == PLUGIN } }

    suspend fun state(url: GatewayUrl, storedSessionId: String, profile: String?): ApiResult<NextPromptState> = apiCall {
        client.get(url.resolve("api/plugins/$PLUGIN/suggestion")) {
            parameter("session_id", storedSessionId)
            profile?.let { parameter("profile", it) }
        }
    }.map { response -> lenientJson.decodeFromString<NextPromptState>(response.bodyAsText()) }

    /**
     * Puts the plugin on the gateway for [profile]: switched on when a copy is there but off, else installed from
     * its repository at [PLUGIN_REF], the commit this Herald was reviewed with, the way herald-push is (live
     * without a restart). Then the gateway's plugin list is read afresh, so [installed] finds it. Throws with
     * the gateway's reason when it can't.
     */
    suspend fun install(url: GatewayUrl, profile: String?) {
        val rpc = (connection?.state?.value as? ConnectionState.Connected)?.client
            ?: throw RpcException(0, "Not connected to the gateway.")
        fun JsonObjectBuilder.scoped() = profile?.let { put("profile", it) }
        val switchedOn = try {
            val reply = rpc.request(
                "plugins.manage",
                buildJsonObject {
                    put("action", "toggle")
                    put("name", PLUGIN)
                    put("enable", true)
                    scoped()
                },
            )
            ((reply as? JsonObject)?.get("ok") as? JsonPrimitive)?.booleanOrNull == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Refused, or no answer in time: installing still can.
            false
        }
        // A toggle may say yes for a name it doesn't have; only the list tells it's really there.
        if (!switchedOn || !rescanned(url, profile)) {
            rpc.request(
                "plugins.manage",
                buildJsonObject {
                    put("action", "install")
                    put("identifier", PLUGIN_REPO)
                    // A reviewed commit, never whatever the branch holds today: this code runs on the gateway.
                    put("ref", PLUGIN_REF)
                    put("enable", true)
                    scoped()
                },
                timeoutMs = INSTALL_TIMEOUT_MS,
            )
            rescanned(url, profile)
        }
    }

    /** Has the dashboard read its plugin list afresh (it keeps it until asked); true when it then lists this one. */
    private suspend fun rescanned(url: GatewayUrl, profile: String?): Boolean {
        apiCall {
            client.get(url.resolve("api/dashboard/plugins/rescan")) {
                profile?.let { parameter("profile", it) }
            }
        }
        return (installed(url, profile) as? ApiResult.Success)?.value == true
    }

    /**
     * Whether the gateway answers the plugin's routes for [profile]. One older than Hermes's hot mount (October
     * 2026) lists a plugin installed while it runs, but only serves its routes after a restart. Only a 404 says
     * no: a gateway that can't be reached isn't a missing route.
     */
    suspend fun served(url: GatewayUrl, profile: String?): Boolean =
        (state(url, "", profile) as? ApiResult.Failed)?.status != 404

    /** Drops the chat's suggestion on the gateway, so no other client shows it either. */
    suspend fun dismiss(url: GatewayUrl, storedSessionId: String, profile: String?): ApiResult<Unit> = apiCall {
        client.post(url.resolve("api/plugins/$PLUGIN/dismiss")) {
            parameter("session_id", storedSessionId)
            profile?.let { parameter("profile", it) }
        }
    }.map { }

    companion object {
        const val PLUGIN = "next-prompt"
        const val PLUGIN_REPO = "https://github.com/soymarketing/hermes-plugin-next-prompt"

        /** v0.3.1: the first that serves suggestions to every profile of a gateway. Read before pinning. */
        const val PLUGIN_REF = "c200200d6678dc6b093bab03c2e00a4f5a5043ed"
        const val PLUGIN_VERSION = "0.3.1"
        private const val INSTALL_TIMEOUT_MS = 180_000L
        private val lenientJson = Json(HermesJson) { coerceInputValues = true }
    }
}
