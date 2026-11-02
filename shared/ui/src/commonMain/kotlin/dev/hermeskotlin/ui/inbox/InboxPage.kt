package dev.hermeskotlin.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import com.composables.icons.lucide.FolderOpen
import dev.hermeskotlin.core.inbox.InboxLayout
import dev.hermeskotlin.core.inbox.keepLineBreaks
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.onAccent
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.hermeskotlin.designsystem.components.verticalScrollWithBar
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.CircleDot
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.MessageSquarePlus
import com.composables.icons.lucide.MessageSquareReply
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.ui.sessions.SwipeAction
import dev.hermeskotlin.ui.sessions.SwipeKind
import dev.hermeskotlin.ui.sessions.SwipeRow
import dev.hermeskotlin.ui.sessions.onSecondaryPress
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderPlus
import com.composables.icons.lucide.FolderTree
import com.composables.icons.lucide.FolderX
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Trash2
import dev.hermeskotlin.core.inbox.ChipMode
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.SheetAction
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.TextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.semantics.Role
import dev.hermeskotlin.core.inbox.InboxGroups
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.BellRing
import com.composables.icons.lucide.CheckCheck
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.TriangleAlert
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.inbox.Follow
import dev.hermeskotlin.core.inbox.InboxRun
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Chip
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MarkdownText
import dev.hermeskotlin.designsystem.components.SegmentedControl
import dev.hermeskotlin.designsystem.components.Switch
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.dangerSoft
import dev.hermeskotlin.designsystem.eyebrow
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.isDesktop
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.components.ListSkeleton
import dev.hermeskotlin.ui.components.dayHeading
import dev.hermeskotlin.ui.components.messageTime
import dev.hermeskotlin.ui.components.uses24HourClock
import dev.hermeskotlin.ui.sessions.MessageBanner
import dev.hermeskotlin.ui.sessions.SubpageHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import androidx.compose.runtime.rememberUpdatedState
import org.koin.compose.viewmodel.koinViewModel

/**
 * The sidebar's Inbox: what the cron jobs the user follows have reported, newest first, and each run read in
 * full. Picking the jobs, and which of them notify, is its own page.
 */
@Composable
internal fun InboxPage(
    gateway: SavedGateway,
    visible: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    /** A run to open, from a tapped notification ([InboxRun.key]); [onOpenedKey] once it has been. */
    openKey: String? = null,
    onOpenedKey: () -> Unit = {},
    /** Opens a chat about a report: its own, or a new one with the report attached. */
    onOpenChat: (ReportChat) -> Unit = {},
    viewModel: InboxViewModel = koinViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(gateway) { viewModel.bind(gateway) }
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val openChat by rememberUpdatedState(onOpenChat)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                // The app checks the chat is of the gateway it is on still.
                is InboxEvent.OpenChat -> openChat(event.chat)
                is InboxEvent.Copy -> clipboard.setText(AnnotatedString(event.text))
            }
        }
    }
    // In sight: the page reads the gateway when it comes into view, and on its nudges only while it shows.
    DisposableEffect(visible) {
        viewModel.setVisible(visible)
        onDispose { viewModel.setVisible(false) }
    }
    // The two lists keep their place while the page is away: a report, the follow or groups page, or a folder
    // opened over the folders. A list off screen can come back at its top, so where each was when it went is
    // kept (the run list's per folder) and put back when it returns. A folder opened afresh starts at its top.
    val foldersList = rememberLazyListState(viewModel.places[ShownList.Folders.name]?.index ?: 0, viewModel.places[ShownList.Folders.name]?.offset ?: 0)
    val runsKey = ShownList.Runs.name + "/" + state.shownFilter.key
    val runsList = rememberLazyListState(viewModel.places[runsKey]?.index ?: 0, viewModel.places[runsKey]?.offset ?: 0)
    val showing = when {
        state.open != null || state.choosing || state.grouping -> null
        state.atFolders -> ShownList.Folders.name
        else -> runsKey
    }
    var last by remember { mutableStateOf(showing) }
    var lastPlace by remember { mutableStateOf(ListPlace()) }
    val showingNow by rememberUpdatedState(showing)
    LaunchedEffect(foldersList) { foldersList.places().collect { if (showingNow == ShownList.Folders.name) lastPlace = it } }
    // The run list is on screen whichever folder it shows. (Compared with [runsKey] here, the collector kept the
    // key of the folder it started in, so in any other folder every move was dropped.)
    LaunchedEffect(runsList) {
        runsList.places().collect { if (showingNow?.startsWith(ShownList.Runs.name) == true) lastPlace = it }
    }
    LaunchedEffect(showing) {
        val before = last
        if (before != null && before != showing) viewModel.places[before] = lastPlace
        last = showing
        if (showing == null) return@LaunchedEffect
        val place = viewModel.places[showing] ?: ListPlace()
        lastPlace = place
        if (showing == ShownList.Folders.name) foldersList.restore(place) else runsList.restore(place)
    }
    LaunchedEffect(openKey) {
        if (openKey != null) {
            viewModel.openKey(openKey)
            onOpenedKey()
        }
    }
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(4_000)
            viewModel.dismissMessage()
        }
    }
    PlatformBackHandler(enabled = visible && state.open != null) { viewModel.closeRun() }
    PlatformBackHandler(enabled = visible && state.open == null && state.choosing) { viewModel.closeJobs() }
    PlatformBackHandler(enabled = visible && state.open == null && !state.choosing && state.grouping) { viewModel.closeGroups() }
    // In a folder, back goes to the folders before it leaves the Inbox.
    val inFolder = state.groups.layout == InboxLayout.Folders && state.shownFilter != InboxFilter.All
    PlatformBackHandler(enabled = visible && state.open == null && !state.choosing && !state.grouping && inFolder) {
        viewModel.setFilter(InboxFilter.All)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            when {
                state.open != null -> RunView(
                    state.open,
                    state.nameOf(state.open),
                    state.openLoading,
                    state.openNote,
                    onBack = viewModel::closeRun,
                    fetching = state.fetching,
                    onAsk = { viewModel.ask(state.open) },
                    onContinue = state.open.session?.let { { viewModel.continueChat(state.open) } },
                    onCopy = { viewModel.copy(state.open) },
                )
                state.choosing -> FollowView(state, viewModel)
                state.grouping -> GroupsView(state, viewModel)
                else -> InboxView(state, viewModel, onBack = onBack, onOpenSettings = onOpenSettings, foldersList = foldersList, runsList = runsList)
            }
        }
        // Over the page's foot, where the lists leave room for it: each page fills the height itself. Above the
        // sidebar's New chat button, which lies over that foot too.
        state.message?.let {
            MessageBanner(it.text(), onDismiss = viewModel::dismissMessage, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        }
    }
}

