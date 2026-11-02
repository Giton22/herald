package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.ui.PageFoot
import dev.hermeskotlin.ui.arrowFocus
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import com.composables.icons.lucide.AppWindow
import dev.hermeskotlin.designsystem.rememberHoverIndication

import dev.hermeskotlin.designsystem.components.handCursor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import dev.hermeskotlin.core.cron.DeliveryTarget
import dev.hermeskotlin.core.cron.Routines
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import dev.hermeskotlin.designsystem.components.ScrollbarBox
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.hermeskotlin.ui.CopyableOnDesktop
import dev.hermeskotlin.ui.isDesktop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.TriangleAlert
import com.composeunstyled.UnstyledIcon
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.dangerSoft
import dev.hermeskotlin.designsystem.eyebrow
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.textMuted
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
import dev.hermeskotlin.core.cron.missedLastFire
import dev.hermeskotlin.core.cron.problem
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
import dev.hermeskotlin.ui.components.ListSkeleton
import dev.hermeskotlin.ui.components.relativeTime
import dev.hermeskotlin.ui.components.timeUntil
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel

/**
 * The sidebar's Scheduled page: the gateway's cron jobs, and inside a job its runs. A run is a chat,
 * so it opens like any other session, but only from its job. With an [owner] it is that bot's
 * Routines: the jobs it runs as itself, each reporting to its chat if the user likes.
 */
@Composable
internal fun ScheduledPage(
    gateway: SavedGateway,
    visible: Boolean,
    selectedId: String?,
    onBack: () -> Unit,
    onOpenRun: (SessionSummary) -> Unit,
    onSessionExpired: () -> Unit,
    owner: RoutineOwner? = null,
    /** Opens a run in a window of its own (a desktop's Ctrl+click or menu), where one can open. */
    onPopOutRun: ((SessionSummary) -> Unit)? = null,
    // Each bot's routines keep their own page state, apart from the full list's.
    viewModel: ScheduledViewModel = koinViewModel(key = owner?.let { "routines-${it.profile}" }),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val job = state.openJob

    LaunchedEffect(gateway, owner) { viewModel.bind(gateway, owner) }
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
    // Leaving the editor on the desktop asks before typed changes are lost.
    val edited = viewModel::editorChanged
    var leaving by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Whatever puts the editor away (Escape, Cancel, another job in the list) goes through here.
    val leaveEditor: (() -> Unit) -> Unit = { then ->
        when {
            viewModel.state.value.editor == null -> then()
            isDesktop && edited() -> leaving = then
            else -> {
                viewModel.closeEditor()
                then()
            }
        }
    }

    PlatformBackHandler(enabled = visible && job != null && state.editor == null) { viewModel.closeJob() }
    PlatformBackHandler(enabled = visible && state.editor != null) { leaveEditor {} }

    val editorPane: (@Composable () -> Unit)? = state.editor?.let { editor ->
        {
            JobEditorPage(
                editor,
                prompt = viewModel.prompt,
                schedule = viewModel.schedule,
                name = viewModel.name,
                deliveryTargets = state.deliveryTargets,
                onSetDeliver = viewModel::setDeliver,
                onSave = viewModel::saveJob,
                onClose = { leaveEditor {} },
                routineOf = owner?.label,
            )
        }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ScheduledView(
                state,
                viewModel,
                // A run opened from here is the one marked when the page comes back, as the page is put away for it.
                selectedId = selectedId ?: viewModel.lastOpenedRun,
                onBack = onBack,
                onOpenRun = { run ->
                    viewModel.lastOpenedRun = run.id
                    onOpenRun(run)
                },
                owner = owner,
                editorPane = editorPane,
                leaveEditor = leaveEditor,
                onPopOutRun = onPopOutRun?.let { popOut ->
                    { run: SessionSummary ->
                        viewModel.lastOpenedRun = run.id
                        popOut(run)
                    }
                },
            )
        }
        state.message?.let { MessageBanner(it, onDismiss = viewModel::dismissMessage, modifier = Modifier.padding(bottom = PageFoot)) }
    }
    Dialog(
        visible = leaving != null,
        onDismissRequest = { leaving = null },
        title = "Discard changes?",
        message = "What you changed in this ${if (owner != null) "routine" else "job"} isn't saved yet.",
        actions = {
            Button("Keep editing", onClick = { leaving = null }, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Discard", onClick = {
                val then = leaving
                leaving = null
                viewModel.closeEditor()
                then?.invoke()
            }, variant = ButtonVariant.Danger, size = ButtonSize.Small)
        },
    )
}

