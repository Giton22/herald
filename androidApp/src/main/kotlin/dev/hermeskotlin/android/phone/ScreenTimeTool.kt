package dev.hermeskotlin.android.phone

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import dev.hermeskotlin.core.phone.PhoneToolException
import dev.hermeskotlin.core.phone.ScreenTimeCounter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * `phone_screen_time`: which apps the user had on screen and for how long, today or over the last few days, from the
 * phone's own usage events ([ScreenTimeCounter]), so the numbers match the phone's own screen time.
 */
internal class ScreenTimeTool(private val context: Context) {

    fun run(args: JsonObject): JsonObject {
        val days = args.int("days") ?: 1
        if (days !in 1..7) throw PhoneToolException("days is 1 (today) to 7.")
        if (!allowed(context)) {
            throw PhoneToolException("Kyllene isn't allowed to see screen time. The user can allow it in Settings → Phone tools.")
        }
        val stats = context.getSystemService(UsageStatsManager::class.java)
            ?: throw PhoneToolException("The phone doesn't keep screen time.")
        val zone = ZoneId.systemDefault()
        val first = LocalDate.now(zone).minusDays(days - 1L)
        // Each day's start in this time zone, and the next day's: a day can be 23 or 25 hours long.
        val starts = LongArray(days + 1) { first.plusDays(it.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() }
        val now = System.currentTimeMillis()

        val usage = ScreenTimeCounter(starts, home(), oneAtATime = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
        // From a day earlier: what was on screen at the start (an app, or the screen itself, on since before
        // midnight) shows only in older events. Only the time from the start is counted.
        val events = stats.queryEvents(starts[0] - LOOK_BACK, now)
        val event = UsageEvents.Event()
        while (events.getNextEvent(event)) {
            val app = event.packageName ?: continue
            usage.add(event.eventType, app, event.className.orEmpty(), event.timeStamp)
        }
        usage.end(now, context.getSystemService(PowerManager::class.java)?.isInteractive == true)

        // By name, and the package too where two apps have the same name (a carrier's Messages and Google's).
        val names = usage.apps.keys.associateWith(::label)
        val clashing = names.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val apps = usage.apps.entries.sortedByDescending { it.value }
            .map { (app, ms) -> names.getValue(app).let { if (it in clashing) "$it ($app)" else it } to ms }
        val listed = apps.filter { it.second >= MINUTE }.take(MAX_APPS)
        return buildJsonObject {
            put("from", first.toString())
            put("to", first.plusDays(days - 1L).toString())
            // The last day is today: so far, not a whole day.
            put("until", LocalTime.now(zone).format(DateTimeFormatter.ofPattern("HH:mm")))
            // Time in any app, without the home screen or a screen left on, so less than screen_on_minutes. Two
            // apps side by side count once here, and each in full in apps. The days' own minutes added up.
            put("in_apps_minutes", usage.byDay.sumOf(::minutes))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                put("screen_on_minutes", minutes(usage.screenOn))
                // A phone without a screen lock is never unlocked: no number then, rather than a wrong 0.
                if (usage.locks) put("unlocks", usage.unlocks)
            }
            putJsonArray("apps") {
                for ((app, ms) in listed) addJsonObject {
                    put("app", app)
                    put("minutes", minutes(ms))
                }
            }
            val others = apps.drop(listed.size).sumOf { it.second }
            if (others >= MINUTE) put("other_apps_minutes", minutes(others))
            if (days > 1) {
                putJsonArray("by_day") {
                    for (i in 0 until days) addJsonObject {
                        put("date", first.plusDays(i.toLong()).toString())
                        put("minutes", minutes(usage.byDay[i]))
                    }
                }
            }
        }
    }

    /**
     * The home screen apps: on screen between every two apps, so not counted, as on the phone's own screen time.
     * Every launcher (the user may have switched within the range), but not Settings' fallback home, which has a
     * negative priority, nor Android's chooser when no launcher is the default.
     */
    private fun home(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val manager = context.packageManager
        val launchers = manager.queryIntentActivities(intent, 0).filter { it.priority >= 0 }.map { it.activityInfo.packageName }
        val default = manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return (launchers + listOfNotNull(default)).toSet() - "android"
    }

    private fun label(packageName: String): String = runCatching {
        val manager = context.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun minutes(ms: Long): Int = (ms / MINUTE.toDouble()).roundToInt()

    companion object {
        private const val MINUTE = 60_000L
        private const val MAX_APPS = 20
        private const val LOOK_BACK = 24 * 60 * MINUTE

        /** Whether the user gave Kyllene Usage access in Android's settings. */
        fun allowed(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            return if (mode == AppOpsManager.MODE_DEFAULT) {
                context.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
            } else {
                mode == AppOpsManager.MODE_ALLOWED
            }
        }
    }
}