@Composable
internal fun InboxView(
    state: InboxUiState,
    viewModel: InboxActions,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    foldersList: LazyListState = rememberLazyListState(),
    runsList: LazyListState = rememberLazyListState(),
) {
    val unread = state.unread
    val folders = state.groups.layout == InboxLayout.Folders
    val inFolder = folders && state.shownFilter != InboxFilter.All
    val folderName = when (val f = state.shownFilter) {
        is InboxFilter.Job -> state.chips.firstOrNull { it.filter == f }?.label
        is InboxFilter.Group -> f.name ?: stringResource(Res.string.inbox_other)
        InboxFilter.Failed -> stringResource(Res.string.inbox_failed)
        InboxFilter.All -> null
    }
    val inbox = stringResource(Res.string.inbox_title)
    val shownCount = state.shown.size
    SubpageHeader(
        if (inFolder) folderName ?: inbox else inbox,
        onBack = if (inFolder) ({ viewModel.setFilter(InboxFilter.All) }) else onBack,
        subtitle = when {
            state.runs.isEmpty() -> null
            state.shownFilter is InboxFilter.Job ->
                pluralStringResource(Res.plurals.inbox_reports_from, shownCount, shownCount, state.nameOf(state.shown.first()))
            state.shownFilter is InboxFilter.Group -> when (val name = (state.shownFilter as InboxFilter.Group).name) {
                null -> pluralStringResource(Res.plurals.inbox_reports_in_no_group, shownCount, shownCount)
                else -> pluralStringResource(Res.plurals.inbox_reports_in, shownCount, shownCount, name)
            }
            unread > 0 -> pluralStringResource(Res.plurals.inbox_unread_from_jobs, unread, unread)
            else -> stringResource(Res.string.inbox_from_jobs)
        },
    ) {
        if (unread > 0) IconButton(Lucide.CheckCheck, contentDescription = stringResource(Res.string.inbox_mark_all_read), onClick = viewModel::markAllRead)
        IconButton(Lucide.FolderTree, contentDescription = stringResource(Res.string.inbox_folders_and_groups), onClick = viewModel::openGroups)
        IconButton(Lucide.Settings2, contentDescription = stringResource(Res.string.inbox_choose_jobs), onClick = viewModel::chooseJobs)
    }
    state.block?.let { BlockNotice(it, onOpenSettings) }
    var renaming by remember { mutableStateOf<JobChip?>(null) }
    var folderMenu by remember { mutableStateOf<InboxFolder?>(null) }
    var runMenu by remember { mutableStateOf<InboxRun?>(null) }
    // Folders are the way in on the folder layout; the chips belong to the single list.
    if (!folders) FilterChips(state, onSelect = viewModel::setFilter, onRename = { renaming = it })
    RenameJobDialog(renaming, onDismiss = { renaming = null }) { chip, name ->
        viewModel.renameJob(chip, name)
        renaming = null
    }
    val shown = state.shown
    when {
        state.loading && shown.isEmpty() -> ListSkeleton()
        state.error != null && shown.isEmpty() -> EmptyState(Lucide.TriangleAlert, stringResource(Res.string.inbox_load_failed_title), state.error.text())
        shown.isEmpty() && state.shownFilter == InboxFilter.Failed ->
            EmptyState(Lucide.Inbox, stringResource(Res.string.inbox_no_failed_runs), stringResource(Res.string.inbox_no_failed_runs_detail))
        shown.isEmpty() && state.block == null -> EmptyState(
            Lucide.Inbox,
            stringResource(Res.string.inbox_empty),
            stringResource(Res.string.inbox_empty_detail),
        )
        state.atFolders -> LazyColumn(
            Modifier.fillMaxSize(),
            state = foldersList,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.folders, key = { it.filter.key }) { folder ->
                FolderCard(
                    folder,
                    recent = state.more,
                    newestName = state.nameOf(folder.newest),
                    onClick = { viewModel.setFilter(folder.filter) },
                    // A group's folder with nothing unread has nothing to offer: it's renamed on the groups page.
                    onLongClick = { folderMenu = folder }.takeIf { folder.unread > 0 || folder.filter is InboxFilter.Job },
                )
            }
            if (state.failed > 0) {
                item(key = "failed") { FailedFolder(state.failed, onClick = { viewModel.setFilter(InboxFilter.Failed) }) }
            }
        }
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            state = runsList,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var lastDay: String? = null
            shown.forEach { run ->
                val day = dayHeading(run.at)
                if (day != lastDay) {
                    lastDay = day
                    item(key = "day-$day") { DayLabel(day) }
                }
                item(key = run.key) {
                    val unread = state.isUnread(run)
                    // Either way across, the row's read state flips: there is only the one thing to do to a report.
                    val swipe = when {
                        unread -> SwipeAction(stringResource(Res.string.inbox_mark_read), Lucide.Check, SwipeKind.Pin) { viewModel.setRead(run, read = true) }
                        state.canMarkUnread(run) ->
                            SwipeAction(stringResource(Res.string.inbox_mark_unread), Lucide.CircleDot, SwipeKind.Pin) { viewModel.setRead(run, read = false) }
                        else -> null
                    }
                    SwipeRow(start = swipe, end = swipe, shape = RoundedCornerShape(Theme[radii][radiusLarge])) { swipeActions ->
                        RunCard(
                            run,
                            state.nameOf(run),
                            unread = unread,
                            onClick = { viewModel.open(run) },
                            onLongClick = { runMenu = run },
                            modifier = swipeActions,
                            fetching = state.fetching?.key == run.key,
                        )
                    }
                }
            }
            if (state.more || state.moreError != null) {
                item(key = "more") { MoreRow(state.loadingMore, state.moreError, onClick = viewModel::loadMore) }
            }
        }
    }
    FolderMenu(
        folder = folderMenu,
        // Only a job's folder renames; a group is renamed on the groups page.
        canRename = (folderMenu?.filter as? InboxFilter.Job)?.let { f -> state.chips.any { it.filter == f } } == true,
        onDismiss = { folderMenu = null },
        onRename = { folder ->
            folderMenu = null
            renaming = (folder.filter as? InboxFilter.Job)?.let { f -> state.chips.firstOrNull { it.filter == f } }
        },
        onMarkRead = { folder ->
            folderMenu = null
            viewModel.markFolderRead(folder.filter)
        },
    )
    // The run as the list has it now: read or not, and with its chat once a read in full learned it.
    val menuRun = runMenu?.let { r -> state.runs.firstOrNull { it.key == r.key } ?: r }
    RunMenu(
        run = menuRun,
        name = menuRun?.let(state::nameOf).orEmpty(),
        unread = menuRun?.let(state::isUnread) == true,
        canMarkUnread = menuRun?.let(state::canMarkUnread) == true,
        onDismiss = { runMenu = null },
        onAsk = { runMenu = null; viewModel.ask(it) },
        onContinue = { runMenu = null; viewModel.continueChat(it) },
        onSetRead = { r, read -> runMenu = null; viewModel.setRead(r, read) },
        onCopy = { runMenu = null; viewModel.copy(it) },
    )
}

