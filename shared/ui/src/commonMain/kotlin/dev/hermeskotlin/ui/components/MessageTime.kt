package dev.hermeskotlin.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.hermeskotlin.ui.resources.*
import kotlin.time.Clock
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

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
 * The words and orders the time labels use, in the app's language. The labels are built in plain functions
 * that many screens and view models call, so the words sit here rather than in each call: [App][dev.hermeskotlin.ui.App]
 * sets [current] from the catalogue with [rememberTimeWords], and until then (and in tests) they're English.
 *
 * The patterns take their parts as `%1$s`, `%2$s`, …, so a language can put the day before the month.
 */
class TimeWords(
    val today: String = "Today",
    val yesterday: String = "Yesterday",
    /** "Yesterday 14:05": the clock. */
    val yesterdayAt: String = "Yesterday %1\$s",
    /** "Mon 14:05": a short weekday, the clock. */
    val weekdayAt: String = "%1\$s %2\$s",
    /** "Sep 3": a short month, the day. */
    val monthDay: String = "%1\$s %2\$s",
    /** "Sep 3, 2024": the month and day, the year. */
    val withYear: String = "%1\$s, %2\$s",
    /** "Sep 3, 14:05": the month and day, the clock. */
    val withClock: String = "%1\$s, %2\$s",
    /** "Thursday, Oct 23, 2026, 14:05": the weekday, the date with its year, the clock. */
    val full: String = "%1\$s, %2\$s, %3\$s",
    val am: String = "AM",
    val pm: String = "PM",
    val monthsShort: List<String> = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"),
    val weekdaysShort: List<String> = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
    val weekdays: List<String> = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"),
    val savedJustNow: String = "Saved copy · just now",
    /** "Saved copy · 5m ago": how long ago. */
    val savedAgo: String = "Saved copy · %1\$s ago",
) {
    companion object {
        /** The words in use: the app's language once [rememberTimeWords] ran, English before. */
        var current: TimeWords = TimeWords()
    }
}

/** [TimeWords] from the catalogue, in the app's language. */
@Composable
fun rememberTimeWords(): TimeWords {
    val today = stringResource(Res.string.time_today)
    val yesterday = stringResource(Res.string.time_yesterday)
    val yesterdayAt = stringResource(Res.string.time_yesterday_at)
    val weekdayAt = stringResource(Res.string.time_weekday_at)
    val monthDay = stringResource(Res.string.time_month_day)
    val withYear = stringResource(Res.string.time_with_year)
    val withClock = stringResource(Res.string.time_with_clock)
    val full = stringResource(Res.string.time_full)
    val am = stringResource(Res.string.time_am)
    val pm = stringResource(Res.string.time_pm)
    val months = stringArrayResource(Res.array.time_months_short)
    val weekdaysShort = stringArrayResource(Res.array.time_weekdays_short)
    val weekdays = stringArrayResource(Res.array.time_weekdays)
    val savedJustNow = stringResource(Res.string.time_saved_just_now)
    val savedAgo = stringResource(Res.string.time_saved_ago)
    return remember(today, yesterday, yesterdayAt, weekdayAt, monthDay, withYear, withClock, full, am, pm, months, weekdaysShort, weekdays, savedJustNow, savedAgo) {
        val english = TimeWords()
        TimeWords(
            today, yesterday, yesterdayAt, weekdayAt, monthDay, withYear, withClock, full, am, pm,
            // A list that lost an entry keeps the English one, rather than a label that throws.
            months.takeIf { it.size == 12 } ?: english.monthsShort,
            weekdaysShort.takeIf { it.size == 7 } ?: english.weekdaysShort,
            weekdays.takeIf { it.size == 7 } ?: english.weekdays,
            savedJustNow,
            savedAgo,
        )
    }
}

/** [pattern] with `%1$s`, `%2$s`, … replaced by [parts] in order. */
internal fun fill(pattern: String, vararg parts: Any): String =
    parts.foldIndexed(pattern) { i, text, part -> text.replace("%${i + 1}\$s", part.toString()) }

/**
 * The time under a message, as short as [relativeTime]'s labels but a clock time, since a chat reads by
 * when things were said: "14:05" (or "2:05 PM") today, "Yesterday 14:05", "Mon 14:05" within the week,
 * "Sep 3, 14:05" this year, "Sep 3, 2024" before.
 */
fun messageTime(at: LocalMoment, now: LocalMoment, use24Hour: Boolean, words: TimeWords = TimeWords.current): String {
    val clock = clockTime(at, use24Hour, words)
    val daysAgo = now.epochDay - at.epochDay
    val date = fill(words.monthDay, words.monthsShort[at.month - 1], at.day)
    return when {
        daysAgo <= 0 -> clock
        daysAgo == 1L -> fill(words.yesterdayAt, clock)
        daysAgo < 7 -> fill(words.weekdayAt, words.weekdaysShort[at.dayOfWeek - 1], clock)
        at.year == now.year -> fill(words.withClock, date, clock)
        else -> fill(words.withYear, date, at.year)
    }
}

/** [messageTime] for a stored or live timestamp (epoch seconds); "" without one. */
fun messageTime(epochSeconds: Double?, use24Hour: Boolean, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): String {
    if (epochSeconds == null || epochSeconds <= 0 || !epochSeconds.isFinite()) return ""
    return messageTime(localMoment((epochSeconds * 1000).toLong()), localMoment(nowMillis), use24Hour)
}

/** The whole moment, for a desktop's tooltip over a message's time: "Thursday, Oct 23, 2026, 14:05"; "" without one. */
fun fullMessageTime(epochSeconds: Double?, use24Hour: Boolean): String {
    if (epochSeconds == null || epochSeconds <= 0 || !epochSeconds.isFinite()) return ""
    val at = localMoment((epochSeconds * 1000).toLong())
    val words = TimeWords.current
    val date = fill(words.withYear, fill(words.monthDay, words.monthsShort[at.month - 1], at.day), at.year)
    return fill(words.full, words.weekdays[at.dayOfWeek - 1], date, clockTime(at, use24Hour, words))
}

private fun clockTime(at: LocalMoment, use24Hour: Boolean, words: TimeWords): String {
    val minute = at.minute.toString().padStart(2, '0')
    if (use24Hour) return "${at.hour.toString().padStart(2, '0')}:$minute"
    val hour = (at.hour % 12).let { if (it == 0) 12 else it }
    return "$hour:$minute ${if (at.hour < 12) words.am else words.pm}"
}

/** A day heading for a list grouped by day: "Today", "Yesterday", "Monday" within the week, "Sep 3", "Sep 3, 2024". */
fun dayHeading(at: LocalMoment, now: LocalMoment, words: TimeWords = TimeWords.current): String {
    val daysAgo = now.epochDay - at.epochDay
    val date = fill(words.monthDay, words.monthsShort[at.month - 1], at.day)
    return when {
        daysAgo <= 0 -> words.today
        daysAgo == 1L -> words.yesterday
        daysAgo < 7 -> words.weekdays[at.dayOfWeek - 1]
        at.year == now.year -> date
        else -> fill(words.withYear, date, at.year)
    }
}

/** [dayHeading] for epoch seconds, on the device's calendar. */
fun dayHeading(epochSeconds: Long, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): String =
    dayHeading(localMoment(epochSeconds * 1000), localMoment(nowMillis))
