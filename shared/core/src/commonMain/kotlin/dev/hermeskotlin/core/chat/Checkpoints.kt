package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A snapshot of the chat's working folder that Hermes takes just before the agent changes files
 * (`rollback.list`, tools/checkpoint_manager.py), newest first. [message] says what was about to happen.
 */
data class Checkpoint(
    val hash: String,
    /** ISO 8601, as git wrote it. */
    val timestamp: String,
    val message: String,
) {
    val shortHash: String get() = hash.take(SHORT_HASH)

    private companion object {
        const val SHORT_HASH = 8
    }
}

/** `rollback.list`: [enabled] false when checkpoints are off in the profile's config. */
data class Checkpoints(val enabled: Boolean, val checkpoints: List<Checkpoint>) {
    companion object {
        fun parse(result: JsonObject?): Checkpoints = Checkpoints(
            enabled = result.boolean("enabled") ?: false,
            checkpoints = (result?.get("checkpoints") as? JsonArray).orEmpty().mapNotNull { item ->
                val row = item as? JsonObject ?: return@mapNotNull null
                val hash = row.string("hash")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Checkpoint(hash, row.string("timestamp").orEmpty(), row.string("message").orEmpty())
            },
        )
    }
}

/** `rollback.diff`: what changed from a checkpoint to the folder as it is now. [error] when it couldn't be read. */
data class CheckpointDiff(val stat: String = "", val diff: String = "", val error: String? = null) {
    /** Nothing differs: the folder is as it was at the checkpoint. */
    val unchanged: Boolean get() = error == null && stat.isBlank() && diff.isBlank()

    companion object {
        fun parse(result: JsonObject?) = CheckpointDiff(stat = result.string("stat").orEmpty(), diff = result.string("diff").orEmpty())
    }
}

/** How a `rollback.restore` went, in words: the files put back, the chat turn taken back, or why not. */
internal fun restoreOutcome(result: JsonObject?): String {
    if (result.boolean("success") != true) {
        return result.string("error") ?: result.string("reason") ?: "Couldn't restore the checkpoint."
    }
    val files = result.strings("restored_files")
    val skipped = result.strings("skipped_user_edits")
    val removed = result.int("history_removed") ?: 0
    return buildString {
        append(
            when (files?.size) {
                null -> "Restored the folder"
                0 -> "Nothing needed restoring"
                1 -> "Restored 1 file"
                else -> "Restored ${files.size} files"
            },
        )
        result.string("restored_to")?.take(8)?.let { append(" to $it") }
        append('.')
        if (removed > 0) append(" The chat's last turn was taken back too.")
        if (!skipped.isNullOrEmpty()) {
            append(" Left alone, since you changed them yourself: ${skipped.joinToString(", ")}.")
        }
    }
}

private fun JsonObject?.strings(key: String): List<String>? =
    (this?.get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