/** A folder's long press: rename its job (a job's folder only), or count every report in it as read. */
@Composable
private fun FolderMenu(
    folder: InboxFolder?,
    canRename: Boolean,
    onDismiss: () -> Unit,
    onRename: (InboxFolder) -> Unit,
    onMarkRead: (InboxFolder) -> Unit,
) {
    // Kept while the sheet slides away, so it doesn't empty as it goes.
    var shown by remember { mutableStateOf(folder) }
    if (folder != null) shown = folder
    BottomSheet(visible = folder != null, onDismiss = onDismiss) {
        val f = shown ?: return@BottomSheet
        SheetHeader(
            folderLabel(f.filter, f.label),
            if (f.unread > 0) pluralStringResource(Res.plurals.inbox_unread_count, f.unread, f.unread) else stringResource(Res.string.inbox_folder),
        )
        if (f.unread > 0) SheetAction(stringResource(Res.string.inbox_mark_folder_read), Lucide.CheckCheck, onClick = { onMarkRead(f) })
        if (canRename) SheetAction(stringResource(Res.string.inbox_rename), Lucide.Pencil, onClick = { onRename(f) })
    }
}

/** A report's long press: ask about it, continue its chat when known, mark it read or unread, copy it. */
@Composable
private fun RunMenu(
    run: InboxRun?,
    name: String,
    unread: Boolean,
    canMarkUnread: Boolean,
    onDismiss: () -> Unit,
    onAsk: (InboxRun) -> Unit,
    onContinue: (InboxRun) -> Unit,
    onSetRead: (InboxRun, Boolean) -> Unit,
    onCopy: (InboxRun) -> Unit,
) {
    var shown by remember { mutableStateOf(run) }
    var shownName by remember { mutableStateOf(name) }
    if (run != null) {
        shown = run
        shownName = name
    }
    BottomSheet(visible = run != null, onDismiss = onDismiss) {
        val r = shown ?: return@BottomSheet
        SheetHeader(shownName, runWhen(r.failed, timeLabel(r.at)))
        SheetAction(stringResource(Res.string.inbox_ask_about_this), Lucide.MessageSquarePlus, onClick = { onAsk(r) })
        if (r.session != null) SheetAction(stringResource(runChatLabel(r)), Lucide.MessageSquareReply, onClick = { onContinue(r) })
        when {
            unread -> SheetAction(stringResource(Res.string.inbox_mark_read), Lucide.Check, onClick = { onSetRead(r, true) })
            canMarkUnread -> SheetAction(stringResource(Res.string.inbox_mark_unread), Lucide.CircleDot, onClick = { onSetRead(r, false) })
        }
        SheetAction(stringResource(Res.string.inbox_copy), Lucide.Copy, onClick = { onCopy(r) })
    }
}

/**
 * All, then a chip per job (its unread count beside it), then Failed when any run failed, in one row that
 * scrolls sideways. Shown once there is more than one job, or a failure, to tell apart.
 */
