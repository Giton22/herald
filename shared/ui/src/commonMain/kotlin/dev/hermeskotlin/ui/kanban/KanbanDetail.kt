package dev.hermeskotlin.ui.kanban

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.kanban.KanbanAttachment
import dev.hermeskotlin.core.kanban.KanbanComment
import dev.hermeskotlin.core.kanban.KanbanEvent
import dev.hermeskotlin.core.kanban.KanbanRun
import dev.hermeskotlin.core.kanban.KanbanTaskDetailResponse
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.ScrollbarBox
import dev.hermeskotlin.designsystem.components.SectionLabel
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.designsystem.warningSoft
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.components.relativeTime
import dev.hermeskotlin.ui.resources.*
import dev.hermeskotlin.ui.sessions.CenteredSpinner
import dev.hermeskotlin.ui.sessions.SubpageHeader
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource

/** The open task's drawer, as a full page over the board (back returns to the columns). */
@Composable
internal fun TaskDetail(
    state: KanbanUiState,
    onClose: () -> Unit,
    onReloadDetail: () -> Unit,
    onReloadLog: () -> Unit,
) {
    val detail = state.detail
    if (detail == null) {
        // Loading or failed: the drawer owns the whole page either way, so back stays meaningful.
        Column(Modifier.fillMaxSize()) {
            SubpageHeader(state.openTaskId ?: "", onBack = onClose)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.detailLoading) {
                    CenteredSpinner()
                } else {
                    EmptyState(Lucide.CloudOff, stringResource(Res.string.kanban_error), state.detailError ?: "", error = true) {
                        // Retries the detail load itself — never the worker log below it.
                        Button(stringResource(Res.string.app_try_again), onClick = onReloadDetail, variant = ButtonVariant.Secondary)
                    }
                }
            }
        }
        return
    }
    val task = detail.task
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        SubpageHeader(
            task?.title?.ifBlank { task.id } ?: (state.openTaskId ?: ""),
            onBack = onClose,
            subtitle = task?.id,
        )
        // A quiet reload failed over the open drawer: the content below may be stale, say so.
        if (state.detailError != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .background(Theme[colors][warningSoft], RoundedCornerShape(Theme[radii][radiusMedium]))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UnstyledIcon(Lucide.TriangleAlert, contentDescription = null, tint = Theme[colors][warning], modifier = Modifier.size(14.dp))
                Text(
                    stringResource(Res.string.kanban_task_stale_warning),
                    style = Theme[typography][caption],
                    color = Theme[colors][warning],
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ScrollbarBox(scroll, Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxWidth()
                        .verticalScroll(scroll)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (task != null) MetaSection(task)
                    detail.task?.result?.takeIf { it.isNotBlank() }?.let { ResultSection(stringResource(Res.string.kanban_result), it) }
                        ?: detail.task?.latestSummary?.takeIf { it.isNotBlank() }?.let { ResultSection(stringResource(Res.string.kanban_run_summary), it) }
                    if (detail.childResults.isNotEmpty()) ChildResults(detail)
                    Comments(detail.comments)
                    Events(detail.events)
                    Attachments(detail.attachments)
                    Runs(detail.runs)
                    LogSection(state, onReloadLog)
                    DependenciesSection(state)
                }
            }
        }
    }
}

private fun epochLabel(sec: Long?): String {
    if (sec == null || sec <= 0) return ""
    return relativeTime(sec.toDouble())
}

