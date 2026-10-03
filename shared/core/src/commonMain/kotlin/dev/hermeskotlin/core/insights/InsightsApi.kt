package dev.hermeskotlin.core.insights

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One day of use; days without any are left out by the gateway. */
@Serializable
data class UsageDay(
    /** `YYYY-MM-DD` in the gateway's time zone. */
    val day: String,
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    @SerialName("estimated_cost") val estimatedCost: Double = 0.0,
    @SerialName("actual_cost") val actualCost: Double = 0.0,
    val sessions: Int = 0,
    @SerialName("api_calls") val apiCalls: Int = 0,
) {
    val tokens: Long get() = inputTokens + outputTokens

    /** What the provider billed when it says so, otherwise the list-price estimate. */
    val cost: Double get() = if (actualCost > 0) actualCost else estimatedCost
}

@Serializable
data class ModelUsage(
    val model: String,
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("estimated_cost") val estimatedCost: Double = 0.0,
    val sessions: Int = 0,
    @SerialName("api_calls") val apiCalls: Int = 0,
) {
    val tokens: Long get() = inputTokens + outputTokens
}

@Serializable
data class ToolUsage(val tool: String, val count: Int = 0)

@Serializable
data class SkillUsage(val skill: String, @SerialName("total_count") val count: Int = 0)

@Serializable
data class UsageTotals(
    @SerialName("total_input") val input: Long = 0,
    @SerialName("total_output") val output: Long = 0,
    @SerialName("total_cache_read") val cacheRead: Long = 0,
    @SerialName("total_estimated_cost") val estimatedCost: Double = 0.0,
    @SerialName("total_actual_cost") val actualCost: Double = 0.0,
    @SerialName("total_sessions") val sessions: Int = 0,
    @SerialName("total_api_calls") val apiCalls: Int = 0,
) {
    val cost: Double get() = if (actualCost > 0) actualCost else estimatedCost

    /** Share of the prompt tokens served from the provider's cache, 0–100; null with no input. */
    val cachePercent: Int? get() = (input + cacheRead).takeIf { it > 0 }?.let { (cacheRead * 100 / it).toInt() }
}

@Serializable
data class SkillsBlock(@SerialName("top_skills") val top: List<SkillUsage> = emptyList())

/** `GET /api/analytics/usage`, the data behind Desktop's Insights. */
@Serializable
data class UsageReport(
    val daily: List<UsageDay> = emptyList(),
    @SerialName("by_model") val byModel: List<ModelUsage> = emptyList(),
    val totals: UsageTotals = UsageTotals(),
    @SerialName("period_days") val periodDays: Int = 30,
    /** The `skills` block; only its top list is read. */
    @SerialName("skills") val skillsBlock: SkillsBlock = SkillsBlock(),
    val tools: List<ToolUsage> = emptyList(),
) {
    val topSkills: List<SkillUsage> get() = skillsBlock.top
}

class InsightsApi(private val client: HttpClient) {

    /** Use over the last [days] (the gateway clamps to 1–365) for [profile], or the launch profile. */
    suspend fun usage(url: GatewayUrl, days: Int, profile: String?): ApiResult<UsageReport> = apiCall {
        client.get(url.resolve("api/analytics/usage")) {
            parameter("days", days)
            profile?.let { parameter("profile", it) }
        }
    }.map { it.body<UsageReport>() }
}

/**
 * The period day by day, ending on [lastEpochDay], with the days the gateway left out (no use) as
 * zero rows, so a chart's time axis stays even.
 */
fun UsageReport.dailySeries(lastEpochDay: Long): List<UsageDay> {
    val byDay = daily.associateBy { epochDayOf(it.day) }
    val keys = byDay.keys.filterNotNull()
    val days = periodDays.coerceIn(1, 365)
    var end = maxOf(lastEpochDay, keys.maxOrNull() ?: lastEpochDay)
    // [lastEpochDay] can run a day ahead of the gateway's own date; never cut off a day it reported.
    keys.minOrNull()?.let { first -> if (first < end - days + 1) end = maxOf(first + days - 1, keys.max()) }
    return ((end - days + 1)..end).map { day -> byDay[day] ?: UsageDay(day = isoDateOf(day)) }
}

/** Days since 1970-01-01 for a `YYYY-MM-DD` string; null when it isn't one. */
internal fun epochDayOf(iso: String): Long? {
    val parts = iso.take(10).split('-')
    if (parts.size != 3) return null
    val (y, m, d) = parts.map { it.toLongOrNull() ?: return null }
    if (m !in 1..12 || d !in 1..31) return null
    // Howard Hinnant's days_from_civil.
    val year = if (m <= 2) y - 1 else y
    val era = (if (year >= 0) year else year - 399) / 400
    val yoe = year - era * 400
    val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

/** `YYYY-MM-DD` for days since 1970-01-01. */
internal fun isoDateOf(epochDay: Long): String {
    val z = epochDay + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}