@Composable
private fun FilterChips(state: InboxUiState, onSelect: (InboxFilter) -> Unit, onRename: (JobChip) -> Unit) {
    // By group once there are groups to split by; by job otherwise, and always when the user picked jobs.
    val byGroup = state.groups.mode == ChipMode.Groups && state.groupChips.isNotEmpty()
    val chips: List<Triple<InboxFilter, String, Int>> = if (byGroup) {
        state.groupChips.map { Triple(it.filter, it.label, it.unread) }
    } else {
        state.chips.map { Triple(it.filter, it.label, it.unread) }
    }
    val failed = state.failed
    val selected = state.shownFilter
    if (!showsChips(chips.size, failed, selected)) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Chip(stringResource(Res.string.inbox_all), selected = selected == InboxFilter.All, onClick = { onSelect(InboxFilter.All) }, count = state.unread.takeIf { it > 0 })
        val rename = stringResource(Res.string.inbox_rename)
        chips.forEach { (filter, label, unread) ->
            // A job's chip renames on a long press; a group's is renamed on the groups page.
            val job = (filter as? InboxFilter.Job)?.let { f -> state.chips.firstOrNull { it.filter == f } }
            Chip(
                folderLabel(filter, label),
                selected = selected == filter,
                onClick = { onSelect(filter) },
                count = unread.takeIf { it > 0 },
                countColor = Theme[colors][accentText],
                onLongClick = job?.let { { onRename(it) } },
                onLongClickLabel = job?.let { rename },
            )
        }
        if (failed > 0) {
            Chip(
                stringResource(Res.string.inbox_failed),
                selected = selected == InboxFilter.Failed,
                onClick = { onSelect(InboxFilter.Failed) },
                count = failed,
                countColor = Theme[colors][danger],
            )
        }
    }
}

/** A folder: its name, unread count, when it last reported and that report's opening words. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(folder: InboxFolder, newestName: String, recent: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    val group = folder.filter is InboxFilter.Group
    val label = folderLabel(folder.filter, folder.label)
    val openLabel = stringResource(Res.string.inbox_open_named, label)
    val moreLabel = stringResource(Res.string.inbox_more_for_named, label)
    val unreadLabel = pluralStringResource(Res.plurals.inbox_unread_count, folder.unread, folder.unread)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][surface])
            .border(1.dp, Theme[colors][stroke], shape)
            .then(if (isDesktop && onLongClick != null) Modifier.onSecondaryPress(onPress = onLongClick) else Modifier)
            .combinedClickable(
                onClickLabel = openLabel,
                onClick = onClick,
                onLongClickLabel = onLongClick?.let { moreLabel },
                onLongClick = onLongClick,
            )
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).background(Theme[colors][accentSoft], RoundedCornerShape(Theme[radii][radiusSmall])), contentAlignment = Alignment.Center) {
            UnstyledIcon(if (group) Lucide.FolderOpen else Lucide.Folder, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    label,
                    style = Theme[typography][body].copy(fontWeight = if (folder.unread > 0) FontWeight.SemiBold else FontWeight.Medium),
                    color = Theme[colors][text],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (folder.unread > 0) {
                    Text(
                        folder.unread.toString(),
                        style = Theme[typography][code].copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                        color = Theme[colors][onAccent],
                        modifier = Modifier
                            .background(Theme[colors][accent], CircleShape)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                            .semantics { contentDescription = unreadLabel },
                    )
                }
            }
            // Only the newest runs are on the phone while the gateway has more: the count says so.
            val reports = if (recent) {
                pluralStringResource(Res.plurals.inbox_recent_reports, folder.runs, folder.runs)
            } else {
                pluralStringResource(Res.plurals.inbox_reports, folder.runs, folder.runs)
            }
            val failedRuns = pluralStringResource(Res.plurals.inbox_failed_count, folder.failed, folder.failed)
            val time = timeLabel(folder.newest.at)
            Text(
                buildString {
                    append(reports)
                    append(" · ")
                    append(time)
                    if (folder.failed > 0) append(" · ").append(failedRuns)
                },
                style = Theme[typography][code].copy(fontSize = 11.5.sp),
                color = if (folder.failed > 0) Theme[colors][danger] else Theme[colors][textMuted],
            )
            Text(
                (if (group) "$newestName: " else "") + remember(folder.newest.text) { folder.newest.text.toPreview() },
                style = Theme[typography][bodySmall].copy(fontSize = 13.sp, lineHeight = 19.sp),
                color = Theme[colors][textSecondary],
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Every failed run, as one more folder after the rest. */
@Composable
private fun FailedFolder(count: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][surface])
            .border(1.dp, Theme[colors][stroke], shape)
            .clickable(onClickLabel = stringResource(Res.string.inbox_open_failed_runs), onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(Theme[colors][dangerSoft], RoundedCornerShape(Theme[radii][radiusSmall])), contentAlignment = Alignment.Center) {
            UnstyledIcon(Lucide.TriangleAlert, contentDescription = null, tint = Theme[colors][danger], modifier = Modifier.size(18.dp))
        }
        Text(stringResource(Res.string.inbox_failed_runs), style = Theme[typography][body].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text], modifier = Modifier.weight(1f))
        Text(count.toString(), style = Theme[typography][code].copy(fontSize = 12.sp), color = Theme[colors][danger])
    }
}

