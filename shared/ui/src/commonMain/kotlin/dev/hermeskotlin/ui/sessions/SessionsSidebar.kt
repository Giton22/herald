package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import dev.hermeskotlin.designsystem.components.Chip
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Archive
import com.composables.icons.lucide.Blocks
import com.composables.icons.lucide.ArchiveRestore
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowLeftRight
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.ChartColumn
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CircleUser
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.PinOff
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.SquareTerminal
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.SearchX
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.SidebarMode
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.designsystem.components.SegmentedControl
import dev.hermeskotlin.ui.bots.BotActions
import dev.hermeskotlin.ui.bots.BotsRoster
import dev.hermeskotlin.ui.bots.BotsViewModel
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.models.displayModelName
import dev.hermeskotlin.core.profiles.Profile
import dev.hermeskotlin.core.projects.Project
import dev.hermeskotlin.core.sessions.SessionListFilter
import dev.hermeskotlin.core.sessions.SessionSummary
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.components.SectionLabel
import dev.hermeskotlin.designsystem.sidebar as sidebarColor
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.wordmark
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.SheetAction
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.heading
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.components.ConnectionLine
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.components.relativeTime
import dev.hermeskotlin.ui.update.UpdateBanner
import kotlinx.coroutines.delay
import dev.hermeskotlin.core.rooms.Room
import dev.hermeskotlin.core.rooms.DesktopRoom
import dev.hermeskotlin.ui.rooms.CreateRoomDialog
import dev.hermeskotlin.ui.rooms.RoomsViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * The sessions sidebar beside the chat, laid out like ChatGPT's: a title with a search button,
 * Scheduled and Archived as navigation rows, then the conversations as plain titles, with a floating
 * "New chat" pill and the account avatar at the bottom. [selectedId] highlights the open chat;
 * [visible] turning true refetches quietly, since the chat may have changed the list meanwhile.
 */