@Composable
private fun MetaSection(task: dev.hermeskotlin.core.kanban.KanbanTask) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(stringResource(Res.string.kanban_status))
        MetaRow(stringResource(Res.string.kanban_status), task.status)
        MetaRow(stringResource(Res.string.kanban_assignee), task.assignee ?: stringResource(Res.string.kanban_unassigned))
        if (task.priority > 0) MetaRow(stringResource(Res.string.kanban_priority), "P${task.priority}")
        task.tenant?.takeIf { it.isNotBlank() }?.let { MetaRow(stringResource(Res.string.kanban_tenant), it) }
        MetaRow(
            stringResource(Res.string.kanban_workspace),
            listOfNotNull(task.workspaceKind.takeIf { it.isNotBlank() }, task.workspacePath?.takeIf { it.isNotBlank() }).joinToString(": "),
        )
        task.skills?.takeIf { it.isNotEmpty() }?.let { MetaRow(stringResource(Res.string.kanban_skills), it.joinToString(", ")) }
        if (task.goalMode) {
            val on = stringResource(Res.string.kanban_goal_mode_on)
            MetaRow(
                stringResource(Res.string.kanban_goal_mode),
                on + task.goalMaxTurns?.let { " " + stringResource(Res.string.kanban_goal_mode_max_turns, it) }.orEmpty(),
            )
        }
        task.createdBy?.takeIf { it.isNotBlank() }?.let { MetaRow(stringResource(Res.string.kanban_created_by), it) }
        task.createdAt?.takeIf { it > 0 }?.let { MetaRow(stringResource(Res.string.kanban_created), epochLabel(it)) }
        task.startedAt?.takeIf { it > 0 }?.let { MetaRow(stringResource(Res.string.kanban_started), epochLabel(it)) }
        task.completedAt?.takeIf { it > 0 }?.let { MetaRow(stringResource(Res.string.kanban_completed), epochLabel(it)) }
        task.lastFailureError?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary], maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        task.body?.takeIf { it.isNotBlank() }?.let { Body(it) }
        val linkCounts = task.linkCounts
        if (linkCounts != null && (linkCounts.parents + linkCounts.children) > 0) {
            Text(
                stringResource(Res.string.kanban_link_counts, linkCounts.parents, linkCounts.children),
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
            )
        }
        val progress = task.progress
        if (progress != null) {
            Text("${progress.done}/${progress.total}", style = Theme[typography][caption], color = Theme[colors][textTertiary])
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary], modifier = Modifier.weight(1f))
        Text(value, style = Theme[typography][bodySmall], color = Theme[colors][text], modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Body(text: String) {
    Column(
        Modifier.fillMaxWidth().background(Theme[colors][surface], RoundedCornerShape(Theme[radii][radiusLarge]))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text.trim(), style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
    }
}

@Composable
private fun ResultSection(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(title)
        Text(text, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
    }
}

@Composable
private fun ChildResults(detail: KanbanTaskDetailResponse) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(Res.string.kanban_child_results) + " (${detail.childResults.size})")
        detail.childResults.forEach { child ->
            val src = child.result ?: child.latestSummary
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(child.id, style = Theme[typography][code].copy(fontSize = 11.sp), color = Theme[colors][textTertiary])
                Column(Modifier.weight(1f)) {
                    Text(child.title.ifBlank { child.id }, style = Theme[typography][body], color = Theme[colors][text], maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!src.isNullOrBlank()) Text(src, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary], maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Text(child.status, style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1)
            }
        }
    }
}

