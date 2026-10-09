package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.em
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.MessageCircleQuestion
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.PencilLine
import com.composables.icons.lucide.ShieldAlert
import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.display
import dev.hermeskotlin.designsystem.components.halo
import dev.hermeskotlin.designsystem.eyebrow
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.warningSoft
import dev.hermeskotlin.ui.chat.AppMark
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onPlaced
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
import com.composables.icons.lucide.Puzzle
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
import com.composables.icons.lucide.History
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.FolderPlus
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
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.sidebar as sidebarColor
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
import dev.hermeskotlin.designsystem.title as titleStyle
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.components.ConnectionLine
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.components.ListSkeleton
import dev.hermeskotlin.ui.components.relativeTime
import dev.hermeskotlin.ui.update.UpdateBanner
import kotlinx.coroutines.delay
import dev.hermeskotlin.core.rooms.Room
import dev.hermeskotlin.core.plugins.DashboardPlugin
import dev.hermeskotlin.core.rooms.DesktopRoom
import dev.hermeskotlin.ui.rooms.CreateRoomDialog
import dev.hermeskotlin.ui.plugins.PluginsPage
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
    /** Opens a plugin's page over the app, from the Plugins page. */
    onOpenPlugin: (DashboardPlugin) -> Unit = {},
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
    var pluginsOpen by remember { mutableStateOf(false) }
    var insightsOpen by remember { mutableStateOf(false) }
    /** The bot whose routines are showing. */
    var routinesOf by remember { mutableStateOf<Bot?>(null) }
    var actionTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var renameTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var projectActions by remember { mutableStateOf<Project?>(null) }
    var renameProject by remember { mutableStateOf<Project?>(null) }
    var deleteProject by remember { mutableStateOf<Project?>(null) }
    var newProject by remember { mutableStateOf<ProjectDraft?>(null) }
    var creatingProject by remember { mutableStateOf(false) }
    var createProjectError by remember { mutableStateOf<String?>(null) }
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
    val openAccount = {
        accountOpen = true
        viewModel.refreshProfiles()
    }

    Box(
        Modifier
            .fillMaxSize()
            .halo()
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
        } else if (pluginsOpen && !searchOpen) {
            PluginsPage(
                gateway = gateway,
                profile = profile,
                onBack = { pluginsOpen = false },
                onSessionExpired = onSessionExpired,
                onOpenPlugin = onOpenPlugin,
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
                else -> MainHeader(
                    gatewayLabel = gateway.label,
                    connection = connection,
                    onAccount = openAccount,
                    onSettings = onOpenSettings,
                    onSearch = { searchOpen = true }.takeIf { mode == SidebarMode.Chats },
                )
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
                            if (!state.loading && state.error == null && (state.projects.isNotEmpty() || state.canMakeProjects)) {
                                item(key = "projects") {
                                    ProjectFilters(
                                        state.projects,
                                        state.project,
                                        onSelect = viewModel::selectProject,
                                        onNew = if (state.canMakeProjects) ({ newProject = ProjectDraft() }) else null,
                                        onOptions = { projectActions = it },
                                    )
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
                            NavGrid(
                                listOf(
                                    NavLink(Lucide.CalendarClock, "Scheduled") { scheduledOpen = true },
                                    NavLink(Lucide.ChartColumn, "Insights") { insightsOpen = true },
                                    NavLink(Lucide.Blocks, "Capabilities") { capabilitiesOpen = true },
                                    NavLink(Lucide.Puzzle, "Plugins") { pluginsOpen = true },
                                    NavLink(Lucide.Archive, "Archived") { viewModel.setFilter(SessionListFilter.Archived) },
                                ),
                            )
                        }
                    }
                    state.loading -> ListSkeleton()
                    state.error != null -> EmptyState(Lucide.CloudOff, "Couldn't load sessions", state.error, error = true) {
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
            onAccount = openAccount,
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
    ProjectActionsSheet(
        project = projectActions,
        onDismiss = { projectActions = null },
        onRename = { renameProject = it },
        onDelete = { deleteProject = it },
        onSave = { newProject = ProjectDraft(name = it.label, folder = it.path.orEmpty()) },
    )
    RenameProjectDialog(renameProject, onDismiss = { renameProject = null }, onRename = viewModel::renameProject)
    DeleteProjectDialog(deleteProject, onDismiss = { deleteProject = null }, onDelete = viewModel::deleteProject)
    NewProjectDialog(
        draft = newProject,
        busy = creatingProject,
        error = createProjectError,
        onDismiss = {
            newProject = null
            createProjectError = null
        },
        onCreate = { name, folder ->
            creatingProject = true
            createProjectError = null
            viewModel.createProject(name, folder.ifEmpty { null }) { error ->
                creatingProject = false
                createProjectError = error
                if (error == null) newProject = null
            }
        },
        listFolders = viewModel::projectFolders,
    )
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
    /** The project the list is narrowed to, showing its options button. */
    selectedProject: Project? = null,
    /** The gateway can make projects, so New project shows. */
    canMakeProjects: Boolean = false,
) {
    Box(
        Modifier
            .fillMaxSize()
            .halo()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
    ) {
        Column(Modifier.fillMaxSize()) {
            MainHeader(gatewayLabel = "homelab", connection = null, onAccount = {}, onSettings = {}, onSearch = {})
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
                        if (projects.isNotEmpty() || canMakeProjects) {
                            item(key = "projects") {
                                ProjectFilters(projects, selected = selectedProject, onSelect = {}, onNew = if (canMakeProjects) ({}) else null)
                            }
                        }
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
                        NavGrid(
                            listOf(
                                NavLink(Lucide.CalendarClock, "Scheduled") {},
                                NavLink(Lucide.ChartColumn, "Insights") {},
                                NavLink(Lucide.Blocks, "Capabilities") {},
                                NavLink(Lucide.Puzzle, "Plugins") {},
                                NavLink(Lucide.Archive, "Archived") {},
                            ),
                        )
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

/**
 * The app's mark and name over the gateway in use, its dot showing the connection, and Settings; then the
 * search pill. The name and gateway open the account sheet.
 */
@Composable
private fun MainHeader(
    gatewayLabel: String,
    /** Null in previews, which have no connection to report. */
    connection: ConnectionState?,
    onAccount: () -> Unit,
    onSettings: () -> Unit,
    onSearch: (() -> Unit)?,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppMark(30.dp, glow = false)
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = MinTouchTarget)
                    .clip(RoundedCornerShape(Theme[radii][radiusSmall]))
                    .clickable(onClickLabel = "Account and gateways", role = Role.Button, onClick = onAccount)
                    .semantics(mergeDescendants = true) { stateDescription = connectionWord(connection) },
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Herald",
                    style = Theme[typography][heading].copy(fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
                    color = Theme[colors][text],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(connectionDot(connection), CircleShape))
                    Text(
                        gatewayLabel,
                        style = Theme[typography][caption].copy(fontSize = 12.sp),
                        color = Theme[colors][textTertiary],
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    UnstyledIcon(Lucide.ChevronDown, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(12.dp))
                }
            }
            IconButton(Lucide.Settings, contentDescription = "Settings", onClick = onSettings, tint = Theme[colors][textSecondary])
        }
        // Search looks through chats; the bot roster is short enough to read. The header keeps its height
        // without the pill, so switching sides doesn't move the switch under the finger.
        if (onSearch != null) SearchPill(onSearch) else Spacer(Modifier.height(MinTouchTarget))
    }
}

@Composable
private fun connectionDot(connection: ConnectionState?): Color = when (connection) {
    null, is ConnectionState.Connected -> Theme[colors][success]
    is ConnectionState.Failed, is ConnectionState.SessionExpired -> Theme[colors][danger]
    else -> Theme[colors][warning]
}

/** The dot's colour in words, for screen readers. */
internal fun connectionWord(connection: ConnectionState?): String = when (connection) {
    null, is ConnectionState.Connected -> "Connected"
    is ConnectionState.Failed -> "Not connected"
    is ConnectionState.SessionExpired -> "Signed out"
    is ConnectionState.Reconnecting -> "Reconnecting"
    is ConnectionState.Connecting, ConnectionState.Idle -> "Connecting"
}

/** A 40dp pill that opens search, in a full-height touch target. */
@Composable
private fun SearchPill(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = CircleShape
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .padding(horizontal = 14.dp)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(shape)
                .background(Theme[colors][surface2], shape)
                .border(1.dp, Theme[colors][stroke], shape)
                .indication(interaction, rememberColoredIndication(Theme[colors][text]))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(Lucide.Search, contentDescription = null, tint = Theme[colors][textMuted], modifier = Modifier.size(15.dp))
            Text("Search chats", style = Theme[typography][body].copy(fontSize = 14.sp), color = Theme[colors][textMuted], maxLines = 1)
        }
    }
}

