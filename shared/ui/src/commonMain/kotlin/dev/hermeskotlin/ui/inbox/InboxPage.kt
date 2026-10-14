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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
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
    viewModel: InboxViewModel = koinViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(gateway) { viewModel.bind(gateway) }
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
                state.open != null -> RunView(state.open, state.nameOf(state.open), state.openLoading, state.openNote, onBack = viewModel::closeRun)
                state.choosing -> FollowView(state, viewModel)
                state.grouping -> GroupsView(state, viewModel)
                else -> InboxView(state, viewModel, onBack = onBack, onOpenSettings = onOpenSettings, foldersList = foldersList, runsList = runsList)
            }
        }
        // Over the page's foot, where the lists leave room for it: each page fills the height itself. Above the
        // sidebar's New chat button, which lies over that foot too.
        state.message?.let {
            MessageBanner(it, onDismiss = viewModel::dismissMessage, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
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
        is InboxFilter.Group -> f.name ?: "Other"
        InboxFilter.Failed -> "Failed"
        InboxFilter.All -> null
    }
    SubpageHeader(
        if (inFolder) folderName ?: "Inbox" else "Inbox",
        onBack = if (inFolder) ({ viewModel.setFilter(InboxFilter.All) }) else onBack,
        subtitle = when {
            state.runs.isEmpty() -> null
            state.shownFilter is InboxFilter.Job -> state.shown.size.let { "$it ${if (it == 1) "report" else "reports"} from ${state.nameOf(state.shown.first())}" }
            state.shownFilter is InboxFilter.Group -> state.shown.size.let { n ->
                "$n ${if (n == 1) "report" else "reports"} in ${(state.shownFilter as InboxFilter.Group).name ?: "no group"}"
            }
            unread > 0 -> "$unread unread · from the jobs you follow"
            else -> "From the jobs you follow"
        },
    ) {
        if (unread > 0) IconButton(Lucide.CheckCheck, contentDescription = "Mark all read", onClick = viewModel::markAllRead)
        IconButton(Lucide.FolderTree, contentDescription = "Folders and groups", onClick = viewModel::openGroups)
        IconButton(Lucide.Settings2, contentDescription = "Choose jobs", onClick = viewModel::chooseJobs)
    }
    state.block?.let { BlockNotice(it, onOpenSettings) }
    var renaming by remember { mutableStateOf<JobChip?>(null) }
    // Folders are the way in on the folder layout; the chips belong to the single list.
    if (!folders) FilterChips(state, onSelect = viewModel::setFilter, onRename = { renaming = it })
    RenameJobDialog(renaming, onDismiss = { renaming = null }) { chip, name ->
        viewModel.renameJob(chip, name)
        renaming = null
    }
    val shown = state.shown
    when {
        state.loading && shown.isEmpty() -> ListSkeleton()
        state.error != null && shown.isEmpty() -> EmptyState(Lucide.TriangleAlert, "Couldn't load the Inbox", state.error)
        shown.isEmpty() && state.shownFilter == InboxFilter.Failed -> EmptyState(Lucide.Inbox, "No failed runs", "Every run in the Inbox went fine.")
        shown.isEmpty() && state.block == null -> EmptyState(
            Lucide.Inbox,
            "Nothing here yet",
            "Pick the jobs whose reports you want here, like a morning briefing. Each one can notify you, or arrive quietly.",
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
                    onLongClick = (folder.filter as? InboxFilter.Job)?.let { f ->
                        state.chips.firstOrNull { it.filter == f }?.let { chip -> { renaming = chip } }
                    },
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
                item(key = run.key) { RunCard(run, state.nameOf(run), unread = state.isUnread(run), onClick = { viewModel.open(run) }) }
            }
            if (state.more || state.moreError != null) {
                item(key = "more") { MoreRow(state.loadingMore, state.moreError, onClick = viewModel::loadMore) }
            }
        }
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
        Chip("All", selected = selected == InboxFilter.All, onClick = { onSelect(InboxFilter.All) }, count = state.unread.takeIf { it > 0 })
        chips.forEach { (filter, label, unread) ->
            // A job's chip renames on a long press; a group's is renamed on the groups page.
            val job = (filter as? InboxFilter.Job)?.let { f -> state.chips.firstOrNull { it.filter == f } }
            Chip(
                label,
                selected = selected == filter,
                onClick = { onSelect(filter) },
                count = unread.takeIf { it > 0 },
                countColor = Theme[colors][accentText],
                onLongClick = job?.let { { onRename(it) } },
                onLongClickLabel = job?.let { "Rename" },
            )
        }
        if (failed > 0) {
            Chip(
                "Failed",
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
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][surface])
            .border(1.dp, Theme[colors][stroke], shape)
            .combinedClickable(
                onClickLabel = "Open ${folder.label}",
                onClick = onClick,
                onLongClickLabel = onLongClick?.let { "Rename" },
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
                    folder.label,
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
                            .semantics { contentDescription = "${folder.unread} unread" },
                    )
                }
            }
            Text(
                buildString {
                    // Only the newest runs are on the phone while the gateway has more: the count says so.
                    val reports = if (folder.runs == 1) "report" else "reports"
                    append(if (recent) "${folder.runs} recent $reports" else "${folder.runs} $reports")
                    append(" · ")
                    append(timeLabel(folder.newest.at))
                    if (folder.failed > 0) append(" · ${folder.failed} failed")
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
            .clickable(onClickLabel = "Open failed runs", onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(Theme[colors][dangerSoft], RoundedCornerShape(Theme[radii][radiusSmall])), contentAlignment = Alignment.Center) {
            UnstyledIcon(Lucide.TriangleAlert, contentDescription = null, tint = Theme[colors][danger], modifier = Modifier.size(18.dp))
        }
        Text("Failed runs", style = Theme[typography][body].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text], modifier = Modifier.weight(1f))
        Text(count.toString(), style = Theme[typography][code].copy(fontSize = 12.sp), color = Theme[colors][danger])
    }
}

