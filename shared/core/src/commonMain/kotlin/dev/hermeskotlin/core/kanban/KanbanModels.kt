package dev.hermeskotlin.core.kanban

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Kanban board shapes, from the gateway's Kanban dashboard plugin (`plugins/kanban/dashboard/plugin_api.py`,
 * mounted at `/api/plugins/kanban/`).
 *
 * Every handler is a thin wrapper around `hermes_cli.kanban_db`; the REST shapes below mirror its dataclasses
 * and its `_task_dict` enrichment exactly, so missing/extra fields drift loudly in tests instead of silently
 * rendering wrong. Nulls the gateway writes read as defaults (`coerceInputValues` on the lenient JSON).
 */

@Serializable
data class KanbanBoardMeta(
    val slug: String,
    val name: String = slug,
    val description: String = "",
    val icon: String = "",
    val color: String = "",
    @SerialName("default_workdir") val defaultWorkdir: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("project_name") val projectName: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    val archived: Boolean = false,
    @SerialName("is_current") val isCurrent: Boolean = false,
    val counts: Map<String, Int> = emptyMap(),
    val total: Int = 0,
    @SerialName("default_workspace_kind") val defaultWorkspaceKind: String = "scratch",
    @SerialName("db_path") val dbPath: String? = null,
)

@Serializable
data class KanbanBoardsResponse(
    val boards: List<KanbanBoardMeta> = emptyList(),
    val current: String = "default",
)

@Serializable
data class KanbanAge(
    @SerialName("created_age_seconds") val createdAgeSeconds: Long? = null,
    @SerialName("started_age_seconds") val startedAgeSeconds: Long? = null,
    @SerialName("time_to_complete_seconds") val timeToCompleteSeconds: Long? = null,
)

@Serializable
data class KanbanLinkCounts(
    val parents: Int = 0,
    val children: Int = 0,
)

@Serializable
data class KanbanProgress(
    val done: Int = 0,
    val total: Int = 0,
)

@Serializable
data class KanbanWarnings(
    val count: Int = 0,
    val kinds: Map<String, Int> = emptyMap(),
    @SerialName("latest_at") val latestAt: Long? = null,
    @SerialName("highest_severity") val highestSeverity: String? = null,
)

/**
 * One kanban task (`hermes_cli.kanban_db.Task` plus the derived fields the dashboard attaches:
 * `age`, `latest_summary`, `current_run_started_at`, rollups, diagnostics). Field names follow
 * `asdict(task)` so snake_case maps 1-1.
 */
@Serializable
data class KanbanTask(
    val id: String,
    val title: String = "",
    val body: String? = null,
    val assignee: String? = null,
    val status: String = "todo",
    val priority: Int = 0,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("completed_at") val completedAt: Long? = null,
    @SerialName("workspace_kind") val workspaceKind: String = "scratch",
    @SerialName("workspace_path") val workspacePath: String? = null,
    @SerialName("branch_name") val branchName: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    val result: String? = null,
    @SerialName("idempotency_key") val idempotencyKey: String? = null,
    @SerialName("consecutive_failures") val consecutiveFailures: Int = 0,
    @SerialName("worker_pid") val workerPid: Int? = null,
    @SerialName("last_failure_error") val lastFailureError: String? = null,
    @SerialName("max_runtime_seconds") val maxRuntimeSeconds: Int? = null,
    @SerialName("last_heartbeat_at") val lastHeartbeatAt: Long? = null,
    @SerialName("current_run_id") val currentRunId: Int? = null,
    @SerialName("workflow_template_id") val workflowTemplateId: String? = null,
    @SerialName("current_step_key") val currentStepKey: String? = null,
    val skills: List<String>? = null,
    @SerialName("model_override") val modelOverride: String? = null,
    @SerialName("provider_override") val providerOverride: String? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("max_retries") val maxRetries: Int? = null,
    @SerialName("goal_mode") val goalMode: Boolean = false,
    @SerialName("goal_max_turns") val goalMaxTurns: Int? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("block_kind") val blockKind: String? = null,
    @SerialName("block_recurrences") val blockRecurrences: Int = 0,
    @SerialName("completion_contract") val completionContract: String? = null,
    @SerialName("claim_lock") val claimLock: String? = null,
    @SerialName("claim_expires") val claimExpires: Long? = null,
    val tenant: String? = null,
    val age: KanbanAge? = null,
    @SerialName("latest_summary") val latestSummary: String? = null,
    @SerialName("current_run_started_at") val currentRunStartedAt: Long? = null,
    @SerialName("link_counts") val linkCounts: KanbanLinkCounts? = null,
    @SerialName("comment_count") val commentCount: Int = 0,
    val progress: KanbanProgress? = null,
    val warnings: KanbanWarnings? = null,
    val diagnostics: List<KanbanDiagnostic> = emptyList(),
)

