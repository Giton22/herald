package dev.hermeskotlin.ui.components

import kotlin.time.Clock

/** Compact age label for list rows: "now", "5m", "3h", "2d", "4w", "6mo", "1y". */
fun relativeTime(epochSeconds: Double?, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): String {
    if (epochSeconds == null || epochSeconds <= 0) return ""
    return span(((nowMillis / 1000.0 - epochSeconds) / 60).toLong())
}

/** What a list or chat shown from the device's copy says about it: "Saved copy · 5m ago". [savedAtMillis] in epoch ms. */
fun savedCopyLabel(savedAtMillis: Long, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): String =
    when (val age = relativeTime(savedAtMillis / 1000.0, nowMillis)) {
        "now", "" -> "Saved copy · just now"
        else -> "Saved copy · $age ago"
    }

/** Compact countdown to a future moment: "in 5m", "in 3h"; "now" once it is due. */
fun timeUntil(epochSeconds: Double?, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): String {
    if (epochSeconds == null || epochSeconds <= 0) return ""
    return when (val label = span(((epochSeconds - nowMillis / 1000.0) / 60).toLong())) {
        "now" -> label
        else -> "in $label"
    }
}

private fun span(minutes: Long): String {
    val m = minutes.coerceAtLeast(0)
    return when {
        m < 1 -> "now"
        m < 60 -> "${m}m"
        m < 60 * 24 -> "${m / 60}h"
        m < 60 * 24 * 7 -> "${m / (60 * 24)}d"
        m < 60 * 24 * 30 -> "${m / (60 * 24 * 7)}w"
        m < 60 * 24 * 365 -> "${m / (60 * 24 * 30)}mo"
        else -> "${m / (60 * 24 * 365)}y"
    }
}