@Composable
private fun BlockNotice(block: InboxBlock, onOpenSettings: () -> Unit) {
    val (text, action) = when (block) {
        InboxBlock.PushOff -> "The Inbox needs Notifications anywhere, which tells the gateway which phone this is." to "Open Settings"
        InboxBlock.PluginOld -> "The gateway's herald-push plugin needs ${InboxViewModel.NEEDS_PLUGIN} for the Inbox. Update it in Settings." to "Open Settings"
    }
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

/** A run as a card: the job, when, the start of what it said, and a dot while it's unread. */
@Composable
private fun RunCard(run: InboxRun, name: String, unread: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    val (tile, tint) = if (run.failed) Theme[colors][dangerSoft] to Theme[colors][danger] else Theme[colors][accentSoft] to Theme[colors][accentText]
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][surface])
            .border(1.dp, Theme[colors][stroke], shape)
            .clickable(onClickLabel = "Read $name", onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).background(tile, RoundedCornerShape(Theme[radii][radiusSmall])), contentAlignment = Alignment.Center) {
            UnstyledIcon(if (run.failed) Lucide.TriangleAlert else Lucide.Inbox, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
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
                    Box(Modifier.size(8.dp).background(Theme[colors][accentText], CircleShape).clearAndSetSemantics { contentDescription = "Unread" })
                }
            }
            Text(
                (if (run.failed) "Failed · " else "") + timeLabel(run.at),
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
private fun MoreRow(loading: Boolean, error: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (error != null) Text(error, style = Theme[typography][bodySmall], color = Theme[colors][danger])
        Button(
            if (loading) "Loading…" else if (error != null) "Try again" else "Older reports",
            onClick = onClick,
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Small,
            enabled = !loading,
        )
    }
}