/** What the Scheduled list and a job's page can ask for; [ScheduledViewModel] does it all, previews nothing. */
interface ScheduledActions {
    fun refresh()
    fun newJob()
    fun openJob(jobId: String)
    fun closeJob()
    fun editJob()
    fun runNow()
    fun togglePaused()
    fun askDelete()
    fun cancelDelete()
    fun deleteJob()
}

/** The jobs list and a job's page, apart from the view model, so previews can draw them from sample data. */
@Composable
internal fun ScheduledView(
    state: ScheduledUiState,
    actions: ScheduledActions,
    selectedId: String?,
    onBack: () -> Unit,
    onOpenRun: (SessionSummary) -> Unit,
    owner: RoutineOwner? = null,
    /** The open editor, if any: beside the list on a wide desktop page, else the page itself. */
    editorPane: (@Composable () -> Unit)? = null,
    /** Runs what leaves the editor (another job picked, a new one started) once it's put away. */
    leaveEditor: (() -> Unit) -> Unit = { it() },
    onPopOutRun: ((SessionSummary) -> Unit)? = null,
) {
    val job = state.openJob
    val routines = owner != null
    val noun = if (routines) "routine" else "job"
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A desktop page wide enough for both lists the jobs on the left and the open one on the right, as a
        // desktop mail or task app does, so a job is a click away from the next one without a trip back. With no
        // jobs yet (or none loaded) the one message takes the page.
        val split = isDesktop && maxWidth >= SPLIT_WIDTH && !state.loading && state.error == null && state.jobs.isNotEmpty()
        if (split) {
            Column(Modifier.fillMaxSize()) {
                JobsHeader(state, actions, owner, onBack, leaveEditor)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.width(SPLIT_LIST_WIDTH).fillMaxHeight()) {
                        // A new job being written isn't the one that was open: nothing in the list is marked.
                        JobsList(state, actions, owner, openId = job?.id?.takeIf { state.editor?.isNew != true }, leaveEditor = leaveEditor, start = 14.dp, end = 8.dp)
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().padding(bottom = PageFoot).background(Theme[colors][stroke]))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (editorPane != null) {
                            editorPane()
                        } else if (job == null) {
                            EmptyState(Lucide.CalendarClock, "Pick a $noun", "Its prompt, where it reports and its runs show here.")
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                // The pane's own head: the job, how it stands, and Edit and Delete beside it.
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            job.displayName,
                                            style = Theme[typography][body].copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                                            color = Theme[colors][text],
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.semantics { heading() },
                                        )
                                        job.statusLine().takeIf { it.isNotEmpty() }?.let {
                                            Text(it, style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp), color = Theme[colors][textTertiary], maxLines = 1)
                                        }
                                    }
                                    JobActions(noun, state.busy, actions)
                                }
                                // Each job's runs scroll from their own top.
                                key(job.id) {
                                    JobDetail(job, state, actions, owner, selectedId, onOpenRun, onPopOutRun, Modifier.weight(1f).fillMaxWidth(), start = 20.dp)
                                }
                            }
                        }
                    }
                }
            }
        } else if (editorPane != null) {
            editorPane()
        } else if (job == null) {
            Column(Modifier.fillMaxSize()) {
                JobsHeader(state, actions, owner, onBack, leaveEditor)
                Box(Modifier.weight(1f).fillMaxWidth()) { JobsList(state, actions, owner, openId = null, leaveEditor = leaveEditor) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                SubpageHeader(job.displayName, onBack = actions::closeJob, subtitle = job.statusLine().ifEmpty { null }) {
                    JobActions(noun, state.busy, actions)
                }
                JobDetail(job, state, actions, owner, selectedId, onOpenRun, onPopOutRun, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    Dialog(
        visible = state.confirmingDelete,
        onDismissRequest = actions::cancelDelete,
        title = if (routines) "Delete routine?" else "Delete job?",
        message = "“${job?.displayName.orEmpty()}” stops running. The chats from its past runs stay.",
        actions = {
            Button("Cancel", onClick = actions::cancelDelete, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Delete", onClick = actions::deleteJob, variant = ButtonVariant.Danger, size = ButtonSize.Small)
        },
    )
}

/** Wide enough for the desktop's jobs list and a job's pane side by side. */
private val SPLIT_WIDTH = 720.dp

/** The desktop's jobs list beside a job's pane. */
private val SPLIT_LIST_WIDTH = 320.dp

@Composable
private fun JobsHeader(state: ScheduledUiState, actions: ScheduledActions, owner: RoutineOwner?, onBack: () -> Unit, leaveEditor: (() -> Unit) -> Unit) {
    val count = state.jobs.size
    SubpageHeader(
        owner?.let { "${it.label}'s routines" } ?: "Scheduled",
        onBack = onBack,
        subtitle = when {
            state.loading || state.error != null || count == 0 -> null
            owner != null -> "$count ${if (count == 1) "routine" else "routines"} · each runs as ${owner.label}"
            else -> "$count ${if (count == 1) "job" else "jobs"} · each run opens as a chat"
        },
    ) {
        Button(
            if (owner != null) "New routine" else "New job",
            onClick = { leaveEditor(actions::newJob) },
            size = ButtonSize.Small,
            leadingIcon = Lucide.Plus,
            modifier = Modifier.heightIn(min = MinTouchTarget),
        )
    }
}

@Composable
private fun JobActions(noun: String, busy: Boolean, actions: ScheduledActions) {
    IconButton(Lucide.Pencil, contentDescription = "Edit $noun", onClick = actions::editJob, enabled = !busy, tint = Theme[colors][textSecondary])
    IconButton(Lucide.Trash2, contentDescription = "Delete $noun", onClick = actions::askDelete, enabled = !busy, tint = Theme[colors][textSecondary])
}

/** The jobs, or why there are none; [openId] marks the one open beside the list on the desktop. */
@Composable
private fun JobsList(
    state: ScheduledUiState,
    actions: ScheduledActions,
    owner: RoutineOwner?,
    openId: String?,
    leaveEditor: (() -> Unit) -> Unit,
    start: Dp = 14.dp,
    end: Dp = 14.dp,
) {
    val routines = owner != null
    when {
        state.loading -> ListSkeleton()
        state.error != null -> EmptyState(Lucide.CloudOff, if (routines) "Couldn't load routines" else "Couldn't load scheduled jobs", state.error, error = true) {
            Button("Try again", onClick = actions::refresh, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
        }
        state.jobs.isEmpty() && owner != null ->
            EmptyState(
                Lucide.CalendarClock,
                "No routines yet",
                "Have ${owner.label} do something on a schedule, like a morning briefing. It runs as ${owner.label}, " +
                    "with its own memory and skills, and can report to its chat.",
            ) {
                Button("New routine", onClick = actions::newJob, variant = ButtonVariant.Secondary, leadingIcon = Lucide.Plus)
            }
        state.jobs.isEmpty() ->
            EmptyState(Lucide.CalendarClock, "No scheduled jobs", "Have the agent do something on a schedule, like a morning briefing. Each run opens as a chat here.") {
                Button("New job", onClick = actions::newJob, variant = ButtonVariant.Secondary, leadingIcon = Lucide.Plus)
            }
        else -> {
            val list = rememberLazyListState()
            ScrollbarBox(list, Modifier.fillMaxSize(), bottomInset = PageFoot) {
                LazyColumn(
                    Modifier.fillMaxSize().arrowFocus(),
                    state = list,
                    contentPadding = PaddingValues(start = start, end = end, bottom = PageFoot),
                    verticalArrangement = Arrangement.spacedBy(if (isDesktop) 6.dp else 8.dp),
                ) {
                    items(state.jobs, key = { it.id }) { item ->
                        // The job page's own actions, from a right-click on the desktop: each opens the job and
                        // acts on it there, so what it did (a run started, the delete to confirm) is in sight.
                        // The action waits with the opening, so a "Discard changes?" asked first can't aim it at the
                        // job still open.
                        val openThen = { then: () -> Unit ->
                            leaveEditor {
                                actions.openJob(item.id)
                                then()
                            }
                        }
                        val open = { openThen {} }
                        // While a change is on its way, only Open, as the job page's own buttons wait too.
                        JobRow(item, selected = item.id == openId, onClick = open, menu = {
                            val opening = listOf(RowMenuItem("Open", Lucide.CalendarClock, run = open))
                            if (state.busy) opening else opening + listOf(
                                RowMenuItem("Run now", Lucide.Zap) { openThen(actions::runNow) },
                                RowMenuItem(if (item.paused) "Resume" else "Pause", if (item.paused) Lucide.Play else Lucide.Pause) {
                                    openThen(actions::togglePaused)
                                },
                                RowMenuItem("Edit", Lucide.Pencil) { openThen(actions::editJob) },
                                RowMenuItem("Delete", Lucide.Trash2, destructive = true) { openThen(actions::askDelete) },
                            )
                        })
                    }
                }
            }
        }
    }
}

/** A job's summary and its runs, each a chat to open. */
@Composable
private fun JobDetail(
    job: CronJob,
    state: ScheduledUiState,
    actions: ScheduledActions,
    owner: RoutineOwner?,
    selectedId: String?,
    onOpenRun: (SessionSummary) -> Unit,
    onPopOutRun: ((SessionSummary) -> Unit)?,
    modifier: Modifier,
    start: Dp = 14.dp,
) {
    val runsList = rememberLazyListState()
    ScrollbarBox(runsList, modifier, bottomInset = PageFoot) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = runsList,
            contentPadding = PaddingValues(start = start, end = 14.dp, bottom = PageFoot),
        ) {
            item(key = "summary") {
                val deliversTo = job.deliver?.takeIf { it.isNotBlank() }?.let { id ->
                    deliveryLabel(id, state.deliveryTargets, botLabel = owner?.label ?: Routines.taggedBot(job.name))
                }
                JobSummary(job, deliversTo, busy = state.busy, onRunNow = actions::runNow, onTogglePaused = actions::togglePaused)
            }
            item(key = "runs-header") {
                Text(
                    "RUNS",
                    style = Theme[typography][eyebrow],
                    color = Theme[colors][textTertiary],
                    modifier = Modifier.padding(start = 6.dp, top = 20.dp, bottom = 6.dp).semantics {
                        heading()
                        contentDescription = "Runs"
                    },
                )
            }
            when {
                state.runsLoading -> item(key = "runs-loading") { ListSpinner() }
                state.runsError != null -> item(key = "runs-error") {
                    ListNotice("Couldn't load runs. ${state.runsError}", action = "Try again", onAction = actions::refresh)
                }
                state.runs.isEmpty() -> item(key = "runs-empty") { ListNotice("No runs yet. Each run opens as a chat here.") }
                else -> items(state.runs, key = { it.id }) { run ->
                    RunRow(run, selected = run.id == selectedId, onOpen = { onOpenRun(run) }, onPopOut = onPopOutRun?.let { { it(run) } })
                }
            }
        }
    }
}

/** How a job stands, for its tile and its "next" column. */
private enum class JobTone { Ok, Idle, Failed }

private val CronJob.tone: JobTone
    get() = when {
        problem != null -> JobTone.Failed
        paused || state == "completed" -> JobTone.Idle
        else -> JobTone.Ok
    }

/** A ringed card: a tinted tile, the name and schedule, and when it runs next (or that it failed or waits). */
@Composable
private fun JobRow(job: CronJob, selected: Boolean = false, onClick: () -> Unit, menu: () -> List<RowMenuItem>) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    var menuOpen by remember { mutableStateOf(false) }
    RowContextMenu(menuOpen, onDismiss = { menuOpen = false }, items = menu)
    val (tile, tint) = when (job.tone) {
        JobTone.Ok -> Theme[colors][accentSoft] to Theme[colors][accentText]
        JobTone.Idle -> Theme[colors][surface2] to Theme[colors][textTertiary]
        JobTone.Failed -> Theme[colors][dangerSoft] to Theme[colors][danger]
    }
    val (next, nextLabel) = job.nextColumn()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][if (selected) surface2 else surface])
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Theme[colors][accentText].copy(alpha = 0.55f) else Theme[colors][stroke], shape)
            .handCursor()
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .then(if (isDesktop) Modifier.onSecondaryPress { menuOpen = true } else Modifier)
            .padding(if (isDesktop) 12.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(tile, RoundedCornerShape(Theme[radii][radiusSmall])), contentAlignment = Alignment.Center) {
            UnstyledIcon(if (job.tone == JobTone.Failed) Lucide.TriangleAlert else Lucide.CalendarClock, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(job.displayName, style = Theme[typography][body].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis)
            job.scheduleDisplay.takeIf { it.isNotBlank() && it != "?" }?.let {
                Text(readableSchedule(it), style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp), color = Theme[colors][textTertiary], maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (next != null) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(next, style = Theme[typography][code].copy(fontSize = 12.sp), color = tint, maxLines = 1)
                nextLabel?.let { Text(it, style = Theme[typography][caption].copy(fontSize = 11.sp), color = Theme[colors][textMuted], maxLines = 1) }
            }
        }
    }
}

