package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One slice of the context window, such as the system prompt, tool definitions or the conversation. */
data class ContextCategory(val id: String, val label: String, val tokens: Long)

/** A file the agent read into its context (AGENTS.md, SOUL.md, a skill), and whether it fit. */
data class ContextFile(val label: String, val path: String, val tokens: Long, val loaded: Boolean)

/**
 * What fills the model's context window (`session.context_breakdown`), the same split as `/context`.
 * Empty until the session's agent exists, which is after its first prompt.
 */
data class ContextBreakdown(
    val categories: List<ContextCategory>,
    val used: Long,
    val max: Long,
    /** False when the provider reported the size; true when it's Hermes's own estimate. */
    val estimated: Boolean,
    val files: List<ContextFile>,
) {
    val isEmpty: Boolean get() = categories.none { it.tokens > 0 }

    companion object {
        fun parse(result: JsonObject?): ContextBreakdown? {
            result ?: return null
            val categories = (result["categories"] as? JsonArray).orEmpty().mapNotNull { item ->
                val row = item as? JsonObject ?: return@mapNotNull null
                val id = row.string("id") ?: return@mapNotNull null
                ContextCategory(id, row.string("label") ?: id, row.double("tokens")?.toLong() ?: 0)
            }
            val files = (result["context_files"] as? JsonArray).orEmpty().mapNotNull { item ->
                val row = item as? JsonObject ?: return@mapNotNull null
                val path = row.string("path") ?: return@mapNotNull null
                ContextFile(
                    label = row.string("label") ?: path.substringAfterLast('/'),
                    path = path,
                    tokens = row.double("est_tokens")?.toLong() ?: 0,
                    loaded = row.boolean("loaded") ?: true,
                )
            }
            return ContextBreakdown(
                categories = categories,
                used = result.double("context_used")?.toLong() ?: categories.sumOf { it.tokens },
                max = result.double("context_max")?.toLong() ?: 0,
                estimated = result.boolean("context_estimated") ?: true,
                files = files,
            )
        }
    }
}