@Composable
fun SessionsSidebar(
    gateway: SavedGateway,
    profile: String?,
    selectedId: String?,
    visible: Boolean,
    onOpenSession: (SessionSummary) -> Unit,
    /** A new chat; [cwd] is the folder of the project the list shows, when one is picked. */
    onNewChat: (cwd: String?) -> Unit,
    onDeleted: (SessionSummary) -> Unit,
    onSessionExpired: () -> Unit,
    onSignOut: () -> Unit,
    onOpenGateways: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchProfile: (String?) -> Unit,
    /** Opens a bot's chat, by the bot and the stored session to resume. */
    onOpenBot: (Bot, String) -> Unit,
    /** Opens one of a bot's other conversations, in the bot's profile, by id and title. */
    onOpenBotSession: (Bot, String, String) -> Unit,
    /** Starts a throwaway chat in a bot's profile. */
    onNewBotChat: (Bot) -> Unit,
    /** Opens the bot editor: null makes a new bot. */
    onEditBot: (Bot?) -> Unit,
    /** A bot was deleted, e.g. to leave its chat if it was open. */
    onBotDeleted: (Bot) -> Unit,
    /** Opens a hosted room from the Rooms section. */
    onOpenRoom: (Room) -> Unit = {},
    /** Opens a Desktop room's read-only copy from the "On Desktop" group. */
    onOpenDesktopRoom: (DesktopRoom) -> Unit = {},
    /** The open chat has a turn running. */
    selectedRunning: Boolean = false,
    viewModel: SessionsViewModel = koinViewModel(),
    bots: BotsViewModel = koinViewModel(),
    rooms: RoomsViewModel = koinViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val mode by bots.mode.collectAsStateWithLifecycle()
    val botsState by bots.state.collectAsStateWithLifecycle()
    val avatars by bots.avatars.collectAsStateWithLifecycle()
    val troubles by bots.troubles.collectAsStateWithLifecycle()
    val failingRoutines by bots.failingRoutines.collectAsStateWithLifecycle()
    val needsYou by bots.needsYou.collectAsStateWithLifecycle()
    val roomsState by rooms.state.collectAsStateWithLifecycle()
    LaunchedEffect(gateway) {
        bots.bind(gateway.gatewayUrl)
        rooms.bind(gateway.gatewayUrl)
    }
    // The sidebar stays composed while closed: the live statuses (a gateway poll) are worked out only while it shows.
    LaunchedEffect(visible) {
        bots.setVisible(visible)
        viewModel.setVisible(visible)
        rooms.setVisible(visible)
    }
    LaunchedEffect(selectedId) { bots.setOpenSession(selectedId) }
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val roster by viewModel.roster.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val attentionFilter by viewModel.attentionFilter.collectAsStateWithLifecycle()
    val drafts by viewModel.draftChats.collectAsStateWithLifecycle()
    var profilesOpen by remember { mutableStateOf(false) }

    var searchOpen by remember { mutableStateOf(false) }
    var scheduledOpen by remember { mutableStateOf(false) }
    var capabilitiesOpen by remember { mutableStateOf(false) }
    var insightsOpen by remember { mutableStateOf(false) }
    /** The bot whose routines are showing. */
    var routinesOf by remember { mutableStateOf<Bot?>(null) }
    var actionTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var renameTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var accountOpen by remember { mutableStateOf(false) }
    var newRoomOpen by remember { mutableStateOf(false) }

    fun closeSearch() {
        searchOpen = false
        viewModel.query.clearText()
    }

    // Back steps out of search or the Scheduled / Archived pages before it closes the drawer.
    PlatformBackHandler(enabled = visible && (searchOpen || scheduledOpen || routinesOf != null || capabilitiesOpen || insightsOpen || state.filter != SessionListFilter.Recent)) {
        when {
            searchOpen -> closeSearch()
            scheduledOpen -> scheduledOpen = false
            routinesOf != null -> {
                routinesOf = null
                bots.refreshRoutines()
            }
            capabilitiesOpen -> capabilitiesOpen = false
            insightsOpen -> insightsOpen = false
            else -> viewModel.setFilter(SessionListFilter.Recent)
        }
    }
    LaunchedEffect(gateway, profile) { viewModel.bind(gateway, profile) }
    var wasVisible by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible && !wasVisible) viewModel.refreshQuietly()
        wasVisible = visible
    }
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

    val open: (SessionSummary) -> Unit = {
        viewModel.markSeen(it)
        onOpenSession(it)
        if (searchOpen) closeSearch()
    }
    // The open chat is being read, so whatever it has said is seen.
    val openSession = state.sessions.firstOrNull { it.id == selectedId }
    LaunchedEffect(openSession) { openSession?.let(viewModel::markSeen) }
    val rowActions: (SessionSummary) -> Unit = { actionTarget = it }

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
    ) {
        if (scheduledOpen && !searchOpen) {
            ScheduledPage(
                gateway = gateway,
                visible = visible,
                selectedId = selectedId,
                onBack = { scheduledOpen = false },
                onOpenRun = open,
                onSessionExpired = onSessionExpired,
            )
        } else if (routinesOf != null && !searchOpen) routinesOf?.let { bot ->
            ScheduledPage(
                gateway = gateway,
                visible = visible,
                selectedId = selectedId,
                onBack = {
                    routinesOf = null
                    // What was fixed there shows on the roster at once.
                    bots.refreshRoutines()
                },
                // A run is a session of the bot's own profile.
                onOpenRun = { run -> onOpenBotSession(bot, run.id, run.title?.takeIf { it.isNotBlank() } ?: bot.label) },
                onSessionExpired = onSessionExpired,
                owner = RoutineOwner(bot.name, bot.label),
            )
        } else if (capabilitiesOpen && !searchOpen) {
            CapabilitiesPage(
                gateway = gateway,
                profile = profile,
                onBack = { capabilitiesOpen = false },
                onSessionExpired = onSessionExpired,
            )
        } else if (insightsOpen && !searchOpen) {
            InsightsPage(
                gateway = gateway,
                profile = profile,
                onBack = { insightsOpen = false },
                onSessionExpired = onSessionExpired,
            )
        } else Column(Modifier.fillMaxSize()) {
            when {
                searchOpen -> SearchHeader(viewModel, onClose = ::closeSearch)
                state.filter != SessionListFilter.Recent -> SubpageHeader(
                    title = state.filter.label,
                    onBack = { viewModel.setFilter(SessionListFilter.Recent) },
                )
                // Search looks through chats; the bot roster is short enough to read.
                else -> MainHeader(onSearch = { searchOpen = true }.takeIf { mode == SidebarMode.Chats })
            }
            if (!searchOpen && state.filter == SessionListFilter.Recent) {
                SegmentedControl(
                    options = SidebarMode.entries,
                    selected = mode,
                    onSelect = bots::setMode,
                    // How many bots need the user, readable from the Chats side too.
                    optionLabel = { if (it == SidebarMode.Bots && needsYou.isNotEmpty()) "${it.name} · ${needsYou.size}" else it.name },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
                UpdateBanner(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val searchResults = state.searchResults
                when {
                    !searchOpen && state.filter == SessionListFilter.Recent && mode == SidebarMode.Bots -> {
                        BotsRoster(
                            state = botsState,
                            avatars = avatars,
                            selectedId = selectedId,
                            selectedRunning = selectedRunning,
                            nowSeconds = bots.nowSeconds(),
                            actions = remember(bots, onOpenBot, onOpenBotSession, onNewBotChat, onEditBot, onBotDeleted) {
                                object : BotActions {
                                    override fun open(bot: Bot) = bots.open(bot) { id -> onOpenBot(bot, id) }
                                    override fun setPinned(bot: Bot, pinned: Boolean) = bots.setPinned(bot, pinned)
                                    override fun setHidden(bot: Bot, hidden: Boolean) = bots.setHidden(bot, hidden)
                                    override fun startFresh(bot: Bot) = bots.startFresh(bot) { id -> onOpenBot(bot, id) }
                                    override fun openRecent(bot: Bot) {
                                        val recent = bot.lastSession ?: return
                                        onOpenBotSession(bot, recent.id ?: return, recent.title?.takeIf { it.isNotBlank() } ?: bot.label)
                                    }
                                    override fun newChat(bot: Bot) = onNewBotChat(bot)
                                    override fun create() = onEditBot(null)
                                    override fun edit(bot: Bot) = onEditBot(bot)
                                    override fun duplicate(bot: Bot) = bots.duplicate(bot)
                                    override fun delete(bot: Bot) = bots.delete(bot) { onBotDeleted(bot) }
                                    override fun checkAgain(bot: Bot) = bots.checkAgain(bot)
                                    override fun routines(bot: Bot) {
                                        routinesOf = bot
                                    }
                                }
                            },
                            onRetry = bots::refresh,
                            onDismissNotice = bots::dismissNotice,
                            troubles = troubles,
                            failingRoutines = failingRoutines,
                            needsYou = needsYou,
                            rooms = roomsState.rooms,
                            roomsAvailable = roomsState.available,
                            unreadRooms = roomsState.unread,
                            onOpenRoom = onOpenRoom,
                            onOpenDesktopRoom = onOpenDesktopRoom,
                            onNewRoom = {
                                rooms.dismissNotice()
                                newRoomOpen = true
                            },
                            onRenameRoom = rooms::renameRoom,
                            onDeleteRoom = rooms::deleteRoom,
                            roomsNotice = roomsState.actionNotice,
                            onDismissRoomsNotice = rooms::dismissActionNotice,
                        )
                        CreateRoomDialog(
                            visible = newRoomOpen,
                            bots = botsState.all,
                            busy = roomsState.busy,
                            error = roomsState.notice,
                            onDismiss = {
                                newRoomOpen = false
                                rooms.dismissNotice()
                            },
                            onCreate = { name, members ->
                                rooms.createRoom(name, members) { room ->
                                    newRoomOpen = false
                                    onOpenRoom(room)
                                }
                            },
                        )
                    }
                    searchOpen && searchResults == null -> Unit
                    searchResults != null -> when {
                        state.searching && searchResults.isEmpty() -> CenteredSpinner()
                        searchResults.isEmpty() -> EmptyState(Lucide.SearchX, "No matches", "Search looks at titles, session ids and message text.")
                        else -> SessionList(searchResults, selectedId, open, rowActions, showSnippets = true, statuses = statuses, drafts = drafts)
                    }
                    state.filter == SessionListFilter.Recent -> SessionList(
                        sessions = when (attentionFilter) {
                            AttentionFilter.All -> state.listed
                            AttentionFilter.Running -> state.listed.filter { statuses[it.id]?.running == true }
                            AttentionFilter.NeedsAttention -> state.listed.filter { statuses[it.id]?.needsAttention == true }
                        },
                        selectedId = selectedId,
                        onOpen = open,
                        onActions = rowActions,
                        // A filtered list stays short, so the end is always in sight: paging on would
                        // fetch the whole history. What's running or waiting is recent anyway. A project's
                        // chats come whole.
                        canLoadMore = state.canLoadMore && attentionFilter == AttentionFilter.All && state.project == null,
                        loadingMore = state.loadingMore,
                        onLoadMore = viewModel::loadMore,
                        statuses = statuses,
                        drafts = drafts,
                        sectioned = true,
                        status = {
                            if (!state.loading && state.error == null && state.projects.isNotEmpty()) {
                                item(key = "projects") {
                                    ProjectFilters(state.projects, state.project, onSelect = viewModel::selectProject)
                                }
                            }
                            if (!state.loading && state.error == null && state.listed.isNotEmpty()) {
                                item(key = "filters") {
                                    AttentionFilters(
                                        selected = attentionFilter,
                                        running = state.listed.count { statuses[it.id]?.running == true },
                                        needsAttention = state.listed.count { statuses[it.id]?.needsAttention == true },
                                        onSelect = viewModel::setAttentionFilter,
                                    )
                                }
                            }
                            when {
                                state.loading -> item(key = "loading") { ListSpinner() }
                                state.error != null -> item(key = "error") {
                                    ListNotice("Couldn't load sessions. ${state.error}", action = "Try again", onAction = viewModel::refresh)
                                }
                                state.project != null && state.projectSessions == null -> item(key = "project-loading") { ListSpinner() }
                                state.listed.isEmpty() -> item(key = "empty") {
                                    ListNotice(if (state.project != null) "No chats in this project." else "Your conversations will show up here.")
                                }
                                attentionFilter == AttentionFilter.Running && state.listed.none { statuses[it.id]?.running == true } -> item(key = "none-running") {
                                    ListNotice("Nothing is running right now.")
                                }
                                attentionFilter == AttentionFilter.NeedsAttention && state.listed.none { statuses[it.id]?.needsAttention == true } ->
                                    item(key = "none-waiting") { ListNotice("Nothing needs you right now.") }
                            }
                        },
                    ) {
                        item(key = "nav") {
                            Column(Modifier.padding(bottom = 4.dp)) {
                                NavRow(Lucide.CalendarClock, "Scheduled jobs") { scheduledOpen = true }
                                NavRow(Lucide.ChartColumn, "Insights") { insightsOpen = true }
                                NavRow(Lucide.Blocks, "Capabilities") { capabilitiesOpen = true }
                                NavRow(Lucide.Archive, "Archived") { viewModel.setFilter(SessionListFilter.Archived) }
                            }
                        }
                    }
                    state.loading -> CenteredSpinner()
                    state.error != null -> EmptyState(Lucide.CloudOff, "Couldn't load sessions", state.error) {
                        Button("Try again", onClick = viewModel::refresh, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
                    }
                    state.sessions.isEmpty() ->
                        EmptyState(Lucide.Archive, "Nothing archived", "Archived sessions are hidden from Recent but stay resumable.")
                    else -> SessionList(
                        sessions = state.sessions,
                        selectedId = selectedId,
                        onOpen = open,
                        onActions = rowActions,
                        canLoadMore = state.canLoadMore,
                        loadingMore = state.loadingMore,
                        onLoadMore = viewModel::loadMore,
                        drafts = drafts,
                    )
                }
            }
        }

        BottomBar(
            userLabel = user?.label,
            connection = connection,
            message = state.message,
            onDismissMessage = viewModel::dismissMessage,
            onNewChat = {
                if (searchOpen) closeSearch()
                onNewChat(state.project?.path.takeIf { state.filter == SessionListFilter.Recent })
            },
            onAccount = {
                accountOpen = true
                viewModel.refreshProfiles()
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    SessionActionsSheet(
        session = actionTarget,
        onDismiss = { actionTarget = null },
        onTogglePinned = { viewModel.togglePinned(it) },
        onRename = { renameTarget = it },
        onToggleArchived = { viewModel.toggleArchived(it) },
        onDelete = { deleteTarget = it },
    )
    RenameDialog(renameTarget, onDismiss = { renameTarget = null }, onRename = viewModel::rename)
    DeleteDialog(
        deleteTarget,
        onDismiss = { deleteTarget = null },
        onDelete = {
            viewModel.delete(it)
            onDeleted(it)
        },
    )
    // The picked profile, else the one the gateway runs as.
    val activeProfile = roster?.let { r -> r.profiles.find { it.name == (profile ?: r.launch) } }
    AccountSheet(
        visible = accountOpen,
        gateway = gateway,
        userLabel = user?.label,
        profileLabel = activeProfile?.label ?: profile,
        connection = connection,
        refreshing = state.refreshing,
        onDismiss = { accountOpen = false },
        onOpenProfiles = { profilesOpen = true },
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retryConnection,
        onSignOut = onSignOut,
        onOpenGateways = onOpenGateways,
        onOpenSettings = onOpenSettings,
    )
    ProfileSheet(
        visible = profilesOpen && roster != null,
        profiles = roster?.profiles.orEmpty(),
        selected = activeProfile?.name,
        onDismiss = { profilesOpen = false },
        onSelect = { picked ->
            profilesOpen = false
            // The launch profile is stored as "none picked", so calls stay exactly as before profiles.
            onSwitchProfile(picked.name.takeIf { it != roster?.launch })
        },
    )
}

/**
 * The sidebar's main page drawn from [sessions] alone, for previews: the same header, sections and
 * bottom bar as [SessionsSidebar], with nothing to load or act on.
 */
@Composable
internal fun SessionsSidebarSample(
    sessions: List<SessionSummary>,
    selectedId: String?,
    userLabel: String,
    statuses: Map<String, RowStatus> = emptyMap(),
    drafts: Set<String> = emptySet(),
    projects: List<Project> = emptyList(),
) {
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
    ) {
        Column(Modifier.fillMaxSize()) {
            MainHeader(onSearch = {})
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SessionList(
                    sessions,
                    selectedId,
                    onOpen = {},
                    onActions = {},
                    statuses = statuses,
                    drafts = drafts,
                    sectioned = true,
                    status = {
                        if (projects.isNotEmpty()) item(key = "projects") { ProjectFilters(projects, selected = null, onSelect = {}) }
                        item(key = "filters") {
                            AttentionFilters(
                                selected = AttentionFilter.All,
                                running = sessions.count { statuses[it.id]?.running == true },
                                needsAttention = sessions.count { statuses[it.id]?.needsAttention == true },
                                onSelect = {},
                            )
                        }
                    },
                ) {
                    item(key = "nav") {
                        Column(Modifier.padding(bottom = 4.dp)) {
                            NavRow(Lucide.CalendarClock, "Scheduled jobs") {}
                            NavRow(Lucide.ChartColumn, "Insights") {}
                            NavRow(Lucide.Blocks, "Capabilities") {}
                            NavRow(Lucide.Archive, "Archived") {}
                        }
                    }
                }
            }
        }
        BottomBar(
            userLabel = userLabel,
            connection = null,
            message = null,
            onDismissMessage = {},
            onNewChat = {},
            onAccount = {},
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private val SessionListFilter.label: String
    get() = when (this) {
        SessionListFilter.Recent -> "Chats"
        SessionListFilter.Archived -> "Archived"
    }

@Composable
private fun MainHeader(onSearch: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "HERALD",
            style = Theme[typography][wordmark].copy(fontSize = 22.sp, lineHeight = 26.sp),
            color = Theme[colors][text],
            modifier = Modifier.weight(1f),
        )
        // The header keeps its height without the button, so switching sides doesn't shift the list.
        if (onSearch != null) SquareButton(Lucide.Search, "Search chats", onClick = onSearch) else Box(Modifier.size(MinTouchTarget))
    }
}

@Composable
internal fun SubpageHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(Lucide.ArrowLeft, contentDescription = "Back", onClick = onBack, tint = Theme[colors][text])
        Text(
            title,
            style = Theme[typography][heading],
            color = Theme[colors][text],
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
private fun SearchHeader(viewModel: SessionsViewModel, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            state = viewModel.query,
            placeholder = "Search chats",
            leadingIcon = Lucide.Search,
            clearable = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            focusRequester = focus,
            modifier = Modifier.weight(1f),
        )
        IconButton(Lucide.X, contentDescription = "Close search", onClick = onClose, tint = Theme[colors][text])
    }
}

/** A 48dp square icon button with no fill. */
@Composable
private fun SquareButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(MinTouchTarget)
            .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
            .clickable(onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        UnstyledIcon(icon, contentDescription = contentDescription, tint = Theme[colors][textSecondary], modifier = Modifier.size(20.dp))
    }
}

/** Desktop's sidebar links: a line icon and a medium-weight label, compact. */
@Composable
private fun NavRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(18.dp))
        Text(label, style = Theme[typography][body].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text])
    }
}