/** "21h" over "next run", "failed" or "missed" over "last run", or "paused" (over "last run failed" when it did). */
private fun CronJob.nextColumn(): Pair<String?, String?> = when {
    paused -> "paused" to ("last run failed".takeIf { problem != null })
    state == "completed" -> "done" to ("last run failed".takeIf { problem != null })
    // The runner never got the fire, so nothing ran to fail.
    missedLastFire -> "missed" to "last run"
    problem != null -> "failed" to "last run"
    else -> when (val until = timeUntil(nextRunEpochSeconds)) {
        "" -> null to null
        "now" -> "now" to "next run"
        else -> until to "next run"
    }
}

/**
 * Where runs go, said for people: the target's own name, a bot's chat, or the platform and the chat's id
 * for one chat the target list doesn't name.
 */
internal fun deliveryLabel(id: String, targets: List<DeliveryTarget>, botLabel: String?): String {
    targets.firstOrNull { it.id == id }?.name?.takeIf { it != id }?.let { return it }
    if (id == Routines.BOT_CHAT_DELIVERY) return botLabel?.let { "$it's chat" } ?: "the bot's chat"
    val platform = id.substringBefore(':', missingDelimiterValue = "")
    return if (platform.isEmpty()) id else "${platform.replaceFirstChar { it.uppercase() }} · ${id.substringAfter(':')}"
}