@Composable
private fun BlockNotice(block: InboxBlock, onOpenSettings: () -> Unit) {
    val text = when (block) {
        InboxBlock.PushOff -> stringResource(Res.string.inbox_needs_push)
        InboxBlock.PluginOld -> stringResource(Res.string.inbox_needs_plugin, InboxViewModel.NEEDS_PLUGIN)
    }
    val action = stringResource(Res.string.inbox_open_settings)
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .clickable(onClick = onOpenSettings)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
        Text(action, style = Theme[typography][bodySmall].copy(fontWeight = FontWeight.SemiBold), color = Theme[colors][accentText])
    }
}

@Composable
private fun DayLabel(day: String) {
    Text(
        day.uppercase(),
        style = Theme[typography][eyebrow],
        color = Theme[colors][textTertiary],
        modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp),
    )
}

/**
 * A run as a card: the job, when, the start of what it said, and a dot while it's unread. [modifier] goes on its
 * clickable node, as [SwipeRow]'s screen-reader actions must.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RunCard(
    run: InboxRun,
    name: String,
    unread: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Its whole report is on its way, for Ask or Copy from its menu. */
    fetching: Boolean = false,
) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    val (tile, tint) = if (run.failed) Theme[colors][dangerSoft] to Theme[colors][danger] else Theme[colors][accentSoft] to Theme[colors][accentText]
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][surface])
            .border(1.dp, Theme[colors][stroke], shape)
            // A right-click is a desktop's long press, as on the session rows.
            .then(if (isDesktop) Modifier.onSecondaryPress(onPress = onLongClick) else Modifier)
            .combinedClickable(
                onClickLabel = stringResource(Res.string.inbox_read_named, name),
                onClick = onClick,
                onLongClickLabel = stringResource(Res.string.inbox_more_for_report),
                onLongClick = onLongClick,
            )
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).background(tile, RoundedCornerShape(Theme[radii][radiusSmall])), contentAlignment = Alignment.Center) {
            if (fetching) Spinner(Modifier.size(16.dp), color = tint)
            else UnstyledIcon(if (run.failed) Lucide.TriangleAlert else Lucide.Inbox, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    name,
                    style = Theme[typography][body].copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium),
                    color = Theme[colors][text],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (unread) {
                    val unreadLabel = stringResource(Res.string.inbox_unread)
                    Box(Modifier.size(8.dp).background(Theme[colors][accentText], CircleShape).clearAndSetSemantics { contentDescription = unreadLabel })
                }
            }
            Text(
                runWhen(run.failed, timeLabel(run.at)),
                style = Theme[typography][code].copy(fontSize = 11.5.sp),
                color = if (run.failed) Theme[colors][danger] else Theme[colors][textMuted],
            )
            Text(
                remember(run.text) { run.text.toPreview() },
                style = Theme[typography][bodySmall].copy(fontSize = 13.sp, lineHeight = 19.sp),
                color = Theme[colors][textSecondary],
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The foot of the list: the reports before the oldest one shown, from the gateway. */
@Composable
private fun MoreRow(loading: Boolean, error: Words?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (error != null) Text(error.text(), style = Theme[typography][bodySmall], color = Theme[colors][danger])
        Button(
            stringResource(
                when {
                    loading -> Res.string.inbox_loading
                    error != null -> Res.string.inbox_try_again
                    else -> Res.string.inbox_older_reports
                },
            ),
            onClick = onClick,
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Small,
            enabled = !loading,
        )
    }
}

/**
 * One run in full, as Markdown, with a way to talk about it: a new chat with the report attached ([onAsk]), or the
 * run's own chat ([onContinue], null when the gateway didn't say which it was).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RunView(
    run: InboxRun,
    name: String,
    loading: Boolean,
    note: RunNote?,
    onBack: () -> Unit,
    /** A whole report being fetched for [onAsk] or [onCopy], this one's or one asked for from the list. */
    fetching: ReportFetch? = null,
    onAsk: () -> Unit = {},
    onContinue: (() -> Unit)? = null,
    onCopy: () -> Unit = {},
) {
    // One fetch at a time: while one runs, a tap here would do nothing.
    val busy = fetching != null
    SubpageHeader(name, onBack = onBack, subtitle = runWhen(run.failed, messageTime(run.at.toDouble(), uses24HourClock()))) {
        IconButton(Lucide.Copy, contentDescription = stringResource(Res.string.inbox_copy_report), onClick = onCopy, enabled = !busy)
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScrollWithBar()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // On a narrow sidebar Continue goes under Ask, rather than its label breaking over two lines.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                stringResource(Res.string.inbox_ask_about_this),
                onClick = onAsk,
                size = ButtonSize.Small,
                variant = ButtonVariant.Secondary,
                leadingIcon = Lucide.MessageSquarePlus,
                // Copy's fetch disables it too, but only Ask's turns it: no chat is on its way for a copy.
                loading = fetching != null && fetching.key == run.key && fetching.ask,
                enabled = !busy,
            )
            if (onContinue != null) {
                Button(
                    stringResource(runChatLabel(run)),
                    onClick = onContinue,
                    size = ButtonSize.Small,
                    variant = ButtonVariant.Ghost,
                    leadingIcon = Lucide.MessageSquareReply,
                    enabled = !busy,
                )
            }
        }
        if (note != null) {
            Text(
                stringResource(
                    when (note) {
                        RunNote.Offline -> if (isDesktop) Res.string.inbox_note_offline_computer else Res.string.inbox_note_offline_phone
                        RunNote.Gone -> if (isDesktop) Res.string.inbox_note_gone_computer else Res.string.inbox_note_gone_phone
                        RunNote.Failed -> if (isDesktop) Res.string.inbox_note_failed_computer else Res.string.inbox_note_failed_phone
                    },
                ),
                style = Theme[typography][bodySmall],
                color = Theme[colors][textTertiary],
            )
        }
        if (loading && run.text.isEmpty()) ListSkeleton() else MarkdownText(remember(run.text) { keepLineBreaks(run.text) })
    }
}