/**
 * Footer under a hairline: "New session" and the account avatar. A failed row action shows its
 * message just above.
 */
@Composable
private fun BottomBar(
    userLabel: String?,
    /** Null in previews, which have no connection to report. */
    connection: ConnectionState?,
    message: String?,
    onDismissMessage: () -> Unit,
    onNewChat: () -> Unit,
    onAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(Theme[colors][sidebarColor]), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (message != null) MessageBanner(message, onDismiss = onDismissMessage)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Theme[colors][stroke]))
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button("New session", onClick = onNewChat, leadingIcon = Lucide.SquarePen)
            Box(Modifier.weight(1f))
            Avatar(userLabel, connection, onClick = onAccount)
        }
    }
}

/** Initials in a circle, with a small dot when the gateway connection isn't healthy. */
@Composable
private fun Avatar(userLabel: String?, connection: ConnectionState?, onClick: () -> Unit) {
    val initials = userLabel?.initials()
    Box(Modifier.size(52.dp)) {
        Box(
            Modifier
                .size(48.dp)
                .align(Alignment.Center)
                .clip(CircleShape)
                .background(Theme[colors][accent])
                .clickable(onClickLabel = "Account", onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (initials.isNullOrEmpty()) {
                UnstyledIcon(Lucide.CircleUser, contentDescription = "Account", tint = Theme[colors][onAccent], modifier = Modifier.size(24.dp))
            } else {
                Text(initials, style = Theme[typography][label], color = Theme[colors][onAccent])
            }
        }
        val dot = when (connection) {
            null, is ConnectionState.Connected -> null
            is ConnectionState.Failed, is ConnectionState.SessionExpired -> Theme[colors][danger]
            else -> Theme[colors][warning]
        }
        if (dot != null) {
            Box(
                Modifier
                    .size(14.dp)
                    .align(Alignment.TopEnd)
                    .background(Theme[colors][sidebarColor], CircleShape)
                    .padding(2.dp)
                    .background(dot, CircleShape),
            )
        }
    }
}

private fun String.initials(): String =
    substringBefore('@').split(' ', '.', '_', '-').filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }

