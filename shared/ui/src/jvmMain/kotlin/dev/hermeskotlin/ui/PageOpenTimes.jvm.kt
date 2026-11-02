package dev.hermeskotlin.ui

import dev.hermeskotlin.core.storage.appDataDir
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// In slow-pages.log beside crash.log, written off the main thread: a disk that's slow to wake is what may be slow here.
internal actual fun keepSlowOpen(page: String, took: Duration, sinceLast: Duration?) {
    val up = ManagementFactory.getRuntimeMXBean().uptime.milliseconds.inWholeSeconds.seconds
    val idle = sinceLast?.let { "; the last page opened ${it.inWholeSeconds.seconds} before" } ?: "; the first page opened"
    val line = "${Instant.now()} $page took ${took.inWholeMilliseconds} ms; Herald up $up$idle\n"
    writer.execute {
        runCatching {
            val file = File(appDataDir, "slow-pages.log")
            // A few thousand lines at most: past that it starts over.
            if (file.length() > MAX_LOG_BYTES) file.writeText(line) else file.appendText(line)
        }
    }
}

private val writer by lazy {
    Executors.newSingleThreadExecutor { task -> Thread(task, "slow-pages").apply { isDaemon = true } }
}

private const val MAX_LOG_BYTES = 256 * 1024L