/**
 * The chips' split (by job or by group), the user's groups (add, rename, remove), and each job's group. Groups
 * live on this phone only: they sort what it shows, nothing on the gateway.
 */
@Composable
internal fun GroupsView(state: InboxUiState, viewModel: InboxActions) {
    val groups = state.groups
    var naming by remember { mutableStateOf<GroupNaming?>(null) }
    var picking by remember { mutableStateOf<InboxFilter.Job?>(null) }
    var renamingJob by remember { mutableStateOf<JobChip?>(null) }
    SubpageHeader(
        stringResource(Res.string.inbox_folders_and_groups),
        onBack = viewModel::closeGroups,
        subtitle = stringResource(if (isDesktop) Res.string.inbox_kept_on_computer else Res.string.inbox_kept_on_phone),
    ) {
        IconButton(Lucide.FolderPlus, contentDescription = stringResource(Res.string.inbox_new_group), onClick = { naming = GroupNaming(null) })
    }
    val folderLayout = stringResource(Res.string.inbox_layout_folders)
    val oneList = stringResource(Res.string.inbox_layout_one_list)
    val eachJob = stringResource(Res.string.inbox_mode_each_job)
    val myGroups = stringResource(Res.string.inbox_mode_my_groups)
    val opensOn = stringResource(Res.string.inbox_opens_on)
    val modeLabel = stringResource(if (groups.layout == InboxLayout.Folders) Res.string.inbox_folders_are else Res.string.inbox_chips_show)
    val groupsHint = stringResource(Res.string.inbox_groups_hint)
    val groupsLabel = stringResource(Res.string.inbox_groups)
    val jobsLabel = stringResource(Res.string.inbox_jobs)
    val renameHint = stringResource(if (isDesktop) Res.string.inbox_rename_hint_computer else Res.string.inbox_rename_hint_phone)
    val noJobs = stringResource(Res.string.inbox_no_jobs_yet)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "layout") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DayLabel(opensOn)
                SegmentedControl(
                    options = InboxLayout.entries,
                    selected = groups.layout,
                    onSelect = viewModel::setLayout,
                    optionLabel = { if (it == InboxLayout.Folders) folderLayout else oneList },
                )
            }
        }
        item(key = "mode") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DayLabel(modeLabel)
                SegmentedControl(
                    options = ChipMode.entries,
                    selected = groups.mode,
                    onSelect = viewModel::setChipMode,
                    optionLabel = { if (it == ChipMode.Jobs) eachJob else myGroups },
                )
                if (groups.mode == ChipMode.Groups && groups.names.isEmpty()) {
                    Text(
                        groupsHint,
                        style = Theme[typography][bodySmall],
                        color = Theme[colors][textTertiary],
                    )
                }
            }
        }
        if (groups.names.isNotEmpty()) {
            item(key = "groups-label") { DayLabel(groupsLabel) }
            items(groups.names, key = { "g-$it" }) { name ->
                val count = state.groupableJobs.count { it.second == name }
                GroupRow(
                    name,
                    jobs = count,
                    onRename = { naming = GroupNaming(name) },
                    onRemove = { viewModel.removeGroup(name) },
                )
            }
        }
        item(key = "jobs-label") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DayLabel(jobsLabel)
                Text(
                    renameHint,
                    style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp),
                    color = Theme[colors][textTertiary],
                    modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                )
            }
        }
        if (state.groupableJobs.isEmpty()) {
            item(key = "no-jobs") {
                Text(noJobs, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
            }
        }
        items(state.groupableJobs, key = { "j-${it.first.filter.bot}/${it.first.filter.job}" }) { (chip, group) ->
            JobGroupRow(
                chip,
                group,
                enabled = groups.names.isNotEmpty(),
                onClick = { picking = chip.filter },
                onRename = { renamingJob = chip },
            )
        }
    }
    GroupNameDialog(
        naming = naming,
        groups = groups,
        onDismiss = { naming = null },
        onSave = { from, to ->
            if (from == null) viewModel.addGroup(to) else viewModel.renameGroup(from, to)
            naming = null
        },
    )
    RenameJobDialog(renamingJob, onDismiss = { renamingJob = null }) { chip, name ->
        viewModel.renameJob(chip, name)
        renamingJob = null
    }
    val job = picking
    val noGroup = stringResource(Res.string.inbox_no_group)
    DropdownSheet(
        visible = job != null,
        title = state.chips.firstOrNull { it.filter == job }?.label ?: "",
        options = listOf<String?>(null) + groups.names,
        selected = job?.let { groups.of(it.bot, it.job) },
        label = { it ?: noGroup },
        onPick = { group -> job?.let { viewModel.assignGroup(it, group) }; picking = null },
        onDismiss = { picking = null },
    )
}