@Serializable
data class KanbanDiagnosticAction(
    val kind: String = "",
    val label: String = "",
    val payload: JsonElement? = null,
    val suggested: Boolean = false,
)

@Serializable
data class KanbanDiagnostic(
    val kind: String = "",
    val severity: String = "warning",
    val title: String = "",
    val detail: String = "",
    val actions: List<KanbanDiagnosticAction> = emptyList(),
    @SerialName("first_seen_at") val firstSeenAt: Long = 0,
    @SerialName("last_seen_at") val lastSeenAt: Long = 0,
    val count: Int = 1,
    @SerialName("run_id") val runId: Int? = null,
    val data: JsonElement? = null,
)

@Serializable
data class KanbanColumn(
    val name: String,
    val tasks: List<KanbanTask> = emptyList(),
)

@Serializable
data class KanbanBoardResponse(
    val columns: List<KanbanColumn> = emptyList(),
    val tenants: List<String> = emptyList(),
    val assignees: List<String> = emptyList(),
    @SerialName("latest_event_id") val latestEventId: Long = 0,
    val now: Long = 0,
)

@Serializable
data class KanbanComment(
    val id: Int = 0,
    @SerialName("task_id") val taskId: String = "",
    val author: String = "",
    val body: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class KanbanEvent(
    val id: Int = 0,
    @SerialName("task_id") val taskId: String = "",
    val kind: String = "",
    val payload: JsonElement? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("run_id") val runId: Int? = null,
)

@Serializable
data class KanbanRun(
    val id: Int = 0,
    @SerialName("task_id") val taskId: String = "",
    val profile: String? = null,
    @SerialName("step_key") val stepKey: String? = null,
    val status: String = "",
    @SerialName("claim_lock") val claimLock: String? = null,
    @SerialName("claim_expires") val claimExpires: Long? = null,
    @SerialName("worker_pid") val workerPid: Int? = null,
    @SerialName("max_runtime_seconds") val maxRuntimeSeconds: Int? = null,
    @SerialName("last_heartbeat_at") val lastHeartbeatAt: Long? = null,
    @SerialName("started_at") val startedAt: Long = 0,
    @SerialName("ended_at") val endedAt: Long? = null,
    val outcome: String? = null,
    val summary: String? = null,
    val metadata: JsonElement? = null,
    val error: String? = null,
)

@Serializable
data class KanbanAttachment(
    val id: Int = 0,
    @SerialName("task_id") val taskId: String = "",
    val filename: String = "",
    @SerialName("content_type") val contentType: String? = null,
    val size: Int = 0,
    @SerialName("uploaded_by") val uploadedBy: String? = null,
    @SerialName("stored_path") val storedPath: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class KanbanLinks(
    val parents: List<String> = emptyList(),
    val children: List<String> = emptyList(),
)

@Serializable
data class KanbanLinkedTask(
    val id: String,
    val title: String = "",
    val status: String = "",
)

@Serializable
data class KanbanChildResult(
    val id: String,
    val title: String = "",
    val status: String = "",
    @SerialName("latest_summary") val latestSummary: String? = null,
    val result: String? = null,
)

@Serializable
data class KanbanTaskDetailResponse(
    val task: KanbanTask? = null,
    val comments: List<KanbanComment> = emptyList(),
    val events: List<KanbanEvent> = emptyList(),
    val attachments: List<KanbanAttachment> = emptyList(),
    val links: KanbanLinks = KanbanLinks(),
    @SerialName("link_tasks") val linkTasks: List<KanbanLinkedTask> = emptyList(),
    @SerialName("child_results") val childResults: List<KanbanChildResult> = emptyList(),
    val runs: List<KanbanRun> = emptyList(),
)

@Serializable
data class KanbanLogResponse(
    @SerialName("task_id") val taskId: String = "",
    val path: String = "",
    val exists: Boolean = false,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val content: String = "",
    val truncated: Boolean = false,
)

/** One frame the `/api/plugins/kanban/events` socket sends: appended events since the cursor. */
@Serializable
data class KanbanEventsFrame(
    val events: List<KanbanEvent> = emptyList(),
    val cursor: Long = 0,
)