@Composable
private fun SessionList(
    sessions: List<SessionSummary>,
    selectedId: String?,
    onOpen: (SessionSummary) -> Unit,
    onActions: (SessionSummary) -> Unit,
    showSnippets: Boolean = false,
    canLoadMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    /** What each row says besides its title: waiting on the user, or an unread reply. */
    statuses: Map<String, RowStatus> = emptyMap(),
    /** Sessions with unsent text, marked "Draft". */
    drafts: Set<String> = emptySet(),
    /** Group under "PINNED" and "SESSIONS" labels, Desktop's sidebar sections. */
    sectioned: Boolean = false,
    /** Loading, error or empty notices, shown under the "SESSIONS" label. */
    status: LazyListScope.() -> Unit = {},
    header: LazyListScope.() -> Unit = {},
) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, canLoadMore, sessions.size) { if (nearEnd && canLoadMore) onLoadMore() }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // Room to scroll the last rows out from under the floating footer.
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
    ) {
        header()
        // Pinned chats stay on top, marked with a pin.
        val (pinned, rest) = sessions.partition { it.pinned }
        val row: @Composable (SessionSummary) -> Unit = { session ->
            SessionRow(
                session,
                selected = session.id == selectedId,
                showSnippet = showSnippets,
                status = statuses[session.id],
                draft = session.id in drafts,
                onClick = { onOpen(session) },
                onActions = { onActions(session) },
            )
        }
        if (sectioned) {
            if (pinned.isNotEmpty()) {
                item(key = "label-pinned") { ListLabel("Pinned") }
                items(pinned, key = { it.id }) { row(it) }
            }
            item(key = "label-sessions") { ListLabel("Sessions") }
            status()
            items(rest, key = { it.id }) { row(it) }
            if (loadingMore) item(key = "loading-more") { ListSpinner() }
            return@LazyColumn
        }
        items(pinned + rest, key = { it.id }) { row(it) }
        if (loadingMore) item(key = "loading-more") { ListSpinner() }
    }
}

