package dev.hermeskotlin.ui

import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// In the system log, under Herald's tag.
internal actual fun keepSlowOpen(page: String, took: Duration, sinceLast: Duration?) {
    val up = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()).milliseconds.inWholeSeconds.seconds
    val idle = sinceLast?.let { "; the last page opened ${it.inWholeSeconds.seconds} before" } ?: "; the first page opened"
    Log.i("Herald", "$page took ${took.inWholeMilliseconds} ms; up $up$idle")
}