/** A page's header: Back with the page's [actions] at the other end, then its title, large, underneath. */
@Composable
internal fun SubpageHeader(title: String, onBack: () -> Unit, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // A narrow page, such as one in the sidebar, takes the smaller title style.
        val style = Theme[typography][if (maxWidth < 360.dp) titleStyle else display]
        Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(Lucide.ArrowLeft, contentDescription = "Back", onClick = onBack, tint = Theme[colors][textSecondary])
                Spacer(Modifier.weight(1f))
                actions()
            }
            Text(
                title,
                style = style,
                color = Theme[colors][text],
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = Theme[typography][bodySmall].copy(fontSize = 13.5.sp),
                    color = Theme[colors][textTertiary],
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
                )
            }
        }
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

/** One of the sidebar's pages, as a tile in [NavGrid]. */
internal class NavLink(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** The sidebar's pages as tiles, two to a row. */
@Composable
private fun NavGrid(links: List<NavLink>) {
    Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 10.dp)) {
        links.chunked(2).forEach { pair ->
            // A tile whose label wraps keeps its neighbour the same height.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEach { NavTile(it, Modifier.weight(1f).fillMaxHeight()) }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** A 44dp tile, an accent icon and its label, inside a full-height touch target. */
@Composable
private fun NavTile(link: NavLink, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Box(
        modifier
            .heightIn(min = MinTouchTarget)
            .clickable(interaction, indication = null, role = Role.Button, onClick = link.onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(vertical = 2.dp)
                .clip(shape)
                .background(Theme[colors][surface2], shape)
                .indication(interaction, rememberColoredIndication(Theme[colors][text]))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(link.icon, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(16.dp))
            Text(
                link.label,
                style = Theme[typography][label].copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                color = Theme[colors][text],
                // Large text wraps rather than cutting the name off; the tile grows.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Footer: the full-width "New chat" pill and the account avatar, the list fading out above them. A failed
 * row action shows its message just above.
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
    val ground = Theme[colors][sidebarColor]
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(16.dp).background(Brush.verticalGradient(listOf(ground.copy(alpha = 0f), ground))))
        // Opaque under the banner too, so no row shows around it.
        Column(Modifier.fillMaxWidth().background(ground)) {
            if (message != null) MessageBanner(message, onDismiss = onDismissMessage, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NewChatPill(onNewChat, Modifier.weight(1f))
                Avatar(userLabel, connection, onClick = onAccount)
            }
        }
    }
}

/** The accent pill, glowing, that starts a chat. */
@Composable
private fun NewChatPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val shape = CircleShape
    val fill = Theme[colors][accent]
    Box(
        modifier
            .heightIn(min = MinTouchTarget)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 46.dp)
                .dropShadow(shape, Shadow(radius = 14.dp, color = fill.copy(alpha = 0.4f), offset = DpOffset(0.dp, 4.dp)))
                .clip(shape)
                .background(fill, shape)
                .indication(interaction, rememberColoredIndication(Theme[colors][onAccent]))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(Lucide.SquarePen, contentDescription = null, tint = Theme[colors][onAccent], modifier = Modifier.size(16.dp))
            Text(
                "New chat",
                style = Theme[typography][body].copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                color = Theme[colors][onAccent],
                maxLines = 1,
            )
        }
    }
}