/** Renames a job on this phone: what its chip, cards and page say. Blank, or its own name, puts that back. */
@Composable
private fun RenameJobDialog(chip: JobChip?, onDismiss: () -> Unit, onSave: (JobChip, String) -> Unit) {
    var shown by remember { mutableStateOf(chip) }
    if (chip != null) shown = chip
    val c = shown ?: return
    val name = rememberTextFieldState(c.label)
    LaunchedEffect(chip) { if (chip != null) name.edit { replace(0, length, chip.label) } }
    Dialog(
        visible = chip != null,
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.inbox_rename_job),
        message = stringResource(if (isDesktop) Res.string.inbox_rename_job_detail_computer else Res.string.inbox_rename_job_detail_phone, c.own),
        actions = {
            if (c.renamed) Button(stringResource(Res.string.inbox_reset), onClick = { onSave(c, "") }, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(stringResource(Res.string.common_cancel), onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(stringResource(Res.string.inbox_save), onClick = { onSave(c, name.text.toString()) }, size = ButtonSize.Small)
        },
    ) {
        TextField(name, placeholder = c.own)
    }
}

/** The name dialog: a new group when [from] is null, else a rename of [from]. */
private data class GroupNaming(val from: String?)

@Composable
private fun GroupNameDialog(naming: GroupNaming?, groups: InboxGroups, onDismiss: () -> Unit, onSave: (from: String?, to: String) -> Unit) {
    var shown by remember { mutableStateOf(naming) }
    if (naming != null) shown = naming
    val n = shown ?: return
    val name = rememberTextFieldState(n.from.orEmpty())
    LaunchedEffect(naming) { if (naming != null) name.edit { replace(0, length, naming.from.orEmpty()) } }
    // At most what a group's name keeps, so what is checked is what is saved.
    LaunchedEffect(name) {
        snapshotFlow { name.text.length }.collect { if (it > InboxGroups.MAX_NAME) name.edit { replace(InboxGroups.MAX_NAME, length, "") } }
    }
    val text = InboxGroups.cleanName(name.text.toString())
    val refusal = groups.refusal(text, renaming = n.from)
    val error = when (refusal) {
        InboxGroups.NameRefusal.Taken -> stringResource(Res.string.inbox_group_name_taken)
        InboxGroups.NameRefusal.Reserved -> stringResource(Res.string.inbox_group_name_reserved, InboxGroups.OTHER)
        InboxGroups.NameRefusal.TooMany -> stringResource(Res.string.inbox_group_too_many)
        InboxGroups.NameRefusal.Blank, null -> null
    }
    Dialog(
        visible = naming != null,
        onDismissRequest = onDismiss,
        title = stringResource(if (n.from == null) Res.string.inbox_new_group else Res.string.inbox_rename_group),
        message = if (n.from == null) stringResource(Res.string.inbox_new_group_detail) else null,
        actions = {
            Button(stringResource(Res.string.common_cancel), onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(
                stringResource(if (n.from == null) Res.string.inbox_create else Res.string.inbox_save),
                onClick = { onSave(n.from, text) },
                size = ButtonSize.Small,
                enabled = refusal == null,
            )
        },
    ) {
        TextField(name, placeholder = stringResource(Res.string.inbox_group_name), error = error)
    }
}

@Composable
private fun GroupRow(name: String, jobs: Int, onRename: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Row(
        Modifier
            .fillMaxWidth()
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(Lucide.Folder, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = Theme[typography][body].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                pluralStringResource(Res.plurals.inbox_job_count, jobs, jobs),
                style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp),
                color = Theme[colors][textTertiary],
            )
        }
        IconButton(Lucide.Pencil, contentDescription = stringResource(Res.string.inbox_rename_named, name), onClick = onRename)
        IconButton(Lucide.Trash2, contentDescription = stringResource(Res.string.inbox_remove_named, name), onClick = onRemove)
    }
}

@Composable
private fun JobGroupRow(chip: JobChip, group: String?, enabled: Boolean, onClick: () -> Unit, onRename: () -> Unit) {
    val label = chip.label
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][surface])
            .border(1.dp, Theme[colors][stroke], shape)
            .clickable(enabled = enabled, onClickLabel = stringResource(Res.string.inbox_choose_group_for, label), onClick = onClick)
            .padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(Lucide.Pencil, contentDescription = stringResource(Res.string.inbox_rename_named, label), onClick = onRename)
        Column(Modifier.weight(1f)) {
            Text(label, style = Theme[typography][body], color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis)
            // A renamed job still says what it's called on the gateway.
            if (chip.renamed) {
                Text(chip.own, style = Theme[typography][code].copy(fontSize = 11.5.sp), color = Theme[colors][textMuted], maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            group ?: if (enabled) stringResource(Res.string.inbox_no_group) else "",
            style = Theme[typography][bodySmall].copy(fontWeight = FontWeight.Medium),
            color = if (group != null) Theme[colors][accentText] else Theme[colors][textTertiary],
            maxLines = 1,
        )
        if (enabled) UnstyledIcon(Lucide.ChevronDown, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp))
    }
}

/** A short list of choices in a bottom sheet, with a tick on the [selected] one. */
@Composable
private fun <T> DropdownSheet(
    visible: Boolean,
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    BottomSheet(visible = visible, onDismiss = onDismiss) {
        SheetHeader(title, stringResource(Res.string.inbox_group))
        options.forEach { option ->
            SheetAction(
                label(option),
                if (option == selected) Lucide.Check else if (option == null) Lucide.FolderX else Lucide.Folder,
                onClick = { onPick(option) },
            )
        }
    }
}