@Composable
private fun ListLabel(text: String) {
    SectionLabel(text, Modifier.padding(start = 12.dp, top = 16.dp, bottom = 8.dp))
}

/**
 * Desktop's session row: a status dot, the title and its age. The dot lights up while a turn is
 * running ([RowStatus.running]); [draft] adds a "Draft" label for unsent text. Long-press for actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionRow(
    session: SessionSummary,
    selected: Boolean,
    showSnippet: Boolean,
    status: RowStatus? = null,
    draft: Boolean = false,
    onClick: () -> Unit,
    onActions: () -> Unit,
) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(shape)
            .then(if (selected) Modifier.background(Theme[colors][accentSoft], shape) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onActions,
                onLongClickLabel = "Session actions",
                interactionSource = null,
                indication = rememberColoredIndication(Theme[colors][text]),
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(6.dp).background(
                    if (status?.running == true) Theme[colors][success] else Theme[colors][strokeStrong],
                    CircleShape,
                ),
            )
            Text(
                session.displayTitle,
                style = Theme[typography][body].copy(fontSize = 15.sp, fontWeight = if (selected) FontWeight.Medium else null),
                color = Theme[colors][text],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (session.pinned) {
                UnstyledIcon(Lucide.Pin, contentDescription = "Pinned", tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
            }
            if (draft) {
                Text("Draft", style = Theme[typography][caption], color = Theme[colors][accent], maxLines = 1)
            }
            if (!showSnippet) {
                Text(relativeTime(session.activityAt), style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1)
            }
        }
        // Said in words as well as by the dot's colour.
        val labels = buildList {
            status?.waiting?.let { add(it.label to Theme[colors][warning]) }
            if (status?.running == true) add("Running" to Theme[colors][success])
            if (status?.unread == true) add("New reply" to Theme[colors][accent])
        }
        if (labels.isNotEmpty()) {
            Row(Modifier.padding(start = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                labels.forEach { (label, color) -> Text(label, style = Theme[typography][caption], color = color, maxLines = 1) }
            }
        }
        if (showSnippet) {
            val detail = session.snippet?.replace('\n', ' ')?.takeIf { it.isNotBlank() } ?: relativeTime(session.activityAt)
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textSecondary],
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}

@Composable
internal fun ListSpinner() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner(Modifier.size(20.dp)) }
}

/** All / Running / Needs attention over the recent list, with how many each holds. */
@Composable
private fun AttentionFilters(selected: AttentionFilter, running: Int, needsAttention: Int, onSelect: (AttentionFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AttentionFilter.entries.forEach { filter ->
            val count = when (filter) {
                AttentionFilter.All -> null
                AttentionFilter.Running -> running
                AttentionFilter.NeedsAttention -> needsAttention
            }
            Chip(
                text = if (count != null && count > 0) "${filter.label} · $count" else filter.label,
                selected = filter == selected,
                onClick = { onSelect(filter) },
            )
        }
    }
}

