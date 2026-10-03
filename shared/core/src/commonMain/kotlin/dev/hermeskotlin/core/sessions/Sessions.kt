package dev.hermeskotlin.core.sessions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One stored session row as `GET /api/sessions` returns it (hermes_cli/web_routers/sessions.py,
 * `StoredSessionRow` in tui_gateway/contracts/common.py). Times are epoch seconds.
 */
@Serializable
data class SessionSummary(
    val id: String,
    val title: String? = null,
    val preview: String? = null,
    val source: String? = null,
    val model: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("last_active") val lastActive: Double? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    @Serializable(with = LenientBooleanSerializer::class) val pinned: Boolean = false,
    @Serializable(with = LenientBooleanSerializer::class) val archived: Boolean = false,
    @Serializable(with = LenientBooleanSerializer::class) @SerialName("is_active") val isActive: Boolean = false,
    /** Search hits only: the matching message excerpt (FTS5 `snippet()`, `>>>`/`<<<` markers stripped). */
    val snippet: String? = null,
) {
    /** What a list shows as the name: the title, else the first prompt, else a placeholder. */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: preview?.takeIf { it.isNotBlank() }?.lineSequence()?.first()
            ?: "Untitled session"

    /** Latest activity in epoch seconds, falling back to when the session started. */
    val activityAt: Double? get() = lastActive ?: startedAt
}

/**
 * What a stored session has used so far, from its row (`GET /api/sessions/{id}`). The gateway keeps
 * these through restarts; costs are estimates from its price table unless the provider billed exactly.
 */
@Serializable
data class SessionTotals(
    // Null in rows written before a counter existed.
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long? = null,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long? = null,
    @SerialName("reasoning_tokens") val reasoningTokens: Long? = null,
    @SerialName("api_call_count") val apiCalls: Long? = null,
    @SerialName("estimated_cost_usd") val estimatedCostUsd: Double? = null,
    @SerialName("actual_cost_usd") val actualCostUsd: Double? = null,
    /** e.g. `estimated`, `actual`, `included` (a subscription), `unknown` (no price for the model). */
    @SerialName("cost_status") val costStatus: String? = null,
    val model: String? = null,
) {
    /** The billed amount when there is one, else the estimate. */
    val costUsd: Double? get() = actualCostUsd?.takeIf { it > 0 } ?: estimatedCostUsd
    val costIsEstimate: Boolean get() = actualCostUsd == null || actualCostUsd <= 0
}

@Serializable
data class SessionPage(
    val sessions: List<SessionSummary> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

/** Which part of the archive a listing covers (`archived=` query). */
enum class ArchiveFilter(val wire: String) { Exclude("exclude"), Only("only"), Include("include") }

/** `source` of sessions started by scheduled (cron) jobs. */
const val CRON_SOURCE = "cron"

/**
 * The list views. Like Hermes Desktop, Recent leaves out cron runs, which are always the newest rows
 * and would otherwise push real conversations off the first page; they are reached through their
 * job instead (`CronApi.runs`).
 */
enum class SessionListFilter(
    val archived: ArchiveFilter,
    val source: String? = null,
    val excludeSources: List<String> = emptyList(),
) {
    Recent(ArchiveFilter.Exclude, excludeSources = listOf(CRON_SOURCE)),
    Archived(ArchiveFilter.Only),
}

/**
 * One stored transcript row from `GET /api/sessions/{id}/messages` — the raw `messages` table row
 * plus display projections. [content] is usually a string, but multimodal turns store a parts array.
 */
@Serializable
data class SessionMessage(
    val id: Long? = null,
    val role: String,
    val content: JsonElement? = null,
    @SerialName("display_content") val displayContent: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: JsonElement? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_name") val toolName: String? = null,
    val timestamp: Double? = null,
    val reasoning: String? = null,
    @SerialName("display_kind") val displayKind: String? = null,
) {
    /** Plain text to render: the display projection when present, else the stored content. */
    val text: String get() = (displayContent ?: content).plainText()

    /** Image parts of a multimodal row (`image_url` / `image` / `input_image`). */
    val imageCount: Int
        get() = ((displayContent as? JsonArray) ?: (content as? JsonArray)).orEmpty().count { part ->
            (part as? JsonObject)?.get("type")?.stringOrNull() in IMAGE_PART_TYPES
        }

    /** Names of the tools an assistant turn called (`tool_calls[].function.name`). */
    val calledTools: List<String>
        get() = (toolCalls as? JsonArray).orEmpty().mapNotNull { call ->
            val function = (call as? JsonObject)?.get("function") as? JsonObject
            (function?.get("name") ?: (call as? JsonObject)?.get("name"))?.stringOrNull()
        }

    /** Rows the gateway marks for storage only (compaction wrappers, seeded context). */
    val isHidden: Boolean get() = displayKind == "hidden"
}

@Serializable
data class SessionMessagesPage(
    @SerialName("session_id") val sessionId: String,
    val messages: List<SessionMessage> = emptyList(),
)

private val IMAGE_PART_TYPES = setOf("image_url", "image", "input_image")

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** Strings pass through; OpenAI-style parts arrays join their `text` parts; anything else is dropped. */
private fun JsonElement?.plainText(): String = when (this) {
    null -> ""
    is JsonPrimitive -> if (isString) content else ""
    is JsonArray -> mapNotNull { part ->
        when (part) {
            is JsonPrimitive -> part.contentOrNull
            is JsonObject -> part["text"]?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }.joinToString("\n")
    is JsonObject -> this["text"]?.stringOrNull() ?: ""
}
