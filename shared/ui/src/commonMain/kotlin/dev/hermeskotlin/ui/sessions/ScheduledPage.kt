package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Zap
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.components.relativeTime
import dev.hermeskotlin.ui.components.timeUntil
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel

/**
 * The sidebar's Scheduled page: the gateway's cron jobs, and inside a job its runs. A run is a chat,
 * so it opens like any other session, but only from its job.
 */
@Composable
internal fun ScheduledPage(
    gateway: SavedGateway,
    visible: Boolean,
    selectedId: String?,
    onBack: () -> Unit,
    onOpenRun: (SessionSummary) -> Unit,
    onSessionExpired: () -> Unit,
    viewModel: ScheduledViewModel = koinViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val job = state.openJob

    LaunchedEffect(gateway) { viewModel.bind(gateway) }
    LaunchedEffect(Unit) { viewModel.refresh() }
    // Consume before the callback: the route changes on it, and the view model outlives the screen,
    // so a stale flag must not bounce the next mount after a fresh sign-in.
    LaunchedEffect(state.sessionExpired) {
        if (state.sessionExpired) {
            viewModel.consumeSessionExpired()
            onSessionExpired()
        }
    }
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(4_000)
            viewModel.dismissMessage()
        }
    }
    PlatformBackHandler(enabled = visible && job != null && state.editor == null) { viewModel.closeJob() }
    PlatformBackHandler(enabled = visible && state.editor != null) { viewModel.closeEditor() }

    Column(Modifier.fillMaxSize()) {
        if (state.editor != null) {
            JobEditorPage(
                state.editor,
                prompt = viewModel.prompt,
                schedule = viewModel.schedule,
                name = viewModel.name,
                deliveryTargets = state.deliveryTargets,
                onSetDeliver = viewModel::setDeliver,
                onSave = viewModel::saveJob,
                onClose = viewModel::closeEditor,
            )
        } else if (job == null) {
            SubpageHeader("Scheduled", onBack = onBack) {
                IconButton(Lucide.Plus, contentDescription = "New job", onClick = viewModel::newJob, tint = Theme[colors][text])
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> CenteredSpinner()
                    state.error != null -> EmptyState(Lucide.CloudOff, "Couldn't load scheduled jobs", state.error) {
                        Button("Try again", onClick = viewModel::refresh, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
                    }
                    state.jobs.isEmpty() ->
                        EmptyState(Lucide.CalendarClock, "No scheduled jobs", "Have the agent do something on a schedule, like a morning briefing. Each run opens as a chat here.") {
                            Button("New job", onClick = viewModel::newJob, variant = ButtonVariant.Secondary, leadingIcon = Lucide.Plus)
                        }
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
                    ) {
                        items(state.jobs, key = { it.id }) { item -> JobRow(item, onClick = { viewModel.openJob(item.id) }) }
                    }
                }
            }
        } else {
            SubpageHeader(job.displayName, onBack = viewModel::closeJob) {
                IconButton(Lucide.Pencil, contentDescription = "Edit job", onClick = viewModel::editJob, enabled = !state.busy, tint = Theme[colors][text])
                IconButton(Lucide.Trash2, contentDescription = "Delete job", onClick = viewModel::askDelete, enabled = !state.busy, tint = Theme[colors][text])
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
            ) {
                item(key = "summary") {
                    JobSummary(job, busy = state.busy, onRunNow = viewModel::runNow, onTogglePaused = viewModel::togglePaused)
                }
                item(key = "runs-header") {
                    Text(
                        "RUNS",
                        style = Theme[typography][caption],
                        color = Theme[colors][textTertiary],
                        modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                when {
                    state.runsLoading -> item(key = "runs-loading") { ListSpinner() }
                    state.runsError != null -> item(key = "runs-error") {
                        ListNotice("Couldn't load runs. ${state.runsError}", action = "Try again", onAction = viewModel::refresh)
                    }
                    state.runs.isEmpty() -> item(key = "runs-empty") { ListNotice("No runs yet. Each run opens as a chat here.") }
                    else -> items(state.runs, key = { it.id }) { run ->
                        RunRow(run, selected = run.id == selectedId, onClick = { onOpenRun(run) })
                    }
                }
            }
        }
        state.message?.let { MessageBanner(it, onDismiss = viewModel::dismissMessage, modifier = Modifier.padding(bottom = 96.dp)) }
    }

    Dialog(
        visible = state.confirmingDelete,
        onDismissRequest = viewModel::cancelDelete,
        title = "Delete job?",
        message = "“${job?.displayName.orEmpty()}” stops running. The chats from its past runs stay.",
        actions = {
            Button("Cancel", onClick = viewModel::cancelDelete, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Delete", onClick = viewModel::deleteJob, variant = ButtonVariant.Danger, size = ButtonSize.Small)
        },
    )
}

@Composable
private fun JobRow(job: CronJob, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(job.displayName, style = Theme[typography][body], color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(job.statusLine(), style = Theme[typography][bodySmall], color = Theme[colors][textSecondary], maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StateDot(job)
    }
}

/** What the job does, when it runs, and the two things you can do with it. */
@Composable
private fun JobSummary(job: CronJob, busy: Boolean, onRunNow: () -> Unit, onTogglePaused: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(
        Modifier.fillMaxWidth().background(Theme[colors][stroke], shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StateDot(job)
            Text(job.statusLine(), style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
        }
        if (job.prompt.isNotBlank()) {
            Text(job.prompt.trim(), style = Theme[typography][bodySmall], color = Theme[colors][text], maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
        job.lastError?.takeIf { it.isNotBlank() && job.lastStatus != "ok" }?.let {
            Text(it, style = Theme[typography][bodySmall], color = Theme[colors][danger], maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("Run now", onClick = onRunNow, size = ButtonSize.Small, leadingIcon = Lucide.Zap, loading = busy, pill = true)
            Button(
                if (job.paused) "Resume" else "Pause",
                onClick = onTogglePaused,
                variant = ButtonVariant.Outline,
                size = ButtonSize.Small,
                leadingIcon = if (job.paused) Lucide.Play else Lucide.Pause,
                enabled = !busy,
                pill = true,
            )
        }
    }
}

@Composable
private fun RunRow(run: SessionSummary, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(Theme[colors][stroke], shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            relativeTime(run.activityAt).let { if (it == "now") "Just now" else "$it ago" },
            style = Theme[typography][body],
            color = Theme[colors][text],
            modifier = Modifier.weight(1f),
        )
        if (run.isActive) Box(Modifier.size(8.dp).background(Theme[colors][success], CircleShape))
        if (run.messageCount > 0) Text("${run.messageCount} msgs", style = Theme[typography][caption], color = Theme[colors][textTertiary])
    }
}

@Composable
private fun StateDot(job: CronJob) {
    val color = when {
        job.state == "error" || job.lastStatus == "error" -> Theme[colors][danger]
        job.paused || job.state == "completed" -> Theme[colors][textTertiary]
        else -> Theme[colors][success]
    }
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

private fun CronJob.statusLine(): String = listOfNotNull(
    scheduleDisplay.takeIf { it.isNotBlank() && it != "?" }?.let(::readableSchedule),
    when {
        paused -> "paused"
        state == "completed" -> "finished"
        else -> when (val until = timeUntil(nextRunEpochSeconds)) {
            "" -> null
            "now" -> "due now"
            else -> "next $until"
        }
    },
).joinToString(" · ")

/**
 * Plain words for the common five-field cron shapes ("0 9 * * *" → "Daily at 09:00"); anything
 * else, and the gateway's own phrasings like "every 30m", pass through.
 */
internal fun readableSchedule(display: String): String {
    val fields = display.trim().split(Regex("\\s+"))
    if (fields.size != 5) return display
    val (minute, hour, dayOfMonth, month, dayOfWeek) = fields
    val m = minute.toIntOrNull() ?: return display
    val h = hour.toIntOrNull() ?: return display
    if (dayOfMonth != "*" || month != "*" || m !in 0..59 || h !in 0..23) return display
    val time = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
    return when (dayOfWeek) {
        "*" -> "Daily at $time"
        "1-5" -> "Weekdays at $time"
        else -> dayOfWeek.toIntOrNull()?.let { WEEKDAYS.getOrNull(it % 7) }?.let { "${it}s at $time" } ?: display
    }
}

private val WEEKDAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