/**
 * The gateway's projects as chips, as Desktop's sidebar groups chats: "All", then each project with its chat
 * count, Home last. A new chat started under a project runs in its folder.
 */
@Composable
private fun ProjectFilters(projects: List<Project>, selected: Project?, onSelect: (Project?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip(text = "All projects", selected = selected == null, onClick = { onSelect(null) })
        projects.forEach { project ->
            Chip(
                text = "${project.label} · ${project.sessionCount}",
                selected = project.id == selected?.id,
                onClick = { onSelect(project) },
            )
        }
    }
}

@Composable
internal fun ListNotice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        if (action != null) Button(action, onClick = onAction, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
    }
}

/** A session's verbs, from a sidebar row or the open chat's menu; the chat adds export and copy id. */
@Composable
internal fun SessionActionsSheet(
    session: SessionSummary?,
    onDismiss: () -> Unit,
    /** Null leaves Rename, Pin, Archive and Delete out, as for a bot's chat, whose title is what makes it the bot's. */
    onTogglePinned: ((SessionSummary) -> Unit)?,
    onRename: ((SessionSummary) -> Unit)?,
    onToggleArchived: ((SessionSummary) -> Unit)?,
    onDelete: ((SessionSummary) -> Unit)?,
    onExport: ((SessionSummary) -> Unit)? = null,
    onCopyId: ((SessionSummary) -> Unit)? = null,
    onUsage: ((SessionSummary) -> Unit)? = null,
    onProcesses: ((SessionSummary) -> Unit)? = null,
    /** A bot's chat: archive it and begin an empty one. */
    onStartFresh: ((SessionSummary) -> Unit)? = null,
) {
    // Keep the last target while the sheet animates out.
    var shown by remember { mutableStateOf(session) }
    if (session != null) shown = session
    BottomSheet(visible = session != null, onDismiss = onDismiss) {
        val s = shown ?: return@BottomSheet
        val age = when (val ago = relativeTime(s.activityAt)) {
            "" -> null
            "now" -> "Active just now"
            else -> "Active $ago ago"
        }
        SheetHeader(s.displayTitle, age)
        fun act(block: (SessionSummary) -> Unit) = { onDismiss(); block(s) }
        onStartFresh?.let { SheetAction("Start fresh", Lucide.RotateCcw, act(it)) }
        onRename?.let { SheetAction("Rename", Lucide.Pencil, act(it)) }
        onTogglePinned?.let { SheetAction(if (s.pinned) "Unpin" else "Pin", if (s.pinned) Lucide.PinOff else Lucide.Pin, act(it)) }
        onUsage?.let { SheetAction("Usage and cost", Lucide.Gauge, act(it)) }
        onProcesses?.let { SheetAction("Background processes", Lucide.SquareTerminal, act(it)) }
        onExport?.let { SheetAction("Export as Markdown", Lucide.Download, act(it)) }
        onCopyId?.let { SheetAction("Copy session ID", Lucide.Copy, act(it)) }
        onToggleArchived?.let {
            SheetAction(if (s.archived) "Unarchive" else "Archive", if (s.archived) Lucide.ArchiveRestore else Lucide.Archive, act(it))
        }
        onDelete?.let { SheetAction("Delete", Lucide.Trash2, act(it), destructive = true) }
    }
}