/** Every job, each set to not follow, follow quietly, or notify; and one switch for failures. */
@Composable
internal fun FollowView(state: InboxUiState, viewModel: InboxActions) {
    SubpageHeader(
        stringResource(Res.string.inbox_follow_title),
        onBack = viewModel::closeJobs,
        subtitle = stringResource(if (isDesktop) Res.string.inbox_follow_subtitle_computer else Res.string.inbox_follow_subtitle_phone),
    )
    val jobsLabel = stringResource(Res.string.inbox_jobs)
    if (state.jobsLoading && state.jobs.isEmpty()) {
        ListSkeleton()
        return
    }
    // A save replaces all this phone follows on the gateway, so nothing changes until that has been read.
    val enabled = state.canEditFollowing
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.followingError?.let { error ->
            item(key = "following-error") { FollowingNotice(error, onRetry = viewModel::chooseJobs) }
        }
        item(key = "all") { AllJobsRow(state.following.default, enabled = enabled, onSet = viewModel::setAll) }
        item(key = "failures") {
            FailuresRow(state.following.failures, enabled = enabled, onChange = viewModel::setFailures)
        }
        item(key = "label") { DayLabel(jobsLabel) }
        // A job id is only unique in its profile's cron store.
        items(state.jobs, key = { "${it.profile ?: "default"}/${it.id}" }) { job ->
            FollowRow(job, state.following.of(job.id), enabled = enabled) { viewModel.setFollow(job, it) }
        }
    }
}

/** What this phone follows couldn't be read (or a save's outcome is unknown): the page waits, with a retry. */
@Composable
private fun FollowingNotice(text: Words, onRetry: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    val tryAgain = stringResource(Res.string.inbox_try_again)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .clickable(onClickLabel = tryAgain, onClick = onRetry)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text.text(), style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
        Text(tryAgain, style = Theme[typography][bodySmall].copy(fontWeight = FontWeight.SemiBold), color = Theme[colors][accentText])
    }
}

/**
 * Every job at once: Off, Inbox or Notify for all of them, jobs made later included. A job set differently below
 * keeps its own setting until this is changed again. Jobs that stay silent never bring anything either way.
 */
@Composable
private fun AllJobsRow(default: Follow, enabled: Boolean, onSet: (Follow) -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(Res.string.inbox_all_jobs), style = Theme[typography][body].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text])
            Text(
                stringResource(Res.string.inbox_all_jobs_detail),
                style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp),
                color = Theme[colors][textTertiary],
            )
        }
        val labels = Follow.entries.associateWith {
            stringResource(
                when (it) {
                    Follow.Off -> Res.string.inbox_all_off
                    Follow.Quiet -> Res.string.inbox_all_in_inbox
                    Follow.Notify -> Res.string.inbox_all_notify
                },
            )
        }
        SegmentedControl(
            options = Follow.entries,
            selected = default,
            onSelect = { if (enabled) onSet(it) },
            enabled = enabled,
            optionLabel = { labels.getValue(it) },
        )
    }
}

@Composable
private fun FailuresRow(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][surface])
            .border(1.dp, Theme[colors][stroke], shape)
            .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(Lucide.BellRing, contentDescription = null, tint = Theme[colors][danger], modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(Res.string.inbox_failures), style = Theme[typography][body].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text])
            Text(
                stringResource(Res.string.inbox_failures_detail),
                style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp),
                color = Theme[colors][textTertiary],
            )
        }
        Switch(on)
    }
}

@Composable
private fun FollowRow(job: CronJob, follow: Follow, enabled: Boolean, onChange: (Follow) -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(job.displayName, style = Theme[typography][body].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis)
            job.scheduleDisplay.takeIf { it.isNotBlank() && it != "?" }?.let {
                Text(it, style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp), color = Theme[colors][textTertiary], maxLines = 1)
            }
        }
        val labels = Follow.entries.associateWith {
            stringResource(
                when (it) {
                    Follow.Off -> Res.string.inbox_follow_off
                    Follow.Quiet -> Res.string.inbox_title
                    Follow.Notify -> Res.string.inbox_follow_notify
                },
            )
        }
        Box(Modifier.heightIn(min = 40.dp)) {
            SegmentedControl(
                options = Follow.entries,
                selected = follow,
                onSelect = { if (enabled) onChange(it) },
                enabled = enabled,
                optionLabel = { labels.getValue(it) },
            )
        }
    }
}

/** A run's start for a card: Markdown markup dropped, lines joined. */
private fun String.toPreview(): String = this
    .replace(Regex("```[^\\n]*\\n?"), "")
    .replace(Regex("(?m)^#{1,6}\\s+"), "")
    .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
    .replace(Regex("`([^`]+)`"), "$1")
    .replace(Regex("(?m)^\\s*([-*]|━+|-{3,})\\s*"), "")
    .replace(Regex("\\s+"), " ")
    .trim()

@Composable
private fun timeLabel(at: Long): String = messageTime(at.toDouble(), uses24HourClock())

/** A run's time, after "Failed" when it failed. */
@Composable
private fun runWhen(failed: Boolean, time: String): String = if (failed) stringResource(Res.string.inbox_failed_at, time) else time

/** What a folder or chip is called: the one for jobs in no group in the app's language, any other as named. */
@Composable
private fun folderLabel(filter: InboxFilter, label: String): String =
    if (filter == InboxFilter.Group(null)) stringResource(Res.string.inbox_other) else label

/** Where a list was scrolled to: its first item showing, and how far that item is scrolled off the top. */
data class ListPlace(val index: Int = 0, val offset: Int = 0)

/** Where this list is, each time it moves. */
private fun LazyListState.places(): Flow<ListPlace> =
    snapshotFlow { ListPlace(firstVisibleItemIndex, firstVisibleItemScrollOffset) }.distinctUntilChanged()

/** The Inbox's two lists. */
private enum class ShownList { Folders, Runs }

/** Puts the list back at [place], unless it is there already. */
private suspend fun LazyListState.restore(place: ListPlace) {
    if (firstVisibleItemIndex == place.index && firstVisibleItemScrollOffset == place.offset) return
    scrollToItem(place.index, place.offset)
}
