package dev.hermeskotlin.ui.components

import kotlin.time.Clock

/** Compact age label for list rows: "now", "5m", "3h", "2d", "4w", "6mo", "1y". */
fun relativeTime(epochSeconds: Double?, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): String {
    if (epochSeconds == null || epochSeconds <= 0) return ""
    val minutes = ((nowMillis / 1000.0 - epochSeconds) / 60).toLong().coerceAtLeast(0)
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 60 * 24 -> "${minutes / 60}h"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24 * 7)}w"
        minutes < 60 * 24 * 365 -> "${minutes / (60 * 24 * 30)}mo"
        else -> "${minutes / (60 * 24 * 365)}y"
    }
}
