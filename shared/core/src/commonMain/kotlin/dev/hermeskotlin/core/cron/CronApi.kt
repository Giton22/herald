package dev.hermeskotlin.core.cron

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import dev.hermeskotlin.core.sessions.SessionSummary
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A scheduled job as `GET /api/cron/jobs` returns it (cron/jobs.py, read-normalized). Times are
 * ISO-8601 strings with an offset. Each run is an ordinary session with `source = cron`.
 */
@Serializable
data class CronJob(
    val id: String,
    val name: String = "",
    val prompt: String = "",
    @SerialName("schedule_display") val scheduleDisplay: String = "",
    /** `scheduled`, `paused`, `completed` or `error`, derived from `enabled` by the server. */
    val state: String = "scheduled",
    val enabled: Boolean = true,
    @SerialName("next_run_at") val nextRunAt: String? = null,
    @SerialName("last_run_at") val lastRunAt: String? = null,
    @SerialName("last_status") val lastStatus: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    val model: String? = null,
) {
    val displayName: String get() = name.ifBlank { prompt.lineSequence().firstOrNull()?.take(50).orEmpty() }.ifBlank { id }

    val paused: Boolean get() = state == "paused"

    /** Next run in epoch seconds, or null when none is planned (paused, finished, or unparseable). */
    val nextRunEpochSeconds: Double? get() = nextRunAt.epochSeconds()

    val lastRunEpochSeconds: Double? get() = lastRunAt.epochSeconds()
}

@Serializable
private data class RunsResponse(val runs: List<SessionSummary> = emptyList())

/** Dashboard cron surface (hermes_cli/web_routers/cron.py), the same one the Desktop's Automations view uses. */
class CronApi(private val client: HttpClient) {

    /** Every job across profiles, in the gateway's order. */
    suspend fun jobs(url: GatewayUrl): ApiResult<List<CronJob>> = apiCall {
        client.get(url.resolve("api/cron/jobs")) { parameter("profile", "all") }
    }.map { it.body<List<CronJob>>() }

    /** The sessions a job's runs produced, newest first. */
    suspend fun runs(url: GatewayUrl, jobId: String, limit: Int = 50): ApiResult<List<SessionSummary>> = apiCall {
        client.get(url.resolve("api/cron/jobs/${jobId.encodeURLPathPart()}/runs")) {
            parameter("limit", limit.coerceIn(1, 100))
        }
    }.map { it.body<RunsResponse>().runs }

    suspend fun pause(url: GatewayUrl, jobId: String): ApiResult<CronJob> = action(url, jobId, "pause")

    suspend fun resume(url: GatewayUrl, jobId: String): ApiResult<CronJob> = action(url, jobId, "resume")

    /** Runs the job now, outside its schedule. A 409 means a run is already in progress. */
    suspend fun trigger(url: GatewayUrl, jobId: String): ApiResult<CronJob> = action(url, jobId, "trigger")

    private suspend fun action(url: GatewayUrl, jobId: String, verb: String): ApiResult<CronJob> = apiCall {
        client.post(url.resolve("api/cron/jobs/${jobId.encodeURLPathPart()}/$verb"))
    }.map { it.body<CronJob>() }
}

@OptIn(ExperimentalTime::class)
private fun String?.epochSeconds(): Double? = this?.let {
    runCatching { Instant.parse(it) }.getOrNull()?.let { instant -> instant.toEpochMilliseconds() / 1000.0 }
}
