package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class SubagentStatus {
    Queued,
    Running,
    Done,
    Failed,
    Interrupted,

    /** Sent off to run in the background, or its fate is unknown here: nothing in this chat is watching it. */
    Unwatched,
    ;

    val live: Boolean get() = this == Queued || this == Running
}

/** A delegated child agent, as its relayed `subagent.*` events describe it. */
data class Subagent(
    val id: String,
    /** The subagent that spawned this one; null for one the chat's own agent delegated. */
    val parentId: String? = null,
    /** The `delegate_task` call it belongs to (top-level ones only; nested ones hang off [parentId]). */
    val toolId: String? = null,
    val goal: String = "",
    val status: SubagentStatus = SubagentStatus.Running,
    val taskIndex: Int = 0,
    val model: String? = null,
    val toolCount: Int? = null,
    /** Its tool calls and progress lines, oldest first. */
    val activity: List<String> = emptyList(),
    val summary: String? = null,
    val durationSeconds: Double? = null,
    val filesWritten: List<String> = emptyList(),
)

/** One task a `delegate_task` call handed out, as its arguments and result describe it. */
data class DelegatedTask(
    val goal: String,
    val status: SubagentStatus,
    val summary: String? = null,
    val durationSeconds: Double? = null,
    val model: String? = null,
)

/** One row of a delegation: a task, what its subagent is doing, and the subagents it spawned in turn. */
data class SubagentRow(
    val key: String,
    val goal: String,
    val status: SubagentStatus,
    val model: String? = null,
    val durationSeconds: Double? = null,
    val activity: List<String> = emptyList(),
    val summary: String? = null,
    val toolCount: Int? = null,
    val filesWritten: List<String> = emptyList(),
    /** The live subagent's id, to stop it; null when only the transcript knows of it. */
    val subagentId: String? = null,
    val children: List<SubagentRow> = emptyList(),
)

internal const val DELEGATE_TOOL = "delegate_task"

/**
 * What a `delegate_task` call dispatched (its `goal` or `tasks[].goal`) and how each task ended (the
 * result's `results[]`). A background call answers `dispatched` and its tasks run on unwatched;
 * a call with no result yet is still running. [knownGoals] stand in when [args] aren't at hand.
 */
internal fun delegatedTasks(args: JsonElement?, result: JsonElement?, knownGoals: List<String> = emptyList()): List<DelegatedTask> {
    val call = args.asObject()
    val answer = result.asObject()
    val dispatched = answer.string("status") == "dispatched"
    val goals = call?.get("tasks").asObjectList().mapIndexed { i, task -> task.string("goal")?.trim()?.ifEmpty { null } ?: "Task ${i + 1}" }
        .ifEmpty { listOfNotNull(call.string("goal")?.trim()?.ifEmpty { null }) }
        .ifEmpty { knownGoals }
        .ifEmpty { if (dispatched) answer?.get("goals").strings() else emptyList() }
    val entries = answer?.get("results").asObjectList()
    val failure = answer.string("error")?.trim()?.ifEmpty { null }
    val titles = goals.ifEmpty { entries.map { "Delegated task" } }
    return titles.mapIndexed { i, goal ->
        val entry = entries.firstOrNull { it.int("task_index") == i } ?: entries.getOrNull(i)?.takeIf { it.int("task_index") == null }
        when {
            entry != null -> DelegatedTask(
                goal = goal,
                status = settledStatus(entry.string("status")),
                summary = (entry.string("summary") ?: entry.string("error"))?.trim()?.ifEmpty { null },
                durationSeconds = entry.double("duration_seconds"),
                model = entry.string("model")?.ifBlank { null },
            )
            dispatched -> DelegatedTask(goal, SubagentStatus.Unwatched)
            answer == null && result == null -> DelegatedTask(goal, SubagentStatus.Running)
            failure != null -> DelegatedTask(goal, SubagentStatus.Failed, summary = failure)
            else -> DelegatedTask(goal, SubagentStatus.Unwatched)
        }
    }
}

/** A finished task's `status`: only a success reads as done (timeouts and errors are failures). */
private fun settledStatus(status: String?): SubagentStatus = when (status.orEmpty()) {
    "", "ok", "completed", "success" -> SubagentStatus.Done
    "interrupted", "cancelled", "canceled" -> SubagentStatus.Interrupted
    else -> SubagentStatus.Failed
}

/** A relayed `status`; a `subagent.complete` with anything but a known end reads as a failure, never as still running. */
private fun eventStatus(status: String?, terminal: Boolean): SubagentStatus = when (status) {
    "completed", "ok" -> SubagentStatus.Done
    "failed", "error", "timeout" -> SubagentStatus.Failed
    "interrupted", "cancelled", "canceled" -> SubagentStatus.Interrupted
    "queued" -> if (terminal) SubagentStatus.Failed else SubagentStatus.Queued
    else -> if (terminal) SubagentStatus.Failed else SubagentStatus.Running
}