/** One run in full, as Markdown. */
@Composable
internal fun RunView(run: InboxRun, name: String, loading: Boolean, note: RunNote?, onBack: () -> Unit) {
    SubpageHeader(name, onBack = onBack, subtitle = (if (run.failed) "Failed · " else "") + messageTime(run.at.toDouble(), uses24HourClock()))
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (note != null) {
            Text(
                when (note) {
                    RunNote.Offline -> "Only the start of this report is on the phone. Connect to the gateway to read the rest."
                    RunNote.Gone -> "Only the start of this report is on the phone: the gateway no longer has it."
                    RunNote.Failed -> "Only the start of this report is on the phone: the gateway couldn't send the rest."
                },
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
    SubpageHeader("Folders and groups", onBack = viewModel::closeGroups, subtitle = "Kept on this phone") {
        IconButton(Lucide.FolderPlus, contentDescription = "New group", onClick = { naming = GroupNaming(null) })
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "layout") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DayLabel("The Inbox opens on")
                SegmentedControl(
                    options = InboxLayout.entries,
                    selected = groups.layout,
                    onSelect = viewModel::setLayout,
                    optionLabel = { if (it == InboxLayout.Folders) "Folders" else "One list" },
                )
            }
        }
        item(key = "mode") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DayLabel(if (groups.layout == InboxLayout.Folders) "Folders are" else "Chips show")
                SegmentedControl(
                    options = ChipMode.entries,
                    selected = groups.mode,
                    onSelect = viewModel::setChipMode,
                    optionLabel = { if (it == ChipMode.Jobs) "Each job" else "My groups" },
                )
                if (groups.mode == ChipMode.Groups && groups.names.isEmpty()) {
                    Text(
                        "Make a group with the folder button above, then put jobs in it. Until then the chips show each job.",
                        style = Theme[typography][bodySmall],
                        color = Theme[colors][textTertiary],
                    )
                }
            }
        }
        if (groups.names.isNotEmpty()) {
            item(key = "groups-label") { DayLabel("Groups") }
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
                DayLabel("Jobs")
                Text(
                    "The pencil renames a job on this phone, for its chip and cards. A long press on its chip does too.",
                    style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp),
                    color = Theme[colors][textTertiary],
                    modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                )
            }
        }
        if (state.groupableJobs.isEmpty()) {
            item(key = "no-jobs") {
                Text("Jobs show here once they have reports in the Inbox.", style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
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
    DropdownSheet(
        visible = job != null,
        title = state.chips.firstOrNull { it.filter == job }?.label ?: "",
        options = listOf<String?>(null) + groups.names,
        selected = job?.let { groups.of(it.bot, it.job) },
        label = { it ?: "No group" },
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
        title = "Rename job",
        message = "Only on this phone: on the gateway it stays ${c.own}.",
        actions = {
            if (c.renamed) Button("Reset", onClick = { onSave(c, "") }, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Save", onClick = { onSave(c, name.text.toString()) }, size = ButtonSize.Small)
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
        InboxGroups.NameRefusal.Taken -> "There's a group with that name."
        InboxGroups.NameRefusal.Reserved -> "\"${InboxGroups.OTHER}\" is the folder for jobs in no group: pick another name."
        InboxGroups.NameRefusal.TooMany -> "That's the most groups there can be."
        InboxGroups.NameRefusal.Blank, null -> null
    }
    Dialog(
        visible = naming != null,
        onDismissRequest = onDismiss,
        title = if (n.from == null) "New group" else "Rename group",
        message = if (n.from == null) "Such as RUBIX or Personal. Put jobs in it from the list below." else null,
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(
                if (n.from == null) "Create" else "Save",
                onClick = { onSave(n.from, text) },
                size = ButtonSize.Small,
                enabled = refusal == null,
            )
        },
    ) {
        TextField(name, placeholder = "Group name", error = error)
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
                if (jobs == 1) "1 job" else "$jobs jobs",
                style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp),
                color = Theme[colors][textTertiary],
            )
        }
        IconButton(Lucide.Pencil, contentDescription = "Rename $name", onClick = onRename)
        IconButton(Lucide.Trash2, contentDescription = "Remove $name", onClick = onRemove)
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
            .clickable(enabled = enabled, onClickLabel = "Choose $label's group", onClick = onClick)
            .padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(Lucide.Pencil, contentDescription = "Rename $label", onClick = onRename)
        Column(Modifier.weight(1f)) {
            Text(label, style = Theme[typography][body], color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis)
            // A renamed job still says what it's called on the gateway.
            if (chip.renamed) {
                Text(chip.own, style = Theme[typography][code].copy(fontSize = 11.5.sp), color = Theme[colors][textMuted], maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            group ?: if (enabled) "No group" else "",
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
        SheetHeader(title, "Group")
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
    SubpageHeader("Jobs in the Inbox", onBack = viewModel::closeJobs, subtitle = "What each job's reports do on this phone")
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
        item(key = "label") { DayLabel("Jobs") }
        // A job id is only unique in its profile's cron store.
        items(state.jobs, key = { "${it.profile ?: "default"}/${it.id}" }) { job ->
            FollowRow(job, state.following.of(job.id), enabled = enabled) { viewModel.setFollow(job, it) }
        }
    }
}

/** What this phone follows couldn't be read (or a save's outcome is unknown): the page waits, with a retry. */
@Composable
private fun FollowingNotice(text: String, onRetry: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .clickable(onClickLabel = "Try again", onClick = onRetry)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
        Text("Try again", style = Theme[typography][bodySmall].copy(fontWeight = FontWeight.SemiBold), color = Theme[colors][accentText])
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
            Text("All jobs", style = Theme[typography][body].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text])
            Text(
                "Sets every job at once, and jobs made later. Jobs that stay silent never show up. Switch single jobs off below.",
                style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp),
                color = Theme[colors][textTertiary],
            )
        }
        SegmentedControl(
            options = Follow.entries,
            selected = default,
            onSelect = { if (enabled) onSet(it) },
            enabled = enabled,
            optionLabel = {
                when (it) {
                    Follow.Off -> "All off"
                    Follow.Quiet -> "All in Inbox"
                    Follow.Notify -> "All notify"
                }
            },
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
            Text("Tell me when any job fails", style = Theme[typography][body].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text])
            Text(
                "A notification for every failed run, including jobs you don't follow.",
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
        Box(Modifier.heightIn(min = 40.dp)) {
            SegmentedControl(
                options = Follow.entries,
                selected = follow,
                onSelect = { if (enabled) onChange(it) },
                enabled = enabled,
                optionLabel = {
                    when (it) {
                        Follow.Off -> "Off"
                        Follow.Quiet -> "Inbox"
                        Follow.Notify -> "Notify"
                    }
                },
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
