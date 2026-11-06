package dev.hermeskotlin.core.chat

/** What a running turn is doing, in the order a live update says it. */
enum class LiveTurnKind {
    /** Blocked on the user: an approval, a question, a password. */
    Waiting,

    /** A tool is running. */
    Tool,

    /** Working through a step of the live plan. */
    Plan,

    /** The gateway's own line (`status.update`), e.g. it is switching model or compacting. */
    Status,

    /** Nothing more specific: the model is thinking. */
    Thinking,
}

/** One tool the turn ran, worth a line on the card. */
data class LiveTool(val name: String, val line: String?, val state: LiveToolState)

/** How a tool on the card ended, or that it is still going. */
enum class LiveToolState { Done, Running, Failed }

/** A running turn as a live update shows it. */
data class LiveTurn(
    val kind: LiveTurnKind,
    /** The tool running now, as the gateway names it (`terminal`, `read_file`). */
    val tool: String? = null,
    /** That tool's own line: the command it runs, or what it is doing. */
    val detail: String? = null,
    /** The live plan's step in hand, when [kind] is [LiveTurnKind.Plan]. */
    val stepText: String? = null,
    /** Tools the turn has run so far, the running one included. */
    val tools: Int = 0,
    /** The plan's step now, and how many there are; null when the turn has no live plan. */
    val step: Int? = null,
    val steps: Int? = null,
    /** The gateway's `status.update` line. */
    val status: String? = null,
    /**
     * The last few tools the turn ran, oldest first: as many as the card has room for. They are the tools since the
     * prompt that started the turn, or since the last mid-turn correction.
     */
    val recent: List<LiveTool> = emptyList(),
)

/**
 * The running turn, or null when no turn runs.
 *
 * A mid-turn correction seals the reply above the steer and appends the steer as a new prompt, so the turn's
 * work spans both sides of it. Its segments start at the sealed reply when there is one ([ChatState.correctedReplyKey]);
 * without a correction they start after the last prompt.
 */
fun ChatState.liveTurn(): LiveTurn? {
    if (!running) return null
    // The turn's assistants: from its sealed reply across a correction, else everything after the last prompt,
    // with notices and events in between skipped.
    val corrected = correctedReplyKey?.let { key -> messages.indexOfFirst { it.key == key } } ?: -1
    val segments = (if (corrected >= 0) messages.drop(corrected) else messages.takeLastWhile { it !is ChatMessage.User })
        .filterIsInstance<ChatMessage.Assistant>()
    val tool = segments.asReversed().firstNotNullOfOrNull { segment -> segment.tools.lastOrNull { it.running } }
    val plan = todos?.takeIf { todosLive }?.takeIf { it.total > 0 && it.active }
    val stepText = plan?.items?.firstOrNull { it.status == TodoStatus.InProgress }?.content
    val kind = when {
        inputRequests.isNotEmpty() -> LiveTurnKind.Waiting
        tool != null -> LiveTurnKind.Tool
        !stepText.isNullOrBlank() -> LiveTurnKind.Plan
        !status.isNullOrBlank() -> LiveTurnKind.Status
        else -> LiveTurnKind.Thinking
    }
    return LiveTurn(
        kind = kind,
        tool = tool?.name,
        detail = tool?.liveDetail(),
        stepText = stepText?.takeIf { kind == LiveTurnKind.Plan },
        tools = segments.sumOf { it.tools.size },
        step = plan?.let { minOf(it.done + 1, it.total) },
        steps = plan?.total,
        status = status?.takeIf { kind == LiveTurnKind.Status },
        // The card's room: the newest tools of the turn, oldest first, across a mid-turn correction.
        recent = segments.flatMap { it.tools }.takeLast(MAX_RECENT_TOOLS).map { tool ->
            LiveTool(tool.name, tool.liveDetail(), tool.liveState())
        },
    )
}

private fun ToolActivity.liveState(): LiveToolState = when {
    failed -> LiveToolState.Failed
    running -> LiveToolState.Running
    else -> LiveToolState.Done
}

/**
 * The tool's own line, by the app's rule ([firstDetailLine] in ChatScreen.kt): its detail, else what it was
 * given unless that looks like JSON, then the first non-empty line, clipped.
 */
private fun ToolActivity.liveDetail(): String? =
    (detail?.takeIf { it.isNotBlank() } ?: input?.takeUnless { it.trimStart().let { s -> s.startsWith("{") || s.startsWith("[") } })
        ?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        ?.let { if (it.length > MAX_DETAIL) it.take(MAX_DETAIL).trimEnd() + "…" else it }

private const val MAX_DETAIL = 160
private const val MAX_RECENT_TOOLS = 4