@Composable
internal fun RenameDialog(session: SessionSummary?, onDismiss: () -> Unit, onRename: (SessionSummary, String) -> Unit) {
    var shown by remember { mutableStateOf(session) }
    if (session != null) shown = session
    val s = shown ?: return
    val title = rememberTextFieldState(s.title.orEmpty())
    LaunchedEffect(s.id) { title.edit { replace(0, length, s.title.orEmpty()) } }
    val submit = {
        onDismiss()
        onRename(s, title.text.toString())
    }
    Dialog(
        visible = session != null,
        onDismissRequest = onDismiss,
        title = "Rename session",
        message = "Leave empty to clear the title.",
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Save", onClick = submit, size = ButtonSize.Small)
        },
    ) {
        TextField(
            state = title,
            placeholder = s.displayTitle,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = { submit() },
        )
    }
}

@Composable
internal fun DeleteDialog(session: SessionSummary?, onDismiss: () -> Unit, onDelete: (SessionSummary) -> Unit) {
    var shown by remember { mutableStateOf(session) }
    if (session != null) shown = session
    val s = shown ?: return
    Dialog(
        visible = session != null,
        onDismissRequest = onDismiss,
        title = "Delete session?",
        message = "“${s.displayTitle}” and its transcript are removed from the gateway. This can't be undone.",
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(
                "Delete",
                onClick = { onDismiss(); onDelete(s) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
            )
        },
    )
}

