package dev.hermeskotlin.ui

import platform.Foundation.NSLog
import kotlin.time.Duration

// In the device log (Console.app), as Android keeps it in logcat.
internal actual fun keepSlowOpen(page: String, took: Duration, sinceLast: Duration?) {
    val idle = sinceLast?.let { "; the last page opened ${it.inWholeSeconds} s before" } ?: "; the first page opened"
    NSLog("Herald: %@", "$page took ${took.inWholeMilliseconds} ms$idle")
}
