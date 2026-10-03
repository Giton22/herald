package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * A background process the agent started in this chat (a dev server, a long build, a watcher), as
 * `process.list` reports it. Finished ones stay listed for a while with their exit code.
 */
data class BackgroundProcess(
    /** The registry's process id (`proc_…`), which `process.kill` takes. */
    val id: String,
    val command: String,
    val cwd: String?,
    val pid: Long?,
    val running: Boolean,
    val uptimeSeconds: Long?,
    val exitCode: Int?,
    /** The last few thousand characters of its output. */
    val output: String,
) {
    /** Ended by a signal (a negative exit code, -15 after `process.kill`) rather than on its own. */
    val stopped: Boolean get() = !running && (exitCode ?: 0) < 0

    companion object {
        fun parseList(result: JsonObject?): List<BackgroundProcess> =
            (result?.get("processes") as? JsonArray).orEmpty().mapNotNull { item ->
                val row = item as? JsonObject ?: return@mapNotNull null
                val id = row.string("session_id") ?: return@mapNotNull null
                BackgroundProcess(
                    id = id,
                    command = row.string("command").orEmpty(),
                    cwd = row.string("cwd"),
                    pid = row.double("pid")?.toLong(),
                    running = row.string("status") != "exited",
                    uptimeSeconds = row.double("uptime_seconds")?.toLong(),
                    exitCode = row.int("exit_code"),
                    output = row.string("output_tail") ?: row.string("output_preview").orEmpty(),
                )
            }.sortedByDescending { it.running }
    }
}

/** What `process.kill` did, in a line for the user. */
fun killOutcome(result: JsonObject?): String = when (result.string("status")) {
    "killed" -> "Stopped."
    "already_exited" -> "It had already finished."
    "not_found" -> "It's no longer running."
    else -> result.string("error") ?: "Couldn't stop it."
}