@Composable
private fun AccountSheet(
    visible: Boolean,
    gateway: SavedGateway,
    userLabel: String?,
    profileLabel: String?,
    connection: ConnectionState,
    refreshing: Boolean,
    onDismiss: () -> Unit,
    onOpenProfiles: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    onOpenGateways: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    BottomSheet(visible = visible, onDismiss = onDismiss) {
        SheetHeader(userLabel ?: "Signed in", gateway.url)
        Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { ConnectionLine(connection, detailed = true) }
        if (profileLabel != null) {
            SheetAction("Profile: $profileLabel", Lucide.Bot, onClick = { onDismiss(); onOpenProfiles() })
        }
        SheetAction("Settings", Lucide.Settings, onClick = { onDismiss(); onOpenSettings() })
        SheetAction(if (refreshing) "Refreshing chats…" else "Refresh chats", Lucide.RefreshCw, onClick = { onDismiss(); onRefresh() })
        if (connection is ConnectionState.Reconnecting || connection is ConnectionState.Failed) {
            SheetAction("Retry connection now", Lucide.RefreshCw, onClick = onRetry)
        }
        SheetAction("Sign out", Lucide.LogOut, onClick = { onDismiss(); onSignOut() })
        SheetAction("Switch or add a gateway", Lucide.ArrowLeftRight, onClick = { onDismiss(); onOpenGateways() })
    }
}

/**
 * Desktop's profile picker: the gateway's profiles, default first, the active one checked. Each is its
 * own agent, so switching changes the chats listed and starts from where that profile left off.
 */
@Composable
private fun ProfileSheet(
    visible: Boolean,
    profiles: List<Profile>,
    selected: String?,
    onDismiss: () -> Unit,
    onSelect: (Profile) -> Unit,
) {
    BottomSheet(visible = visible, onDismiss = onDismiss) {
        SheetHeader("Profiles", "Each profile is its own agent, with its own chats, memory and model.")
        profiles.forEach { profile ->
            ProfileRow(profile, checked = profile.name == selected, onClick = { onSelect(profile) })
        }
        if (profiles.size < 2) {
            Text(
                "Add profiles on the gateway host with “hermes profile create”.",
                style = Theme[typography][bodySmall],
                color = Theme[colors][textTertiary],
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ProfileRow(profile: Profile, checked: Boolean, onClick: () -> Unit) {
    val detail = profile.description?.trim()?.takeIf { it.isNotEmpty() } ?: profile.model?.let(::displayModelName)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(Theme[colors][if (checked) accent else surface]),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                profile.label.take(1).uppercase(),
                style = Theme[typography][caption].copy(fontWeight = FontWeight.SemiBold),
                color = Theme[colors][if (checked) onAccent else textSecondary],
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(profile.label, style = Theme[typography][body], color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull("Default".takeIf { profile.isDefault }, detail).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary], maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (checked) UnstyledIcon(Lucide.Check, contentDescription = "Active", tint = Theme[colors][accent], modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun CenteredSpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
}

@Composable
internal fun MessageBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.padding(horizontal = 16.dp).fillMaxWidth(), elevated = true) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = Theme[typography][bodySmall], color = Theme[colors][text], modifier = Modifier.weight(1f))
            IconButton(Lucide.X, contentDescription = "Dismiss", onClick = onDismiss, tint = Theme[colors][accent])
        }
    }
}
