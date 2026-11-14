package dev.hermeskotlin.ui.kanban

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.Hash
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.TriangleAlert
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.kanban.KanbanColumn
import dev.hermeskotlin.core.kanban.KanbanTask
import dev.hermeskotlin.core.kanban.KanbanStale
import dev.hermeskotlin.core.kanban.stale
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Chip
import dev.hermeskotlin.designsystem.components.DropdownMenu
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MenuChoice
import dev.hermeskotlin.designsystem.components.Switch
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.designsystem.warningSoft
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.components.ListSkeleton
import dev.hermeskotlin.ui.components.relativeTime
import dev.hermeskotlin.ui.resources.*
import dev.hermeskotlin.ui.sessions.CenteredSpinner
import dev.hermeskotlin.ui.sessions.SubpageHeader
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
private fun columnLabel(status: String): String = stringResource(
    when (status) {
        "triage" -> Res.string.kanban_column_triage
        "todo" -> Res.string.kanban_column_todo
        "scheduled" -> Res.string.kanban_column_scheduled
        "ready" -> Res.string.kanban_column_ready
        "running" -> Res.string.kanban_column_running
        "blocked" -> Res.string.kanban_column_blocked
        "review" -> Res.string.kanban_column_review
        "done" -> Res.string.kanban_column_done
        "archived" -> Res.string.kanban_column_archived
        else -> Res.string.kanban_status
    },
)

@Composable
private fun columnHelp(status: String): String? = when (status) {
    "triage" -> stringResource(Res.string.kanban_column_triage_help)
    "todo" -> stringResource(Res.string.kanban_column_todo_help)
    "ready" -> stringResource(Res.string.kanban_column_ready_help)
    "running" -> stringResource(Res.string.kanban_column_running_help)
    "blocked" -> stringResource(Res.string.kanban_column_blocked_help)
    "review" -> stringResource(Res.string.kanban_column_review_help)
    "done" -> stringResource(Res.string.kanban_column_done_help)
    else -> null
}

/**
 * The main Kanban view: the gateway's Kanban boards and, inside one, its columns of cards. Read-only —
 * nothing here moves, edits or creates a card. The sidebar's navigation button opens it in the content area;
 * the Plugins page keeps its WebView entry for the same board.
 */
@Composable
internal fun KanbanPage(
    gateway: SavedGateway,
    visible: Boolean,
    onBack: () -> Unit,
    onSessionExpired: () -> Unit,
    /** A task asked for from outside the page (a widget tap, a `hermes://kanban` link): opened once its board loads. */
    openTaskBoard: String? = null,
    openTaskId: String? = null,
    /** Called once the main view owns [openTaskId], or once the queue was consumed without it (a deleted task). */
    onTaskOpened: () -> Unit = {},
    viewModel: KanbanViewModel = koinViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val pendingTask by viewModel.pendingOpenTask.collectAsStateWithLifecycle()
    LaunchedEffect(gateway) { viewModel.bind(gateway) }
    // A pending link is state handed into the page (D22), queued on the view model and applied by it after
    // the board load — never before it, because openTask returns early with no board and bind resets state.
    var taskQueued by rememberSaveable { mutableStateOf(false) }
    var taskReported by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openTaskId, openTaskBoard) {
        val id = openTaskId ?: return@LaunchedEffect
        if (taskReported) return@LaunchedEffect
        if (state.openTaskId == id) {
            taskReported = true
            onTaskOpened()
            return@LaunchedEffect
        }
        viewModel.queueOpenTask(openTaskBoard ?: "", id)
        taskQueued = true
    }
    LaunchedEffect(state.openTaskId, pendingTask) {
        if (openTaskId == null || taskReported || !taskQueued) return@LaunchedEffect
        when {
            state.openTaskId == openTaskId -> {
                taskReported = true
                onTaskOpened()
            }
            // The queue was consumed without the drawer opening: the board has no such task (deleted).
            pendingTask == null -> {
                taskReported = true
                onTaskOpened()
            }
        }
    }
    // Leaving the page stops every load and the events tail; coming back refreshes and re-tails.
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }
    LaunchedEffect(state.sessionExpired) {
        if (state.sessionExpired) {
            viewModel.consumeSessionExpired()
            onSessionExpired()
        }
    }
    PlatformBackHandler(enabled = visible && state.openTaskId != null) { viewModel.closeTask() }

    val searchField = rememberTextFieldState()
    // Sync the field with the view model's search so a preview or restore keeps both.
    LaunchedEffect(state.search) {
        if (searchField.text.toString() != state.search) searchField.setTextAndPlaceCursorAtEnd(state.search)
    }
    LaunchedEffect(searchField.text) {
        val q = searchField.text.toString()
        if (q != state.search) viewModel.setSearch(q)
    }

    KanbanView(
        state = state,
        searchField = searchField,
        onBack = onBack,
        onSelectBoard = viewModel::selectBoard,
        onSearch = { viewModel.setSearch(it) },
        onTenant = viewModel::setTenant,
        onAssignee = viewModel::setAssignee,
        onIncludeArchived = viewModel::setIncludeArchived,
        onOpenTask = viewModel::openTask,
        onCloseTask = viewModel::closeTask,
        onRetry = viewModel::refresh,
        onReloadDetail = viewModel::reloadDetail,
        onReloadLog = viewModel::reloadLog,
    )
}