@Composable
private fun Comments(comments: List<KanbanComment>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(Res.string.kanban_comments) + " (${comments.size})")
        if (comments.isEmpty()) {
            Text(stringResource(Res.string.kanban_no_comments), style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        } else {
            comments.forEach { c ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(c.author.ifBlank { stringResource(Res.string.kanban_anonymous) }, style = Theme[typography][bodySmall].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text], maxLines = 1)
                        Text(c.body, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
                    }
                    Text(epochLabel(c.createdAt), style = Theme[typography][caption], color = Theme[colors][textMuted], maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Events(events: List<KanbanEvent>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(Res.string.kanban_events) + " (${events.size})")
        if (events.isEmpty()) {
            Text(stringResource(Res.string.kanban_no_events), style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        } else {
            events.reversed().take(20).forEach { e ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(e.kind, style = Theme[typography][code].copy(fontSize = 11.sp), color = Theme[colors][textSecondary], modifier = Modifier.weight(1f))
                    Text(epochLabel(e.createdAt), style = Theme[typography][caption], color = Theme[colors][textMuted], maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Attachments(attachments: List<KanbanAttachment>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(Res.string.kanban_attachments) + " (${attachments.size})")
        if (attachments.isEmpty()) {
            Text(stringResource(Res.string.kanban_no_attachments), style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        } else {
            attachments.forEach { a ->
                Row(
                    Modifier.fillMaxWidth().background(Theme[colors][surface], RoundedCornerShape(Theme[radii][radiusLarge])).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.filename, style = Theme[typography][body], color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val size = stringResource(Res.string.kanban_bytes, a.size)
                        Text(
                            a.uploadedBy?.let { "$size · $it" } ?: size,
                            style = Theme[typography][caption],
                            color = Theme[colors][textTertiary],
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Runs(runs: List<KanbanRun>) {
    if (runs.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(Res.string.kanban_run_history) + " (${runs.size})")
        val visible = if (runs.size <= 3) runs else runs.takeLast(3)
        val hidden = runs.size - visible.size
        if (hidden > 0) {
            Text(pluralStringResource(Res.plurals.kanban_runs_earlier, hidden, hidden), style = Theme[typography][caption], color = Theme[colors][textTertiary])
        }
        visible.forEach { r ->
            val summary = r.summary
            val error = r.error
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.outcome ?: r.status, style = Theme[typography][bodySmall].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text], modifier = Modifier.weight(1f))
                Text(epochLabel(r.startedAt), style = Theme[typography][caption], color = Theme[colors][textMuted], maxLines = 1)
            }
            if (!summary.isNullOrBlank()) {
                Text(summary, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary], maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (!error.isNullOrBlank()) {
                Text(error, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary], maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun LogSection(state: KanbanUiState, onReload: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(Res.string.kanban_worker_log))
        val log = state.log
        when {
            state.logLoading -> CenteredSpinner()
            state.logError != null && log == null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.logError, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
                // The log's own retry: fetches the log again, leaving the loaded detail alone.
                Button(stringResource(Res.string.app_try_again), onClick = onReload, variant = ButtonVariant.Secondary)
            }
            log == null || !log.exists -> Text(stringResource(Res.string.kanban_log_not_found), style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
            else -> {
                if (log.truncated) {
                    Text(stringResource(Res.string.kanban_log_too_long), style = Theme[typography][caption], color = Theme[colors][textTertiary])
                }
                Text(
                    log.content,
                    style = Theme[typography][code].copy(fontSize = 11.sp),
                    color = Theme[colors][textSecondary],
                    modifier = Modifier.fillMaxWidth().background(Theme[colors][surface], RoundedCornerShape(Theme[radii][radiusLarge])).padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun DependenciesSection(state: KanbanUiState) {
    val detail = state.detail ?: return
    // Guard on the links themselves: the gateway drops missing/deleted rows from link_tasks, so a task
    // whose parent was deleted must not lose the whole section — the id is the fallback label.
    val parents = detail.links.parents
    val children = detail.links.children
    if (parents.isEmpty() && children.isEmpty()) return
    val titles = detail.linkTasks.associate { it.id to it.title }
    fun label(id: String): String = titles[id]?.takeIf { it.isNotBlank() } ?: id
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(Res.string.kanban_dependencies))
        if (parents.isNotEmpty()) {
            Text(stringResource(Res.string.kanban_parents), style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
            parents.forEach { id -> LinkedTaskRow(id, label(id)) }
        }
        if (children.isNotEmpty()) {
            Text(stringResource(Res.string.kanban_children), style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
            children.forEach { id -> LinkedTaskRow(id, label(id)) }
        }
    }
}

@Composable
private fun LinkedTaskRow(id: String, label: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(id, style = Theme[typography][code].copy(fontSize = 11.sp), color = Theme[colors][textTertiary], maxLines = 1)
        Text(label, style = Theme[typography][bodySmall], color = Theme[colors][text], modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
