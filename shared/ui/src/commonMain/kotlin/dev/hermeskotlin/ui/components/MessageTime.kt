package dev.hermeskotlin.ui.components

import androidx.compose.runtime.Composable
import kotlin.time.Clock

/** A moment as the device's clock and calendar show it. [dayOfWeek] runs 1 (Monday) to 7; [month] 1 to 12. */
data class LocalMoment(
    val epochDay: Long,
    val year: Int,
    val month: Int,
    val day: Int,
    val dayOfWeek: Int,
    val hour: Int,
    val minute: Int,
)

/** [epochMillis] in the device's time zone. */
expect fun localMoment(epochMillis: Long): LocalMoment

/** The phone is set to a 24-hour clock. */
@Composable
expect fun uses24HourClock(): Boolean

/**
 * The time under a message, as short as [relativeTime]'s labels but a clock time, since a chat reads by
 * when things were said: "14:05" (or "2:05 PM") today, "Yesterday 14:05", "Mon 14:05" within the week,
 * "Sep 3, 14:05" this year, "Sep 3, 2024" before.
 */
fun messageTime(at: LocalMoment, now: LocalMoment, use24Hour: Boolean): String {
    val clock = clockTime(at, use24Hour)
    val daysAgo = now.epochDay - at.epochDay
    val date = "${MONTHS[at.month - 1]} ${at.day}"
    return when {
        daysAgo <= 0 -> clock
        daysAgo == 1L -> "Yesterday $clock"
        daysAgo < 7 -> "${WEEKDAYS[at.dayOfWeek - 1]} $clock"
        at.year == now.year -> "$date, $clock"
        else -> "$date, ${at.year}"
    }
}

/** [messageTime] for a stored or live timestamp (epoch seconds); "" without one. */
fun messageTime(epochSeconds: Double?, use24Hour: Boolean, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): String {
    if (epochSeconds == null || epochSeconds <= 0 || !epochSeconds.isFinite()) return ""
    return messageTime(localMoment((epochSeconds * 1000).toLong()), localMoment(nowMillis), use24Hour)
}

private fun clockTime(at: LocalMoment, use24Hour: Boolean): String {
    val minute = at.minute.toString().padStart(2, '0')
    if (use24Hour) return "${at.hour.toString().padStart(2, '0')}:$minute"
    val hour = (at.hour % 12).let { if (it == 0) 12 else it }
    return "$hour:$minute ${if (at.hour < 12) "AM" else "PM"}"
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
