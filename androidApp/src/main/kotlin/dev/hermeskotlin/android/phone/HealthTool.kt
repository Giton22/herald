package dev.hermeskotlin.android.phone

import android.app.ActivityManager
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.hermeskotlin.core.phone.PhoneToolException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * `phone_health`: steps, sleep or heart rate from Health Connect, whichever apps write there (a watch's, a fitness
 * app's). Only the three the user allowed in Health Connect, and only totals and sessions: no samples, no sources.
 */
internal class HealthTool(private val context: Context) {

    suspend fun run(args: JsonObject): JsonObject {
        val metric = args.string("metric") ?: throw PhoneToolException("metric is steps, sleep or heart_rate.")
        val permission = when (metric) {
            "steps" -> HealthPermission.getReadPermission(StepsRecord::class)
            "sleep" -> HealthPermission.getReadPermission(SleepSessionRecord::class)
            "heart_rate" -> HealthPermission.getReadPermission(HeartRateRecord::class)
            else -> throw PhoneToolException("metric is steps, sleep or heart_rate.")
        }
        val client = client()
        val granted = guarded { client.permissionController.getGrantedPermissions() }
        if (permission !in granted) {
            throw PhoneToolException("Kyllene isn't allowed to read $metric in Health Connect. The user can allow it in Kyllene: Settings → Phone tools, or in the Health Connect app.")
        }
        // Health Connect itself refuses reads from an app out of sight, unless the user allowed that too.
        if (!visible() && HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted) {
            throw PhoneToolException("Health data is only read while Kyllene is open on the phone; the user can also allow it while closed in Kyllene: Settings → Phone tools.")
        }
        val now = LocalDateTime.now()
        val from = args.string("from")?.let { time(it, "from") }
            // Last night's sleep began yesterday.
            ?: if (metric == "sleep") now.minusDays(1) else LocalDate.now().atStartOfDay()
        // A date alone as the end means through that day, as people say "the 1st to the 7th".
        val to = (args.string("to")?.let { time(it, "to").let { end -> if (it.length <= DATE_LENGTH) end.plusDays(1) else end } } ?: now)
            .coerceAtMost(now)
        if (!to.isAfter(from)) throw PhoneToolException("to must be after from, and from not in the future.")
        if (Duration.between(from, to) > Duration.ofDays(MAX_DAYS)) throw PhoneToolException("A range covers at most $MAX_DAYS days.")
        return guarded {
            buildJsonObject {
                put("from", from.format(LOCAL))
                put("to", to.format(LOCAL))
                // Health Connect gives an app only what's newer than 30 days before it was first allowed.
                if (from < now.minusDays(HISTORY_DAYS)) {
                    put("history_note", "Health Connect may hold back data from more than 30 days before Kyllene was first allowed to read it; missing data there isn't proof of none.")
                }
                when (metric) {
                    "steps" -> steps(client, from, to)
                    "sleep" -> sleep(client, from, to)
                    else -> heartRate(client, from, to)
                }
            }
        }
    }

    private suspend fun JsonObjectBuilder.steps(client: HealthConnectClient, from: LocalDateTime, to: LocalDateTime) {
        val total = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(from, to)))
        put("steps", total[StepsRecord.COUNT_TOTAL] ?: 0L)
        if (total.dataOrigins.isEmpty()) {
            // Health Connect only totals the apps in its source list; one left out isn't "no steps".
            put("note", "No steps were recorded here, or the app that records them isn't in Health Connect's \"Data sources and priority\" list, which is all it counts.")
            return
        }
        if (from.toLocalDate() != to.minusNanos(1).toLocalDate()) {
            val days = days(client, setOf(StepsRecord.COUNT_TOTAL), from, to)
            putJsonArray("by_day") {
                days.forEach { (day, result) ->
                    addJsonObject {
                        put("day", day)
                        put("steps", result[StepsRecord.COUNT_TOTAL] ?: 0L)
                    }
                }
            }
        }
    }

    private suspend fun JsonObjectBuilder.heartRate(client: HealthConnectClient, from: LocalDateTime, to: LocalDateTime) {
        val metrics = setOf(HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX, HeartRateRecord.MEASUREMENTS_COUNT)
        val total = client.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(from, to)))
        val count = total[HeartRateRecord.MEASUREMENTS_COUNT] ?: 0L
        put("measurements", count)
        if (count == 0L) return
        bpm(total)
        if (from.toLocalDate() != to.minusNanos(1).toLocalDate()) {
            val days = days(client, metrics, from, to)
            putJsonArray("by_day") {
                for ((day, result) in days) {
                    if ((result[HeartRateRecord.MEASUREMENTS_COUNT] ?: 0L) == 0L) continue
                    addJsonObject {
                        put("day", day)
                        bpm(result)
                    }
                }
            }
        }
    }

    private fun JsonObjectBuilder.bpm(result: AggregationResult) {
        result[HeartRateRecord.BPM_MIN]?.let { put("min_bpm", it) }
        result[HeartRateRecord.BPM_AVG]?.let { put("avg_bpm", it) }
        result[HeartRateRecord.BPM_MAX]?.let { put("max_bpm", it) }
    }

    private suspend fun JsonObjectBuilder.sleep(client: HealthConnectClient, from: LocalDateTime, to: LocalDateTime) {
        val zone = ZoneId.systemDefault()
        // Newest first, so a long range with many sessions keeps the recent nights.
        val page = client.readRecords(
            ReadRecordsRequest(
                SleepSessionRecord::class,
                TimeRangeFilter.between(from.atZone(zone).toInstant(), to.atZone(zone).toInstant()),
                ascendingOrder = false,
                pageSize = MAX_SESSIONS,
            ),
        )
        val records = page.records.sortedBy { it.startTime }
        if (page.pageToken != null) put("note", "Only the newest $MAX_SESSIONS sessions; ask for a shorter range for older ones.")
        putJsonArray("sessions") {
            for (session in records) {
                addJsonObject {
                    put("start", LocalDateTime.ofInstant(session.startTime, zone).format(LOCAL))
                    put("end", LocalDateTime.ofInstant(session.endTime, zone).format(LOCAL))
                    put("minutes", Duration.between(session.startTime, session.endTime).toMinutes())
                    if (session.stages.isNotEmpty()) {
                        putJsonObject("stage_minutes") {
                            session.stages.groupBy { STAGES[it.stage] ?: "unknown" }.forEach { (name, stages) ->
                                // Summed before rounding: a night has dozens of stages.
                                put(name, stages.fold(Duration.ZERO) { sum, it -> sum + Duration.between(it.startTime, it.endTime) }.toMinutes())
                            }
                        }
                    }
                }
            }
        }
    }

    /** [metrics] for each calendar day in the range, as ("2026-10-14", result). */
    private suspend fun days(
        client: HealthConnectClient,
        metrics: Set<AggregateMetric<*>>,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<Pair<String, AggregationResult>> =
        client.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(metrics, TimeRangeFilter.between(from, to), Period.ofDays(1)))
            .map { it.startTime.toLocalDate().toString() to it.result }

    private fun client(): HealthConnectClient = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectClient.getOrCreate(context)
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            throw PhoneToolException("Health Connect needs an update on the phone before Kyllene can read from it.")
        else -> throw PhoneToolException("This phone has no Health Connect.")
    }

    /** Health Connect's own refusals (a permission taken back, too many reads) as answers the agent can pass on. */
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: PhoneToolException) {
        throw e
    } catch (e: SecurityException) {
        throw PhoneToolException("Health Connect refused the read: the user can allow it in Kyllene: Settings → Phone tools.")
    } catch (e: Exception) {
        throw PhoneToolException("Health Connect didn't answer (${e.javaClass.simpleName}); try again later.")
    }

    private fun visible(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    /** "2026-10-14T09:30", or "2026-10-14" for the start of that day; local time. */
    private fun time(text: String, name: String): LocalDateTime = try {
        if (text.length <= DATE_LENGTH) LocalDate.parse(text).atStartOfDay() else LocalDateTime.parse(text)
    } catch (e: DateTimeParseException) {
        throw PhoneToolException("$name is a local date and time like 2026-10-14T09:30, or a date like 2026-10-14.")
    }

    private companion object {
        const val MAX_DAYS = 31L
        const val MAX_SESSIONS = 100
        const val HISTORY_DAYS = 30L
        const val DATE_LENGTH = 10
        val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT)
        val STAGES = mapOf(
            SleepSessionRecord.STAGE_TYPE_AWAKE to "awake",
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED to "awake",
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED to "out_of_bed",
            SleepSessionRecord.STAGE_TYPE_SLEEPING to "asleep",
            SleepSessionRecord.STAGE_TYPE_LIGHT to "light",
            SleepSessionRecord.STAGE_TYPE_DEEP to "deep",
            SleepSessionRecord.STAGE_TYPE_REM to "rem",
        )
    }
}