/** The page's layout, apart from its view model, so a preview can draw it from sample data. */
@Composable
internal fun KanbanView(
    state: KanbanUiState,
    searchField: TextFieldState,
    onBack: () -> Unit,
    onSelectBoard: (String) -> Unit,
    onSearch: (String) -> Unit,
    onTenant: (String?) -> Unit,
    onAssignee: (String?) -> Unit,
    onIncludeArchived: (Boolean) -> Unit,
    onOpenTask: (String) -> Unit,
    onCloseTask: () -> Unit,
    onRetry: () -> Unit,
    onReloadDetail: () -> Unit,
    onReloadLog: () -> Unit,
) {
    val openId = state.openTaskId
    Column(Modifier.fillMaxSize()) {
        // The drawer only needs the open id: its own states (spinner, error) live in TaskDetail.
        if (openId != null) {
            TaskDetail(
                state = state,
                onClose = onCloseTask,
                onReloadDetail = onReloadDetail,
                onReloadLog = onReloadLog,
            )
            return@Column
        }
        SubpageHeader(stringResource(Res.string.kanban_title), onBack = onBack, subtitle = stringResource(Res.string.kanban_detail)) {
            IconButton(
                icon = Lucide.RefreshCw,
                contentDescription = stringResource(Res.string.kanban_refresh),
                onClick = onRetry,
            )
        }
        BoardStrip(state, onSelectBoard)
        FiltersRow(state, searchField, onTenant, onAssignee, onIncludeArchived)
        KanbanWarning(state)
        val columns = remember(state.response, state.search, state.tenantFilter, state.assigneeFilter) {
            state.filteredColumns
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.boardsError != null && state.boards.isEmpty() -> EmptyState(Lucide.CloudOff, stringResource(Res.string.kanban_error), state.boardsError, error = true) {
                    Button(stringResource(Res.string.app_try_again), onClick = onRetry, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
                }
                state.board == null && state.boardsLoading -> CenteredSpinner()
                // /boards succeeded with nothing on it: say so instead of spinning forever.
                state.board == null -> EmptyState(Lucide.Search, stringResource(Res.string.kanban_empty), stringResource(Res.string.kanban_empty_detail))
                state.loading && state.response == null -> ListSkeleton()
                state.error != null && state.response == null -> EmptyState(Lucide.CloudOff, stringResource(Res.string.kanban_error), state.error, error = true) {
                    Button(stringResource(Res.string.app_try_again), onClick = onRetry, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
                }
                state.response != null && columns.sumOf { it.tasks.size } == 0 -> EmptyState(Lucide.Search, stringResource(Res.string.kanban_no_tasks), stringResource(Res.string.kanban_no_tasks_detail))
                state.response != null -> ColumnRail(columns, onOpenTask)
            }
        }
    }
}

/** A quiet failure or a dead tail, with the last snapshot still on screen: say the board may be stale. */
@Composable
private fun KanbanWarning(state: KanbanUiState) {
    if (state.response == null) return
    val message = when {
        state.reloadError -> stringResource(Res.string.kanban_stale_warning)
        state.eventsDead -> stringResource(Res.string.kanban_events_dead)
        else -> return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .background(Theme[colors][warningSoft], RoundedCornerShape(Theme[radii][radiusMedium]))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(Lucide.TriangleAlert, contentDescription = null, tint = Theme[colors][warning], modifier = Modifier.size(14.dp))
        Text(message, style = Theme[typography][caption], color = Theme[colors][warning], maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BoardStrip(state: KanbanUiState, onSelectBoard: (String) -> Unit) {
    if (state.boards.size <= 1) return
    val rowState = rememberLazyListState()
    LazyRow(
        state = rowState,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(state.boards, key = { it.slug }) { board ->
            val selected = board.slug == state.board
            Chip(
                text = board.name,
                selected = selected,
                count = board.total,
                onClick = { onSelectBoard(board.slug) },
                modifier = Modifier.semantics { contentDescription = board.name },
            )
        }
    }
}

@Composable
private fun FiltersRow(
    state: KanbanUiState,
    searchField: TextFieldState,
    onTenant: (String?) -> Unit,
    onAssignee: (String?) -> Unit,
    onIncludeArchived: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(
            state = searchField,
            placeholder = stringResource(Res.string.kanban_search),
            leadingIcon = Lucide.Search,
            clearable = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // The two filter chips split their row; each label ellipsizes instead of clipping at 360 dp.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val tenants = state.response?.tenants.orEmpty()
            if (tenants.isNotEmpty()) {
                FilterMenu(
                    label = stringResource(Res.string.kanban_tenant),
                    value = state.tenantFilter ?: stringResource(Res.string.kanban_all_tenants),
                    options = listOf(null) + tenants,
                    optionLabel = { it ?: stringResource(Res.string.kanban_all_tenants) },
                    selected = state.tenantFilter,
                    onPick = onTenant,
                    modifier = Modifier.weight(1f),
                )
            }
            val assignees = state.response?.assignees.orEmpty()
            if (assignees.isNotEmpty()) {
                FilterMenu(
                    label = stringResource(Res.string.kanban_assignee),
                    value = state.assigneeFilter ?: stringResource(Res.string.kanban_all_profiles),
                    options = listOf(null) + assignees,
                    optionLabel = { it ?: stringResource(Res.string.kanban_all_profiles) },
                    selected = state.assigneeFilter,
                    onPick = onAssignee,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // The archived toggle is its own switch row: a real Role.Switch, not a bare clickable.
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = state.includeArchived, role = Role.Switch, onValueChange = onIncludeArchived)
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.kanban_show_archived),
                style = Theme[typography][bodySmall],
                color = Theme[colors][textSecondary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Switch(state.includeArchived)
        }
    }
}

@Composable
private fun <T> FilterMenu(
    label: String,
    value: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    selected: T?,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    DropdownMenu(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
        items = {
            options.forEach { option ->
                MenuChoice(
                    optionLabel(option),
                    Lucide.Hash,
                    selected = option == selected,
                    onClick = { expanded = false; onPick(option) },
                )
            }
        },
    ) {
        Chip(text = "$label: $value", selected = false, onClick = { expanded = true }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ColumnRail(columns: List<KanbanColumn>, onOpenTask: (String) -> Unit) {
    if (columns.isEmpty()) {
        EmptyState(Lucide.Search, stringResource(Res.string.kanban_no_tasks), stringResource(Res.string.kanban_no_tasks_detail))
        return
    }
    val rowState = rememberLazyListState()
    LazyRow(
        state = rowState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(columns, key = { it.name }) { column ->
            ColumnPane(column, onOpenTask)
        }
    }
}

@Composable
private fun ColumnPane(column: KanbanColumn, onOpenTask: (String) -> Unit) {
    val label = columnLabel(column.name)
    val help = columnHelp(column.name)
    Column(
        Modifier.width(280.dp).fillMaxHeight().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column {
            Text(
                stringResource(Res.string.kanban_column_count, label, column.tasks.size),
                style = Theme[typography][bodySmall].copy(fontWeight = FontWeight.SemiBold),
                color = Theme[colors][text],
                maxLines = 1,
            )
            if (help != null) {
                Text(help, style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1)
            }
        }
        val list = rememberLazyListState()
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(column.tasks, key = { it.id }) { task ->
                TaskCard(task, onClick = { onOpenTask(task.id) })
            }
        }
    }
}

@Composable
private fun TaskCard(task: KanbanTask, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    val stale = task.stale()
    val borderColor = when (stale) {
        KanbanStale.Red -> Theme[colors][danger].copy(alpha = 0.55f)
        KanbanStale.Amber -> Theme[colors][warning].copy(alpha = 0.55f)
        KanbanStale.None -> Theme[colors][stroke]
    }
    val open = stringResource(Res.string.kanban_task_open, task.id)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][surface], shape)
            .border(1.dp, borderColor, shape)
            .clickable(onClickLabel = open, onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(task.id, style = Theme[typography][code].copy(fontSize = 11.sp), color = Theme[colors][textTertiary], maxLines = 1)
            val warnings = task.warnings
            if ((warnings?.count ?: 0) > 0) {
                val isCritical = warnings?.highestSeverity == "critical"
                val isError = warnings?.highestSeverity == "error"
                Text(
                    if (isCritical) "!!!" else if (isError) "!!" else "⚠",
                    style = Theme[typography][code].copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    color = Theme[colors][if (isCritical || isError) danger else warning],
                    maxLines = 1,
                )
            }
            if (task.priority > 0) {
                Text(
                    "P${task.priority}",
                    style = Theme[typography][code].copy(fontSize = 10.5.sp),
                    color = Theme[colors][accentText],
                    modifier = Modifier.background(Theme[colors][accentSoft], RoundedCornerShape(6.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                    maxLines = 1,
                )
            }
            task.tenant?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = Theme[typography][code].copy(fontSize = 10.5.sp),
                    color = Theme[colors][textSecondary],
                    modifier = Modifier.background(Theme[colors][surface2], RoundedCornerShape(6.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                    maxLines = 1,
                )
            }
            val progress = task.progress
            progress?.let { p ->
                Text("${p.done}/${p.total}", style = Theme[typography][code].copy(fontSize = 11.sp), color = Theme[colors][textTertiary], maxLines = 1)
            }
        }
        Text(
            task.title.ifBlank { task.id },
            style = Theme[typography][body].copy(fontWeight = FontWeight.Medium),
            color = Theme[colors][text],
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                task.assignee?.let { "@$it" } ?: stringResource(Res.string.kanban_unassigned),
                style = Theme[typography][bodySmall].copy(fontSize = 12.sp),
                color = Theme[colors][textSecondary],
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (task.commentCount > 0) {
                Text("💬 ${task.commentCount}", style = Theme[typography][caption], color = Theme[colors][textMuted], maxLines = 1)
            }
            (task.linkCounts?.let { it.parents + it.children } ?: 0).takeIf { it > 0 }?.let {
                Text("↔ $it", style = Theme[typography][caption], color = Theme[colors][textMuted], maxLines = 1)
            }
            val created = task.createdAt
            if (created != null && created > 0) {
                Text(relativeTime(created.toDouble()), style = Theme[typography][caption], color = Theme[colors][textMuted], maxLines = 1)
            }
        }
        if (task.status == "ready" && task.assignee == null) {
            Row(
                Modifier.background(Theme[colors][warningSoft], RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UnstyledIcon(Lucide.TriangleAlert, contentDescription = null, tint = Theme[colors][warning], modifier = Modifier.size(12.dp))
                Text(stringResource(Res.string.kanban_needs_assignee), style = Theme[typography][caption], color = Theme[colors][warning], maxLines = 1)
            }
        }
    }
}