/** Folds one `subagent.*` event in. A new top-level subagent is tied to the `delegate_task` call that spawned it. */
internal fun ChatState.withSubagentEvent(type: String, payload: JsonObject?): ChatState {
    if (payload == null) return this
    val id = payload.string("subagent_id")?.ifBlank { null }
        ?: "${payload.string("parent_id") ?: "root"}:${payload.int("task_index") ?: 0}:${payload.string("goal").orEmpty()}"
    val previous = subagents.firstOrNull { it.id == id }
    val terminal = type == "subagent.complete"
    val parentId = payload.string("parent_id")?.ifBlank { null } ?: previous?.parentId
    val goal = payload.string("goal")?.trim()?.ifEmpty { null } ?: previous?.goal
        ?: payload.string("text")?.trim().orEmpty().takeIf { type == "subagent.spawn_requested" || type == "subagent.start" }.orEmpty()
    val status = when (type) {
        "subagent.spawn_requested" -> previous?.status?.takeUnless { it == SubagentStatus.Queued } ?: SubagentStatus.Queued
        else -> eventStatus(payload.string("status"), terminal)
    }
    val lines = buildList {
        payload["output_tail"].asObjectList().forEach { tail ->
            val tool = tail.string("tool")
            val preview = tail.string("preview").orEmpty()
            (if (tool != null) toolLine(tool, preview) else compact(preview)).takeIf { it.isNotEmpty() }?.let(::add)
        }
        when (type) {
            "subagent.tool" -> payload.string("tool_name")?.let { add(toolLine(it, payload.string("tool_preview") ?: payload.string("text").orEmpty())) }
            "subagent.progress", "subagent.thinking" -> compact(payload.string("text").orEmpty().removePrefix("🔀")).takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
    val updated = Subagent(
        id = id,
        parentId = parentId,
        toolId = previous?.toolId ?: if (parentId == null) delegationFor(goal) else null,
        goal = goal,
        status = status,
        taskIndex = payload.int("task_index") ?: previous?.taskIndex ?: 0,
        model = payload.string("model")?.ifBlank { null } ?: previous?.model,
        toolCount = payload.int("tool_count") ?: previous?.toolCount,
        activity = lines.fold(previous?.activity.orEmpty()) { list, line -> if (list.lastOrNull() == line) list else (list + line).takeLast(MAX_ACTIVITY) },
        summary = payload.string("summary")?.trim()?.ifEmpty { null }.takeIf { terminal } ?: previous?.summary,
        durationSeconds = payload.double("duration_seconds") ?: previous?.durationSeconds,
        filesWritten = payload["files_written"].strings().ifEmpty { previous?.filesWritten.orEmpty() },
    )
    val list = if (previous == null) subagents + updated else subagents.map { if (it.id == id) updated else it }
    return copy(subagents = list.trimFinished())
}

/**
 * `subagent.list` snapshots, for children already running when this chat attached. They keep any
 * activity already seen; a snapshot can't say which call spawned one, so its goal decides.
 */
internal fun ChatState.withSubagentSnapshots(snapshots: List<JsonObject>): ChatState {
    var list = subagents
    for (snapshot in snapshots) {
        val id = snapshot.string("subagent_id")?.ifBlank { null } ?: continue
        val previous = list.firstOrNull { it.id == id }
        val parentId = snapshot.string("parent_id")?.ifBlank { null } ?: previous?.parentId
        val goal = snapshot.string("goal")?.trim()?.ifEmpty { null } ?: previous?.goal.orEmpty()
        val updated = (previous ?: Subagent(id)).copy(
            parentId = parentId,
            toolId = previous?.toolId ?: if (parentId == null) delegationFor(goal, runningOnly = false) else null,
            goal = goal,
            status = eventStatus(snapshot.string("status"), terminal = false).takeIf { snapshot.string("status") != null } ?: previous?.status ?: SubagentStatus.Running,
            model = snapshot.string("model")?.ifBlank { null } ?: previous?.model,
            toolCount = snapshot.int("tool_count") ?: previous?.toolCount,
            activity = previous?.activity?.takeIf { it.isNotEmpty() } ?: listOfNotNull(snapshot.string("last_tool")?.ifBlank { null }),
        )
        list = if (previous == null) list + updated else list.map { if (it.id == id) updated else it }
    }
    return copy(subagents = list.trimFinished())
}

/**
 * The `delegate_task` call a new top-level subagent came from: the one that lists its goal, else the
 * latest still running. Newest first, so a goal reused later finds the later call.
 */
private fun ChatState.delegationFor(goal: String, runningOnly: Boolean = true): String? {
    val calls = messages.asReversed().asSequence()
        .filterIsInstance<ChatMessage.Assistant>()
        .flatMap { it.tools.asReversed() }
        .filter { it.name == DELEGATE_TOOL }
        .toList()
    val wanted = goal.normalized()
    return calls.firstOrNull { call -> (!runningOnly || call.running) && wanted.isNotEmpty() && call.tasks.any { it.goal.normalized() == wanted } }?.id
        ?: calls.firstOrNull { it.running }?.id
        ?: calls.firstOrNull { call -> !runningOnly && wanted.isNotEmpty() && call.tasks.any { it.goal.normalized() == wanted } }?.id
}

/** Finished subagents beyond the newest [MAX_FINISHED] go; live ones always stay. */
private fun List<Subagent>.trimFinished(): List<Subagent> {
    val finished = count { !it.status.live }
    if (finished <= MAX_FINISHED) return this
    var drop = finished - MAX_FINISHED
    return filter { if (drop > 0 && !it.status.live) false.also { drop-- } else true }
}

/**
 * The rows for one `delegate_task` [call]: each task it handed out, with its subagent's live state
 * laid over it when this chat saw one, and that subagent's own children beneath. Goals tie a task to
 * its subagent; task order does when the goals don't match but the counts do.
 */
fun subagentRows(call: ToolActivity, subagents: List<Subagent>): List<SubagentRow> {
    val goals = call.tasks.mapTo(HashSet()) { it.goal.normalized() }
    // One not tied to a call yet (listed before the transcript loaded) is claimed by its goal.
    val unclaimed = subagents.filter {
        it.parentId == null && (it.toolId == call.id || (it.toolId == null && it.goal.normalized() in goals))
    }.toMutableList()
    // Task order is only trusted when both sides agree on how many tasks there are.
    val sameShape = call.tasks.size == unclaimed.size
    fun claim(predicate: (Subagent) -> Boolean): Subagent? = unclaimed.firstOrNull(predicate)?.also { unclaimed.remove(it) }
    val byGoal = call.tasks.map { task -> claim { it.goal.normalized() == task.goal.normalized() } }
    val rows = call.tasks.mapIndexed { i, task ->
        val live = byGoal[i] ?: if (sameShape) claim { it.taskIndex == i } else null
        if (live != null) {
            row(live, subagents, depth = 0).let { row ->
                row.copy(
                    model = row.model ?: task.model,
                    durationSeconds = row.durationSeconds ?: task.durationSeconds,
                    summary = row.summary ?: task.summary,
                )
            }
        } else {
            SubagentRow(
                key = "${call.id}:$i",
                goal = task.goal,
                // A call that is over can't have a task still running without a subagent saying so.
                status = if (task.status.live && !call.running) SubagentStatus.Unwatched else task.status,
                model = task.model,
                durationSeconds = task.durationSeconds,
                summary = task.summary,
            )
        }
    }
    return rows + unclaimed.map { row(it, subagents, depth = 0) }
}

private fun row(subagent: Subagent, all: List<Subagent>, depth: Int): SubagentRow = SubagentRow(
    key = subagent.id,
    goal = subagent.goal.ifEmpty { "Subagent" },
    status = subagent.status,
    model = subagent.model,
    durationSeconds = subagent.durationSeconds,
    activity = subagent.activity,
    summary = subagent.summary,
    toolCount = subagent.toolCount,
    filesWritten = subagent.filesWritten,
    subagentId = subagent.id.takeIf { subagent.status.live },
    // Bounded, in case a malformed parent chain loops.
    children = if (depth >= MAX_DEPTH) emptyList() else all.filter { it.parentId == subagent.id }.map { row(it, all, depth + 1) },
)

private fun String.normalized(): String = trim().replace(WHITESPACE, " ").lowercase()

private fun compact(text: String): String = text.replace(WHITESPACE, " ").trim().let {
    if (it.length <= MAX_LINE) it else it.take(MAX_LINE - 1).trimEnd() + "…"
}

private fun toolLine(name: String, preview: String): String = compact(preview).let { if (it.isEmpty()) name else "$name: $it" }

private fun JsonElement?.asObject(): JsonObject? = when (this) {
    is JsonObject -> this
    is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.startsWith("{") }?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
    else -> null
}

private fun JsonElement?.strings(): List<String> =
    (this as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.ifEmpty { null } }

private val WHITESPACE = Regex("""\s+""")
private const val MAX_ACTIVITY = 24
private const val MAX_LINE = 200
private const val MAX_FINISHED = 100
private const val MAX_DEPTH = 6
