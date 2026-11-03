package dev.hermeskotlin.core.capabilities

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class ActionStart(val name: String = "", val ok: Boolean = true, val error: String? = null)

@Serializable
private data class ActionStatus(
    val running: Boolean = false,
    @SerialName("exit_code") val exitCode: Int? = null,
    val lines: List<String> = emptyList(),
)

@Serializable
private data class OfficialResponse(val skills: List<HubSkill> = emptyList())

/** The skills hub through the dashboard routes: browse, search, preview and scan. */
class SkillsHubApi(
    private val client: HttpClient,
    private val pollIntervalMs: Long = 1_000,
    /** How long, in waits between status reads, an action is polled before Herald stops waiting; the gateway keeps it going. */
    private val actionTimeoutMs: Long = 10 * 60_000,
) {

    suspend fun sources(url: GatewayUrl, profile: String?): ApiResult<HubSources> = apiCall {
        client.get(url.resolve("api/skills/hub/sources")) {
            profile(profile)
            waitForHub()
        }
    }.map { it.decode<HubSources>() }

    suspend fun official(url: GatewayUrl, profile: String?): ApiResult<List<HubSkill>> = apiCall {
        client.get(url.resolve("api/skills/hub/official")) {
            profile(profile)
            waitForHub()
        }
    }.map { it.decode<OfficialResponse>().skills }

    suspend fun search(url: GatewayUrl, profile: String?, query: String, limit: Int = 20): ApiResult<HubSearch> = apiCall {
        client.get(url.resolve("api/skills/hub/search")) {
            profile(profile)
            parameter("q", query)
            parameter("source", "all")
            parameter("limit", limit)
            waitForHub()
        }
    }.map { it.decode<HubSearch>() }

    suspend fun preview(url: GatewayUrl, profile: String?, identifier: String): ApiResult<HubPreview> = apiCall {
        client.get(url.resolve("api/skills/hub/preview")) {
            profile(profile)
            parameter("identifier", identifier)
            waitForHub()
        }
    }.map { it.decode<HubPreview>() }

    suspend fun scan(url: GatewayUrl, profile: String?, identifier: String): ApiResult<HubScan> = apiCall {
        client.get(url.resolve("api/skills/hub/scan")) {
            profile(profile)
            parameter("identifier", identifier)
            waitForHub()
        }
    }.map { it.decode<HubScan>() }

    suspend fun install(url: GatewayUrl, profile: String?, identifier: String): ApiResult<HubActionResult> =
        runAction(url, profile, "api/skills/hub/install", "install", buildJsonObject { put("identifier", identifier) })

    suspend fun uninstall(url: GatewayUrl, profile: String?, name: String): ApiResult<HubActionResult> =
        runAction(url, profile, "api/skills/hub/uninstall", "removal", buildJsonObject { put("name", name) })

    suspend fun updateAll(url: GatewayUrl, profile: String?): ApiResult<HubActionResult> =
        runAction(url, profile, "api/skills/hub/update", "update", buildJsonObject { })

    /** Starts a background action on the gateway, then polls its status until the process ends; [noun] names it in a lost-track message. */
    private suspend fun runAction(url: GatewayUrl, profile: String?, route: String, noun: String, fields: JsonObject): ApiResult<HubActionResult> {
        val body = buildJsonObject {
            fields.forEach { (k, v) -> put(k, v) }
            profile?.let { put("profile", it) }
        }
        val started = apiCall {
            client.post(url.resolve(route)) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }.map { it.decode<ActionStart>() }
        val start = when (started) {
            is ApiResult.Success -> started.value
            else -> return started.passThrough()
        }
        if (!start.ok) return ApiResult.Success(HubActionResult.Failed(listOfNotNull(start.error)))
        if (start.name.isBlank()) return ApiResult.Success(HubActionResult.Failed(listOf("The gateway didn't name the action.")))

        var outages = 0
        // Time spent between reads; past actionTimeoutMs Herald stops waiting on an action that never ends.
        var waited = 0L
        while (true) {
            currentCoroutineContext().ensureActive() // a zero interval never suspends in delay
            if (pollIntervalMs > 0 && waited >= actionTimeoutMs) {
                return ApiResult.Success(HubActionResult.StillRunning(listOf("The $noun is still running on the gateway. Check back later.")))
            }
            val read = apiCall {
                client.get(url.resolve("api/actions/${start.name.encodeURLPathPart()}/status")) {
                    profile(profile)
                    parameter("lines", 200)
                }
            }.map { it.decode<ActionStatus>() }
            if (read is ApiResult.Unavailable && ++outages <= STATUS_RETRIES) {
                delay(pollIntervalMs)
                waited += pollIntervalMs
                continue
            }
            val status = when (read) {
                is ApiResult.Success -> read.value.also { outages = 0 }
                is ApiResult.Failed -> return when (read.status) {
                    404 -> ApiResult.Success(HubActionResult.Failed(listOf("Lost track of the $noun on the gateway.")))
                    else -> read
                }
                else -> return read.passThrough()
            }
            if (!status.running) {
                val result = when {
                    status.exitCode == 0 -> HubActionResult.Done(status.lines)
                    else -> scanBlock(status.lines) ?: HubActionResult.Failed(status.lines.takeLast(3))
                }
                return ApiResult.Success(result)
            }
            delay(pollIntervalMs)
            waited += pollIntervalMs
        }
    }

    /** A non-success result carries no value, so it can change its type freely. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> ApiResult<*>.passThrough(): ApiResult<T> = this as ApiResult<T>

    private fun HttpRequestBuilder.profile(profile: String?) {
        profile?.let { parameter("profile", it) }
    }

    /**
     * A read may be silent for as long as it may take: a preview or scan fetches the skill first, and the
     * engine's own read timeout (10 s on OkHttp) would cut it off long before.
     */
    private fun HttpRequestBuilder.waitForHub() = timeout {
        requestTimeoutMillis = HUB_REQUEST_TIMEOUT_MS
        socketTimeoutMillis = HUB_REQUEST_TIMEOUT_MS
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T = lenientJson.decodeFromString<T>(bodyAsText())

    private companion object {
        /** Search waits up to 30 s on slow sources, longer than the client's default timeout. */
        const val HUB_REQUEST_TIMEOUT_MS = 45_000L

        /** Status reads in a row that may fail as unavailable before polling gives up; the gateway keeps going through a Wi-Fi to LTE switch. */
        const val STATUS_RETRIES = 2

        /** The gateway writes `null` for what it doesn't have (a preview's tags, files); that reads as the field's default. */
        val lenientJson = Json(HermesJson) { coerceInputValues = true }
    }
}
