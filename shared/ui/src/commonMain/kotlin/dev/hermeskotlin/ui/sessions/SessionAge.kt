package dev.hermeskotlin.ui.sessions

import androidx.compose.runtime.Composable
import dev.hermeskotlin.ui.resources.*
import kotlin.time.Clock
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The units a list's ages count in, as `relativeTime` buckets them. */
internal enum class AgeUnit { Minute, Hour, Day, Week, Month, Year }

/** How long ago something was: [count] [unit]s, or [now] under a minute. */
internal data class Age(val count: Long, val unit: AgeUnit) {
    val now: Boolean get() = count == 0L
}

/** The age of [epochSeconds] at [nowMillis], bucketed as the rows show it; null without a time. */
internal fun ageOf(epochSeconds: Double?, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): Age? {
    if (epochSeconds == null || epochSeconds <= 0) return null
    val m = ((nowMillis / 1000.0 - epochSeconds) / 60).toLong().coerceAtLeast(0)
    return when {
        m < 1 -> Age(0, AgeUnit.Minute)
        m < 60 -> Age(m, AgeUnit.Minute)
        m < 60 * 24 -> Age(m / 60, AgeUnit.Hour)
        m < 60 * 24 * 7 -> Age(m / (60 * 24), AgeUnit.Day)
        m < 60 * 24 * 30 -> Age(m / (60 * 24 * 7), AgeUnit.Week)
        m < 60 * 24 * 365 -> Age(m / (60 * 24 * 30), AgeUnit.Month)
        else -> Age(m / (60 * 24 * 365), AgeUnit.Year)
    }
}

/** How long until [epochSeconds], bucketed as [ageOf] buckets the past; [Age.now] once it's due; null without a time. */
internal fun untilOf(epochSeconds: Double?, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): Age? {
    if (epochSeconds == null || epochSeconds <= 0) return null
    // The same distance mirrored into the past, so both read alike.
    return ageOf(2 * nowMillis / 1000.0 - epochSeconds, nowMillis)
}

/** A countdown, compact: "in 5m"; "now" once it's due. */
@Composable
internal fun shortUntil(until: Age): String =
    if (until.now) stringResource(Res.string.sessions_age_now) else stringResource(Res.string.sessions_in_time, shortAge(until))

/** A row's compact age ("now", "5m", "3h"), in the user's language; empty without a time. */
@Composable
internal fun shortAge(epochSeconds: Double?): String = shortAge(ageOf(epochSeconds))

@Composable
internal fun shortAge(age: Age?): String {
    age ?: return ""
    if (age.now) return stringResource(Res.string.sessions_age_now)
    val n = age.count.toInt()
    return stringResource(
        when (age.unit) {
            AgeUnit.Minute -> Res.string.sessions_age_minutes
            AgeUnit.Hour -> Res.string.sessions_age_hours
            AgeUnit.Day -> Res.string.sessions_age_days
            AgeUnit.Week -> Res.string.sessions_age_weeks
            AgeUnit.Month -> Res.string.sessions_age_months
            AgeUnit.Year -> Res.string.sessions_age_years
        },
        n,
    )
}

/** "5 minutes ago", "just now"; empty without a time. */
@Composable
internal fun agoPhrase(age: Age?): String {
    age ?: return ""
    if (age.now) return stringResource(Res.string.sessions_just_now)
    val n = age.count.toInt()
    return pluralStringResource(
        when (age.unit) {
            AgeUnit.Minute -> Res.plurals.sessions_minutes_ago
            AgeUnit.Hour -> Res.plurals.sessions_hours_ago
            AgeUnit.Day -> Res.plurals.sessions_days_ago
            AgeUnit.Week -> Res.plurals.sessions_weeks_ago
            AgeUnit.Month -> Res.plurals.sessions_months_ago
            AgeUnit.Year -> Res.plurals.sessions_years_ago
        },
        n,
        n,
    )
}

/** What the list says of the device's copy it shows: "Saved copy · 5 minutes ago". [savedAtMillis] in epoch ms. */
@Composable
internal fun savedCopyText(savedAtMillis: Long): String =
    stringResource(Res.string.sessions_saved_copy, agoPhrase(ageOf(savedAtMillis / 1000.0) ?: Age(0, AgeUnit.Minute)))
