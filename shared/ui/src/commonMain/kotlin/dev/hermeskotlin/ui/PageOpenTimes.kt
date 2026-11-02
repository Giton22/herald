package dev.hermeskotlin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Times [page] from its first composition to its first frame on screen; a slow one is written down ([keepSlowOpen]).
 * Call it once in the page, keyed to it, so it times each opening.
 */
@Composable
fun TimeOpening(page: String) {
    val start = remember { TimeSource.Monotonic.markNow() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        PageOpenTimes.opened(page, start.elapsedNow())
    }
}

internal object PageOpenTimes {
    private var last: TimeMark? = null

    /** On the main thread, as every composition's effects are. */
    fun opened(page: String, took: Duration) {
        val sinceLast = last?.elapsedNow()
        last = TimeSource.Monotonic.markNow()
        if (took >= SLOW_OPEN) keepSlowOpen(page, took, sinceLast)
    }
}

/**
 * Notes that [page] took [took] to appear, with what tells the likely cause apart: how long the app had been running
 * (a page met before the desktop's warm-up reached it) and [sinceLast], how long since any page last opened (an app
 * idle for hours, whose memory the system had set aside); null when it's the first.
 */
internal expect fun keepSlowOpen(page: String, took: Duration, sinceLast: Duration?)

/** Slower than this, a page's opening is noticed. */
private val SLOW_OPEN = 250.milliseconds