/**
 * The user's initials on a 46dp disc, opening the account sheet, with a dot when the connection isn't healthy:
 * the header that also shows it is gone in search and on subpages.
 */
@Composable
private fun Avatar(userLabel: String?, connection: ConnectionState?, onClick: () -> Unit) {
    val initials = userLabel?.initials()
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(MinTouchTarget)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = "Account", onClick = onClick)
            .semantics {
                contentDescription = "Account"
                if (connection != null && connection !is ConnectionState.Connected) stateDescription = connectionWord(connection)
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Theme[colors][surface3])
                .indication(interaction, rememberColoredIndication(Theme[colors][text]))
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            if (initials.isNullOrEmpty()) {
                UnstyledIcon(Lucide.CircleUser, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(22.dp))
            } else {
                Text(initials, style = Theme[typography][label].copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = Theme[colors][textSecondary])
            }
        }
        if (connection != null && connection !is ConnectionState.Connected) {
            Box(
                Modifier
                    .size(14.dp)
                    .align(Alignment.TopEnd)
                    .background(Theme[colors][sidebarColor], CircleShape)
                    .padding(2.dp)
                    .background(connectionDot(connection), CircleShape),
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
    /** Pinned chats under a "PINNED" label, then [status] and the rest. */
    sectioned: Boolean = false,
    /** Filters and the loading, error or empty notices, shown above the unpinned chats. */
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
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp),
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
            // The filter chips head the rest, with no label of their own.
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
    Text(
        text.uppercase(),
        style = Theme[typography][eyebrow],
        color = Theme[colors][textTertiary],
        maxLines = 1,
        modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 6.dp).semantics { heading() },
    )
}