/** What the job does, where it reports, and the two things you can do with it. */
@Composable
private fun JobSummary(job: CronJob, deliversTo: String?, busy: Boolean, onRunNow: () -> Unit, onTogglePaused: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(
        Modifier.fillMaxWidth().background(Theme[colors][surface], shape).border(1.dp, Theme[colors][stroke], shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (job.prompt.isNotBlank()) {
            Text(job.prompt.trim(), style = Theme[typography][bodySmall].copy(fontSize = 14.sp, lineHeight = 21.sp), color = Theme[colors][textSecondary], maxLines = 8, overflow = TextOverflow.Ellipsis)
        }
        job.problem?.let {
            CopyableOnDesktop { Text(it, style = Theme[typography][bodySmall], color = Theme[colors][danger], maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
        deliversTo?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                UnstyledIcon(Lucide.Send, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(13.dp))
                Text("Delivers to $it", style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp), color = Theme[colors][textTertiary])
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("Run now", onClick = onRunNow, variant = ButtonVariant.Inverse, leadingIcon = Lucide.Zap, loading = busy)
            Button(
                if (job.paused) "Resume" else "Pause",
                onClick = onTogglePaused,
                variant = ButtonVariant.Secondary,
                leadingIcon = if (job.paused) Lucide.Play else Lucide.Pause,
                enabled = !busy,
            )
        }
    }
}

/** A run: a disc (a spinner while it runs), when, what it said, and how many messages. */
@Composable
private fun RunRow(run: SessionSummary, selected: Boolean, onOpen: () -> Unit, onPopOut: (() -> Unit)?) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    var menuOpen by remember { mutableStateOf(false) }
    if (isDesktop) {
        RowContextMenu(menuOpen, onDismiss = { menuOpen = false }, items = {
            listOfNotNull(
                RowMenuItem("Open", Lucide.MessageSquare, run = onOpen),
                onPopOut?.let { RowMenuItem("Open in new window", Lucide.AppWindow, run = it) },
            )
        })
    }
    // A Ctrl-click (⌘ on a Mac) opens the run in a window of its own, as it does a chat in the sidebar, so the
    // page stays where it is.
    val windowInfo = LocalWindowInfo.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (isDesktop) 44.dp else 52.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(Theme[colors][surface2], shape) else Modifier)
            .handCursor()
            .clickable(interactionSource = null, indication = if (isDesktop) rememberHoverIndication(Theme[colors][textSecondary], shape) else LocalIndication.current) {
                val keys = windowInfo.keyboardModifiers
                if (isDesktop && onPopOut != null && (keys.isCtrlPressed || keys.isMetaPressed)) onPopOut() else onOpen()
            }
            .then(if (isDesktop) Modifier.onSecondaryPress { menuOpen = true } else Modifier)
            .semantics { this.selected = selected }
            .padding(horizontal = 8.dp, vertical = if (isDesktop) 7.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .background(Theme[colors][if (run.isActive) accentSoft else surface2], CircleShape)
                .clearAndSetSemantics { if (run.isActive) contentDescription = "Running" },
            contentAlignment = Alignment.Center,
        ) {
            if (run.isActive) Spinner(Modifier.size(13.dp), color = Theme[colors][accentText])
            else UnstyledIcon(Lucide.MessageSquare, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(13.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                relativeTime(run.activityAt).let { if (it == "now") "Just now" else "$it ago" },
                style = Theme[typography][body].copy(fontSize = 14.5.sp),
                color = Theme[colors][text],
            )
            (run.preview ?: run.snippet)?.takeIf { it.isNotBlank() }?.let {
                Text(it.trim(), style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp), color = Theme[colors][textTertiary], maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (run.messageCount > 0) Text("${run.messageCount} msgs", style = Theme[typography][code].copy(fontSize = 11.5.sp), color = Theme[colors][textMuted])
    }
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
