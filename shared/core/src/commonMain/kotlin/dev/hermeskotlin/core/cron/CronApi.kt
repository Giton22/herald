package dev.hermeskotlin.core.cron

import dev.hermeskotlin.core.chat.double
import dev.hermeskotlin.core.chat.string
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import dev.hermeskotlin.core.sessions.SessionSummary
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
    /** The run worked but its result never reached its target (`last_status` is then `delivery_failed`). */
    @SerialName("last_delivery_error") val lastDeliveryError: String? = null,
    /** Why the scheduler couldn't start the last run. */
    @SerialName("last_fire_error") val lastFireError: String? = null,
    /** Why the scheduler paused the job by itself, when it did. */
    @SerialName("paused_reason") val pausedReason: String? = null,
    /** The profile whose cron store holds the job, as the cross-profile list tags it. */
    val profile: String? = null,
    val model: String? = null,
    /** Where a run's reply goes: `local` (kept on the gateway) or a messaging platform such as `telegram`. */
    val deliver: String? = null,
    /**
     * The raw schedule, kept loose: an object (`kind` with `expr`, `minutes` or `run_at`) normally,
     * but a hand-edited or legacy job may store a plain string.
     */
    val schedule: JsonElement? = null,
) {
    /** The job's name without a Bot Mode routine's `[bot:<profile>]` tag, else the start of its prompt. */
    val displayName: String get() = routineTitle.ifBlank { prompt.lineSequence().firstOrNull()?.take(50).orEmpty() }.ifBlank { id }

    val paused: Boolean get() = state == "paused"

    /** Next run in epoch seconds, or null when none is planned (paused, finished, or unparseable). */
    val nextRunEpochSeconds: Double? get() = nextRunAt.epochSeconds()

    val lastRunEpochSeconds: Double? get() = lastRunAt.epochSeconds()

    /**
     * The schedule as text the gateway parses back to the same schedule, for the editor. One-shot
     * jobs have no such form ("once at …" doesn't parse), so they start blank.
     */
    val editableSchedule: String get() {
        val raw = schedule as? JsonObject
        return when (raw.string("kind")) {
            "cron" -> raw.string("expr").orEmpty()
            "interval" -> raw.double("minutes")?.let { "every ${it.toLong()}m" }.orEmpty()
            "once" -> ""
            else -> scheduleDisplay.takeUnless { it.startsWith("once") || it == "?" }.orEmpty()
        }
    }
}

/** What a new job needs; the gateway parses [schedule] ("every 30m", "0 9 * * *", "every monday 9am", "in 2h", an ISO time). */
@Serializable
data class CronJobDraft(
    val name: String = "",
    val prompt: String,
    val schedule: String,
    val deliver: String = "local",
)

/** A place a job's replies can go, from `GET /api/cron/delivery-targets`. */
@Serializable
data class DeliveryTarget(
    val id: String,
    val name: String = id,
    /** False when the platform has no home channel set yet, so a delivery there would have nowhere to land. */
    @SerialName("home_target_set") val homeTargetSet: Boolean = true,
)

@Serializable
private data class DeliveryTargetsResponse(val targets: List<DeliveryTarget> = emptyList())

@Serializable
private data class JobUpdate(val updates: Map<String, String>)

@Serializable
private data class RunsResponse(val runs: List<SessionSummary> = emptyList())

/** Dashboard cron surface (hermes_cli/web_routers/cron.py), the same one the Desktop's Automations view uses. */
class CronApi(private val client: HttpClient) {

    /** Every job across profiles, in the gateway's order. */
    suspend fun jobs(url: GatewayUrl): ApiResult<List<CronJob>> = apiCall {
        client.get(url.resolve("api/cron/jobs")) { parameter("profile", "all") }
    }.map { it.body<List<CronJob>>() }

    /**
     * The sessions a job's runs produced, newest first. Here and below, [profile] names the store the job is
     * in (the list's `profile`): a hint the gateway checks, which keeps a same-id job of another profile apart.
     */
    suspend fun runs(url: GatewayUrl, jobId: String, limit: Int = 50, profile: String? = null): ApiResult<List<SessionSummary>> = apiCall {
        client.get(url.resolve("api/cron/jobs/${jobId.encodeURLPathPart()}/runs")) {
            parameter("limit", limit.coerceIn(1, 100))
            parameter("profile", profile)
        }
    }.map { it.body<RunsResponse>().runs }

    suspend fun pause(url: GatewayUrl, jobId: String, profile: String? = null): ApiResult<CronJob> = action(url, jobId, "pause", profile)

    suspend fun resume(url: GatewayUrl, jobId: String, profile: String? = null): ApiResult<CronJob> = action(url, jobId, "resume", profile)

    /** Runs the job now, outside its schedule. A 409 means a run is already in progress. */
    suspend fun trigger(url: GatewayUrl, jobId: String, profile: String? = null): ApiResult<CronJob> = action(url, jobId, "trigger", profile)

    /** Adds a job to [profile]'s cron store (null: the launch profile's), where it then runs as that profile. */
    suspend fun create(url: GatewayUrl, draft: CronJobDraft, profile: String? = null): ApiResult<CronJob> = apiCall {
        client.post(url.resolve("api/cron/jobs")) {
            parameter("profile", profile)
            contentType(ContentType.Application.Json)
            setBody(draft)
        }
    }.map { it.body<CronJob>() }

    /**
     * Changes only the fields in [changes] (`name`, `prompt`, `schedule`, `deliver`); the gateway
     * re-parses a new schedule and works out the next run. A bad schedule is a 400 with the reason.
     */
    suspend fun update(url: GatewayUrl, jobId: String, changes: Map<String, String>, profile: String? = null): ApiResult<CronJob> = apiCall {
        client.put(url.resolve("api/cron/jobs/${jobId.encodeURLPathPart()}")) {
            parameter("profile", profile)
            contentType(ContentType.Application.Json)
            setBody(JobUpdate(changes))
        }
    }.map { it.body<CronJob>() }

    suspend fun delete(url: GatewayUrl, jobId: String, profile: String? = null): ApiResult<Unit> = apiCall {
        client.delete(url.resolve("api/cron/jobs/${jobId.encodeURLPathPart()}")) { parameter("profile", profile) }
    }.map { }

    /** `local` first, then each messaging platform [profile] (null: the launch profile) is set up for. */
    suspend fun deliveryTargets(url: GatewayUrl, profile: String? = null): ApiResult<List<DeliveryTarget>> = apiCall {
        client.get(url.resolve("api/cron/delivery-targets")) { parameter("profile", profile) }
    }.map { it.body<DeliveryTargetsResponse>().targets }

    private suspend fun action(url: GatewayUrl, jobId: String, verb: String, profile: String?): ApiResult<CronJob> = apiCall {
        client.post(url.resolve("api/cron/jobs/${jobId.encodeURLPathPart()}/$verb")) { parameter("profile", profile) }
    }.map { it.body<CronJob>() }
}

@OptIn(ExperimentalTime::class)
private fun String?.epochSeconds(): Double? = this?.let {
    runCatching { Instant.parse(it) }.getOrNull()?.let { instant -> instant.toEpochMilliseconds() / 1000.0 }
}