/** What a row's disc shows, the most pressing first: waiting on the user, running, an unread reply, a draft. */
internal enum class RowTone { Approval, Question, Running, Reply, Draft, Idle }

internal fun rowTone(status: RowStatus?, draft: Boolean): RowTone = when {
    status?.waiting == Waiting.Approval || status?.waiting == Waiting.Unknown -> RowTone.Approval
    status?.waiting != null -> RowTone.Question
    status?.running == true -> RowTone.Running
    status?.unread == true -> RowTone.Reply
    draft -> RowTone.Draft
    else -> RowTone.Idle
}

/** The row's second line: everything [status] and [draft] say, in words, or null for an idle chat. */
internal fun rowStatusLine(status: RowStatus?, draft: Boolean): String? = listOfNotNull(
    status?.waiting?.label,
    "Running".takeIf { status?.running == true },
    "New reply".takeIf { status?.unread == true },
    "Draft".takeIf { draft },
).joinToString(" · ").ifEmpty { null }

/**
 * A session row: a status disc, the title over what the chat is doing, and its age in mono. A pinned
 * chat with nothing going on shows a pin instead of the disc. Long-press for actions.
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
    val tone = rowTone(status, draft)
    val line = rowStatusLine(status, draft)
    val toneColor = Theme[colors][
        when (tone) {
            RowTone.Approval, RowTone.Question -> warning
            RowTone.Running, RowTone.Reply -> accentText
            RowTone.Draft -> textTertiary
            RowTone.Idle -> textMuted
        },
    ]
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(shape)
            .then(if (selected) Modifier.background(Theme[colors][surface2], shape) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onActions,
                onLongClickLabel = "Session actions",
                interactionSource = null,
                indication = rememberColoredIndication(Theme[colors][text]),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pinOnly = session.pinned && tone == RowTone.Idle
        if (pinOnly) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                UnstyledIcon(Lucide.Pin, contentDescription = "Pinned", tint = Theme[colors][textMuted], modifier = Modifier.size(14.dp))
            }
        } else {
            StatusDisc(tone, toneColor)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.displayTitle,
                    style = Theme[typography][body].copy(
                        fontSize = 14.5.sp,
                        lineHeight = 19.sp,
                        fontWeight = if (selected || tone != RowTone.Idle && tone != RowTone.Draft) FontWeight.Medium else FontWeight.Normal,
                    ),
                    color = Theme[colors][text],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (session.pinned && !pinOnly) {
                    UnstyledIcon(Lucide.Pin, contentDescription = "Pinned", tint = Theme[colors][textMuted], modifier = Modifier.size(12.dp))
                }
            }
            // Said in words as well as by the disc.
            line?.let {
                Text(it, style = Theme[typography][caption].copy(fontSize = 12.sp), color = toneColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (showSnippet) {
                val detail = session.snippet?.replace('\n', ' ')?.takeIf { it.isNotBlank() } ?: relativeTime(session.activityAt)
                if (detail.isNotEmpty()) {
                    Text(detail, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary], maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (!showSnippet) {
            Text(
                relativeTime(session.activityAt),
                style = Theme[typography][code].copy(fontSize = 11.5.sp),
                color = Theme[colors][textMuted],
                maxLines = 1,
                // Level with the title when a status line follows it.
                modifier = if (line != null) Modifier.align(Alignment.Top).padding(top = 2.dp) else Modifier,
            )
        }
    }
}

/** A 28dp disc tinted for [tone], its icon spinning while a turn runs. The row says the same in words. */
@Composable
private fun StatusDisc(tone: RowTone, color: Color) {
    val fill = when (tone) {
        RowTone.Approval, RowTone.Question -> Theme[colors][warningSoft]
        RowTone.Running, RowTone.Reply -> Theme[colors][accentSoft]
        RowTone.Draft, RowTone.Idle -> Theme[colors][surface2]
    }
    Box(Modifier.size(28.dp).background(fill, CircleShape).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        when (tone) {
            RowTone.Running -> Spinner(Modifier.size(13.dp), color = color)
            else -> UnstyledIcon(
                when (tone) {
                    RowTone.Approval -> Lucide.ShieldAlert
                    RowTone.Question -> Lucide.MessageCircleQuestion
                    RowTone.Reply -> Lucide.MessageCircle
                    RowTone.Draft -> Lucide.PencilLine
                    else -> Lucide.MessageSquare
                },
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp),
            )
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
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 6.dp, end = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AttentionFilter.entries.forEach { filter ->
            val (count, color) = when (filter) {
                AttentionFilter.All -> null to Theme[colors][textTertiary]
                AttentionFilter.Running -> running to Theme[colors][accentText]
                AttentionFilter.NeedsAttention -> needsAttention to Theme[colors][warning]
            }
            Chip(
                text = filter.label,
                selected = filter == selected,
                onClick = { onSelect(filter) },
                count = count?.takeIf { it > 0 },
                countColor = color,
            )
        }
    }
}

/**
 * The gateway's projects as chips, as Desktop's sidebar groups chats: "All", then each project with its chat
 * count, Home last. A new chat started under a project runs in its folder.
 */
@Composable
private fun ProjectFilters(
    projects: List<Project>,
    selected: Project?,
    onSelect: (Project?) -> Unit,
    /** Null when the gateway can't make projects. */
    onNew: (() -> Unit)? = null,
    onOptions: (Project) -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val scroll = rememberScrollState()
        // Where the picked chip sits in the row, so the row can scroll it into sight: its options button
        // stays beside the row, and must never read as belonging to whichever chip happens to show.
        // Keyed on the pick, so "All projects" doesn't scroll back to the chip picked before it.
        var picked by remember(selected?.id) { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
        val margin = with(LocalDensity.current) { 8.dp.toPx() }
        LaunchedEffect(selected?.id, picked, scroll.viewportSize) {
            val span = picked ?: return@LaunchedEffect
            val viewport = scroll.viewportSize.takeIf { it > 0 } ?: return@LaunchedEffect
            when {
                span.endInclusive + margin > scroll.value + viewport -> scroll.animateScrollTo((span.endInclusive + margin - viewport).toInt())
                span.start - margin < scroll.value -> scroll.animateScrollTo((span.start - margin).toInt().coerceAtLeast(0))
            }
        }
        Row(
            Modifier.weight(1f).horizontalScroll(scroll).padding(start = 6.dp, end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (projects.isNotEmpty()) Chip(text = "All projects", selected = selected == null, onClick = { onSelect(null) })
            projects.forEach { project ->
                val isPicked = project.id == selected?.id
                Chip(
                    text = project.label,
                    count = project.sessionCount,
                    selected = isPicked,
                    onClick = { onSelect(project) },
                    modifier = if (isPicked) {
                        Modifier.onPlaced { picked = it.positionInParent().x.let { x -> x..(x + it.size.width) } }
                    } else {
                        Modifier
                    },
                )
            }
            if (projects.isEmpty() && onNew != null) Chip(text = "New project", selected = false, onClick = onNew)
        }
        // The picked project's actions sit outside the scrolling chips, so they stay in reach.
        if (selected != null && !selected.isNoProject && (selected.isUserMade || (selected.path != null && onNew != null))) {
            IconButton(Lucide.Ellipsis, contentDescription = "${selected.label} options", onClick = { onOptions(selected) })
        }
        if (onNew != null && projects.isNotEmpty()) IconButton(Lucide.FolderPlus, contentDescription = "New project", onClick = onNew)
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
    onCheckpoints: ((SessionSummary) -> Unit)? = null,
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
        onCheckpoints?.let { SheetAction("Checkpoints", Lucide.History, act(it)) }
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
    // On every open, so a cancelled edit doesn't come back.
    LaunchedEffect(session != null, s.id) { if (session != null) title.edit { replace(0, length, s.title.orEmpty()) } }
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
        if (checked) UnstyledIcon(Lucide.Check, contentDescription = "Active", tint = Theme[colors][accentText], modifier = Modifier.size(20.dp))
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
            IconButton(Lucide.X, contentDescription = "Dismiss", onClick = onDismiss, tint = Theme[colors][accentText])
        }
    }
}
