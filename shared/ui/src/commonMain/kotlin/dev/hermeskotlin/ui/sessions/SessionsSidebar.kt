package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.designsystem.components.Tooltip
import dev.hermeskotlin.designsystem.components.handCursor
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
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.ChevronsUpDown
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Folders
import com.composables.icons.lucide.House
import com.composeunstyled.AnchorAlignment
import dev.hermeskotlin.designsystem.components.DropdownMenu
import dev.hermeskotlin.designsystem.components.MenuChoice
import dev.hermeskotlin.designsystem.components.MenuAction
import dev.hermeskotlin.designsystem.components.MenuSeparator
import com.composables.icons.lucide.CircleUserRound
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import com.composables.icons.lucide.AppWindow
import dev.hermeskotlin.ui.clearOfWindowControls
import dev.hermeskotlin.ui.CopyableOnDesktop
import dev.hermeskotlin.ui.arrowFocus
import dev.hermeskotlin.ui.isDesktop
import dev.hermeskotlin.ui.keysLabel
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.MessageCircleQuestion
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.PencilLine
import com.composables.icons.lucide.ShieldAlert
import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.display
import dev.hermeskotlin.designsystem.components.glow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.Layout
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import dev.hermeskotlin.ui.MainPageSlot
import androidx.compose.ui.semantics.selected
import dev.hermeskotlin.designsystem.components.verticalScrollWithBar
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import dev.hermeskotlin.core.settings.SettingsStore
import org.koin.compose.koinInject
import dev.hermeskotlin.designsystem.components.LocalOpenModals
import androidx.compose.runtime.mutableIntStateOf
import dev.hermeskotlin.core.chat.ChatHost
import com.composables.icons.lucide.Inbox
import dev.hermeskotlin.core.inbox.InboxLink
import dev.hermeskotlin.core.inbox.InboxStore
import dev.hermeskotlin.ui.inbox.InboxPage
import dev.hermeskotlin.ui.inbox.ReportChat
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
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
import com.composables.icons.lucide.SlidersHorizontal
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
import com.composables.icons.lucide.Target
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.FolderPlus
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Hash
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.ArrowDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import dev.hermeskotlin.core.threads.ChatThread
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
import dev.hermeskotlin.designsystem.rememberHoverIndication
import androidx.compose.foundation.LocalIndication
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.SidebarMode
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.designsystem.components.SegmentedControl
import dev.hermeskotlin.ui.bots.BotActions
import dev.hermeskotlin.ui.bots.BotsRoster
import dev.hermeskotlin.ui.bots.BotsUiState
import dev.hermeskotlin.ui.bots.NeedsYou
import dev.hermeskotlin.ui.bots.BotsViewModel
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.models.displayModelName
import dev.hermeskotlin.core.profiles.Profile
import dev.hermeskotlin.core.profiles.ProfileRoster
import dev.hermeskotlin.core.projects.Project
import dev.hermeskotlin.core.projects.ProjectIndex
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
import dev.hermeskotlin.designsystem.components.ActionSheet
import dev.hermeskotlin.designsystem.components.ContextMenu
import dev.hermeskotlin.designsystem.components.MenuRow
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.VerticalScrollbar
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
import dev.hermeskotlin.ui.Shortcut
import dev.hermeskotlin.ui.ShortcutHandler
import dev.hermeskotlin.ui.SidebarPage
import dev.hermeskotlin.ui.LocalShortcuts
import dev.hermeskotlin.ui.RowClick
import dev.hermeskotlin.ui.rowClick
import dev.hermeskotlin.ui.chat.ChatTarget
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import dev.hermeskotlin.ui.stepChat
import dev.hermeskotlin.ui.components.ConnectionLine
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.components.ListSkeleton
import dev.hermeskotlin.ui.update.RenameBanner
import dev.hermeskotlin.ui.update.UpdateBanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.utf16CodePoint
import dev.hermeskotlin.ui.macKeys
import kotlin.time.Clock
import dev.hermeskotlin.core.rooms.Room
import dev.hermeskotlin.core.plugins.DashboardPlugin
import dev.hermeskotlin.core.rooms.DesktopRoom
import dev.hermeskotlin.ui.rooms.CreateRoomDialog
import dev.hermeskotlin.ui.plugins.PluginsPage
import dev.hermeskotlin.ui.rooms.RoomsViewModel
import org.koin.compose.viewmodel.koinViewModel
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

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
    /** Opens a chat, with how far it had been read when it has new messages since (null otherwise). */
    onOpenSession: (session: SessionSummary, readUpTo: Double?) -> Unit,
    /** A new chat; [cwd] is the folder of the project the list shows, when one is picked. */
    onNewChat: (cwd: String?) -> Unit,
    onDeleted: (SessionSummary) -> Unit,
    onSessionExpired: () -> Unit,
    onSignOut: () -> Unit,
    onOpenGateways: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchProfile: (String?) -> Unit,
    /** Opens a bot's chat, by the bot and the stored session to resume, with how far it had been read when it has new messages. */
    onOpenBot: (bot: Bot, storedSessionId: String, readUpTo: Double?) -> Unit,
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
    /** A tapped Inbox notification the app passed on: the Inbox opens, at its run; [onInboxLinkTaken] once it has. */
    inboxLink: InboxLink? = null,
    onInboxLinkTaken: (InboxLink) -> Unit = {},
    /** Opens a chat about an Inbox report: the run's own, or a new one in its profile with the report attached. */
    onOpenReportChat: (ReportChat) -> Unit = {},
    /** Where the pages show beside the docked sidebar on a desktop; null keeps them in the sidebar. */
    mainPage: MainPageSlot? = null,
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
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val roster by viewModel.roster.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val attentionFilter by viewModel.attentionFilter.collectAsStateWithLifecycle()
    val drafts by viewModel.draftChats.collectAsStateWithLifecycle()
    val settingsStore = koinInject<SettingsStore>()
    val settings = settingsStore.settings.collectAsStateWithLifecycle().value
    val pinnedCollapsed = settings?.pinnedCollapsed ?: false
    val threadsCollapsed = settings?.threadsCollapsed ?: false
    val projectsAsDropdown = settings?.projectsAsDropdown ?: false
    var profilesOpen by remember { mutableStateOf(false) }

    var searchOpen by remember { mutableStateOf(false) }
    var searchFocusAsks by remember { mutableIntStateOf(0) }
    val searchList = remember { ListFocus() }
    var scheduledOpen by remember { mutableStateOf(false) }
    var inboxOpen by remember { mutableStateOf(false) }
    // A tapped Inbox notification, passed on by the app once it opened the drawer: the Inbox, at that run. The
    // page takes the key and opens the run itself.
    var inboxOpenKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(inboxLink) {
        val link = inboxLink ?: return@LaunchedEffect
        inboxOpen = true
        inboxOpenKey = link.key
        onInboxLinkTaken(link)
    }
    // The badge is this gateway's Inbox: the store is bound here too, so it counts before the Inbox is opened.
    val inboxStore = koinInject<InboxStore>()
    val chatHost = koinInject<ChatHost>()
    LaunchedEffect(gateway) { inboxStore.bind(gateway.gatewayUrl.value) }
    val inboxKept = inboxStore.state.collectAsStateWithLifecycle().value
    val inboxUnread = if (inboxStore.bound == gateway.gatewayUrl.value) inboxKept.unread else 0
    var capabilitiesOpen by remember { mutableStateOf(false) }
    var configOpen by remember { mutableStateOf(false) }
    // A desktop reads Hermes's config a little after it starts, so Config opens on it rather than on a wait for the
    // gateway. The page's own view model, shared through this screen's store; opening it then only refreshes.
    if (isDesktop) {
        val config: ConfigViewModel = koinViewModel()
        LaunchedEffect(gateway, profile) {
            delay(CONFIG_PREFETCH_DELAY)
            config.bind(gateway, profile)
        }
    }
    var pluginsOpen by remember { mutableStateOf(false) }
    var insightsOpen by remember { mutableStateOf(false) }
    /** The bot whose routines are showing. */
    var routinesOf by remember { mutableStateOf<Bot?>(null) }
    var actionTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var renameTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionSummary?>(null) }
    // Row swipes: Recent and search pin (right) and archive (left); Archived unarchives (right) and deletes
    // (left), which asks first, as the actions sheet's Delete does.
    val pinWord = stringResource(Res.string.sessions_pin)
    val unpinWord = stringResource(Res.string.sessions_unpin)
    val archiveWord = stringResource(Res.string.sessions_archive)
    val unarchiveWord = stringResource(Res.string.sessions_unarchive)
    val deleteWord = stringResource(Res.string.sessions_delete)
    val renameWord = stringResource(Res.string.sessions_rename)
    val openInWindowWord = stringResource(Res.string.sessions_open_in_new_window)
    val makeThreadWord = stringResource(Res.string.sessions_make_a_thread)
    val pinSwipe = { s: SessionSummary ->
        if (s.pinned) SwipeAction(unpinWord, Lucide.PinOff, SwipeKind.Pin) { viewModel.togglePinned(s) }
        else SwipeAction(pinWord, Lucide.Pin, SwipeKind.Pin) { viewModel.togglePinned(s) }
    }
    val archiveSwipe = { s: SessionSummary ->
        if (s.archived) SwipeAction(unarchiveWord, Lucide.ArchiveRestore, SwipeKind.Archive) { viewModel.toggleArchived(s) }
        else SwipeAction(archiveWord, Lucide.Archive, SwipeKind.Archive) { viewModel.toggleArchived(s) }
    }
    val deleteSwipe = { s: SessionSummary -> SwipeAction(deleteWord, Lucide.Trash2, SwipeKind.Delete) { deleteTarget = s } }
    var projectActions by remember { mutableStateOf<Project?>(null) }
    // The button a desktop menu was opened from, for it to hang from; null for a right-click, which opens it at the pointer.
    var projectAnchor by remember { mutableStateOf<Rect?>(null) }
    var accountAnchor by remember { mutableStateOf<Rect?>(null) }
    // Where the desktop's account row is; a plain holder, read only when it's clicked.
    val accountRow = remember { arrayOfNulls<Rect>(1) }
    var renameProject by remember { mutableStateOf<Project?>(null) }
    var deleteProject by remember { mutableStateOf<Project?>(null) }
    var newProject by remember { mutableStateOf<ProjectDraft?>(null) }
    var creatingProject by remember { mutableStateOf(false) }
    var createProjectError by remember { mutableStateOf<Said?>(null) }
    var accountOpen by remember { mutableStateOf(false) }
    var newRoomOpen by remember { mutableStateOf(false) }
    val threads by viewModel.threads.collectAsStateWithLifecycle()
    var threadActions by remember { mutableStateOf<ThreadRow?>(null) }
    var renameThread by remember { mutableStateOf<ChatThread?>(null) }
    var freshThread by remember { mutableStateOf<ChatThread?>(null) }
    var reorderingThreads by remember { mutableStateOf(false) }

    fun closeSearch() {
        searchOpen = false
        viewModel.query.clearText()
    }

    // The pages open beside a docked sidebar on the desktop, where it stays the list: any one opened puts the last away.
    val pageInMain = mainPage?.enabled == true
    fun closePages() {
        scheduledOpen = false
        inboxOpen = false
        if (routinesOf != null) {
            routinesOf = null
            // What was fixed there shows on the roster at once.
            bots.refreshRoutines()
        }
        capabilitiesOpen = false
        configOpen = false
        pluginsOpen = false
        insightsOpen = false
    }
    val pageOpen = scheduledOpen || inboxOpen || routinesOf != null || capabilitiesOpen || configOpen || pluginsOpen || insightsOpen

    // Back steps out of search or the Scheduled / Archived pages before it closes the drawer. On the desktop the
    // sidebar stays put and a filter is picked like a tab, so Escape leaves it for the chat (stopping a reply).
    // A page beside a docked sidebar is put away by Escape even with the sidebar folded away.
    val filterBacks = !isDesktop && state.filter != SessionListFilter.Recent
    PlatformBackHandler(enabled = visible && (searchOpen || pageOpen || filterBacks) || pageInMain && pageOpen) {
        when {
            searchOpen && visible -> closeSearch()
            pageOpen -> closePages()
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

    val projectIndex = remember(state.projects) { ProjectIndex(state.projects) }
    val sections = remember(threads, state.listed, state.sessions, state.project, projectIndex) {
        threadSections(threads, state.listed, state.sessions, state.project, projectIndex)
    }
    // The thread rows as the section shows them; moving one goes past its neighbour here, not in the whole list.
    val shownThreads = sections.threads.filter { attentionFilter.keeps(statuses[it.session.id]) }
    val openHere: (SessionSummary) -> Unit = {
        // The viewport decides when the new messages have actually been read.
        val readUpTo = viewModel.readUpTo(it)
        onOpenSession(it, readUpTo)
        if (searchOpen) closeSearch()
    }
    val rowActions: (SessionSummary) -> Unit = { actionTarget = it }
    // A click with keys held, on the desktop: pin, archive, or the chat in a window of its own.
    val windowInfo = LocalWindowInfo.current
    val shortcutBus = LocalShortcuts.current
    // The chat in a window of its own; where no window can open (a phone), it opens as a plain click does.
    val popOut: (SessionSummary) -> Unit = { s ->
        // Opening a window preserves the unread boundary too.
        val readUpTo = viewModel.readUpTo(s)
        val popped = shortcutBus.fire(Shortcut.PopOut(ChatTarget(gateway, s.id, s.displayTitle, profile = profile, readUpTo = readUpTo)))
        if (!popped) openHere(s) else if (s.id == selectedId) onNewChat(null)
    }
    // A chat a window of its own shows comes forward there, so no chat runs in two windows at once.
    val windowed by chatHost.sideChats.collectAsStateWithLifecycle()
    val open: (SessionSummary) -> Unit = { s -> if (isDesktop && s.id in windowed) popOut(s) else openHere(s) }
    val click: (SessionSummary) -> Unit = click@{ s ->
        if (!isDesktop) return@click open(s)
        val keys = windowInfo.keyboardModifiers
        when (rowClick(shift = keys.isShiftPressed, primary = keys.isCtrlPressed || keys.isMetaPressed, alt = keys.isAltPressed)) {
            RowClick.Open -> open(s)
            RowClick.Pin -> viewModel.togglePinned(s)
            RowClick.Archive -> viewModel.toggleArchived(s)
            RowClick.NewWindow -> popOut(s)
        }
    }
    // The desktop's keys for the list: search, and stepping through the chats in the order they're listed.
    val listed = shownThreads.map { it.session } + sections.others.filter { attentionFilter.keeps(statuses[it.id]) }
    val modals = LocalOpenModals.current
    ShortcutHandler { shortcut ->
        when (shortcut) {
            // Left to the app too, which shows the sidebar if it's put away.
            Shortcut.SearchChats -> false.also {
                if (mode != SidebarMode.Chats) bots.setMode(SidebarMode.Chats)
                searchOpen = true
                searchFocusAsks++
            }
            // Not behind a dialog or sheet: the chat it's about would change under it. Taken then, so nothing else steps.
            Shortcut.NextChat, Shortcut.PreviousChat -> modals.any || listed.isNotEmpty().also {
                if (it) open(listed[stepChat(listed.indexOfFirst { s -> s.id == selectedId }, listed.size, forward = shortcut == Shortcut.NextChat)])
            }
            is Shortcut.OpenChatAt -> modals.any || listed.getOrNull(shortcut.index)?.let(open) != null
            is Shortcut.OpenListed -> state.sessions.firstOrNull { it.id == shortcut.id }?.let(open) != null
            // A page from the palette, in place of whatever the sidebar shows. Left to the app too, as search is.
            is Shortcut.Go -> false.also {
                if (searchOpen) closeSearch()
                scheduledOpen = false
                inboxOpen = false
                routinesOf = null
                capabilitiesOpen = false
                configOpen = false
                pluginsOpen = false
                insightsOpen = false
                if (state.filter != SessionListFilter.Recent) viewModel.setFilter(SessionListFilter.Recent)
                when (shortcut.page) {
                    SidebarPage.Chats -> bots.setMode(SidebarMode.Chats)
                    SidebarPage.Bots -> bots.setMode(SidebarMode.Bots)
                    SidebarPage.Inbox -> inboxOpen = true
                    SidebarPage.Scheduled -> scheduledOpen = true
                    SidebarPage.Archived -> {
                        bots.setMode(SidebarMode.Chats)
                        viewModel.setFilter(SessionListFilter.Archived)
                    }
                    SidebarPage.Capabilities -> capabilitiesOpen = true
                    SidebarPage.Config -> configOpen = true
                    SidebarPage.Plugins -> pluginsOpen = true
                    SidebarPage.Insights -> insightsOpen = true
                }
            }
            else -> false
        }
    }
    val openAccount = {
        accountOpen = true
        viewModel.refreshProfiles()
    }
    // A right-click on a chat, on the desktop: its actions in a menu at the pointer, as the long-press sheet has them.
    val rowMenu: ((SessionSummary) -> List<RowMenuItem>)? = if (!isDesktop) null else { s ->
        listOfNotNull(
            RowMenuItem(openInWindowWord, Lucide.AppWindow) { popOut(s) }.takeIf { !s.archived },
            RowMenuItem(if (s.pinned) unpinWord else pinWord, if (s.pinned) Lucide.PinOff else Lucide.Pin) { viewModel.togglePinned(s) }
                .takeIf { !s.archived },
            RowMenuItem(renameWord, Lucide.Pencil, key = Key.F2) { renameTarget = s },
            RowMenuItem(makeThreadWord, Lucide.Hash) { viewModel.makeThread(s) }
                .takeIf { !s.archived && threads.none { t -> t.holds(s) } },
            RowMenuItem(if (s.archived) unarchiveWord else archiveWord, if (s.archived) Lucide.ArchiveRestore else Lucide.Archive) {
                viewModel.toggleArchived(s)
            },
            RowMenuItem(deleteWord, Lucide.Trash2, destructive = true, key = Key.Delete) { deleteTarget = s },
        )
    }

    val pageKey = when {
        inboxOpen -> "inbox"
        scheduledOpen -> "scheduled"
        routinesOf != null -> "routines:${routinesOf?.name}"
        capabilitiesOpen -> "capabilities"
        configOpen -> "config"
        pluginsOpen -> "plugins"
        insightsOpen -> "insights"
        else -> null
    }
    // Beside the sidebar, a page stays live while the sidebar is folded away.
    val pageVisible = visible || pageInMain
    val pageView: @Composable () -> Unit = {
        val bot = routinesOf
        when {
            inboxOpen -> InboxPage(
                gateway = gateway,
                visible = pageVisible,
                onBack = { inboxOpen = false },
                onOpenSettings = onOpenSettings,
                openKey = inboxOpenKey,
                onOpenedKey = { inboxOpenKey = null },
                onOpenChat = onOpenReportChat,
            )
            scheduledOpen -> ScheduledPage(
                gateway = gateway,
                visible = pageVisible,
                selectedId = selectedId,
                onBack = { scheduledOpen = false },
                onOpenRun = open,
                onSessionExpired = onSessionExpired,
                onPopOutRun = popOut.takeIf { isDesktop },
            )
            bot != null -> ScheduledPage(
                gateway = gateway,
                visible = pageVisible,
                selectedId = selectedId,
                onBack = { closePages() },
                // A run is a session of the bot's own profile.
                onOpenRun = { run -> onOpenBotSession(bot, run.id, run.title?.takeIf { it.isNotBlank() } ?: bot.label) },
                onSessionExpired = onSessionExpired,
                owner = RoutineOwner(bot.name, bot.label),
            )
            capabilitiesOpen -> CapabilitiesPage(
                gateway = gateway,
                profile = profile,
                onBack = { capabilitiesOpen = false },
                onSessionExpired = onSessionExpired,
            )
            configOpen -> ConfigPage(
                gateway = gateway,
                profile = profile,
                onBack = { configOpen = false },
                onSessionExpired = onSessionExpired,
                visible = pageVisible,
            )
            pluginsOpen -> PluginsPage(
                gateway = gateway,
                profile = profile,
                onBack = { pluginsOpen = false },
                onSessionExpired = onSessionExpired,
                onOpenPlugin = onOpenPlugin,
            )
            insightsOpen -> InsightsPage(
                gateway = gateway,
                profile = profile,
                onBack = { insightsOpen = false },
                onSessionExpired = onSessionExpired,
            )
        }
    }
    if (pageInMain && mainPage != null) {
        val latest by rememberUpdatedState(pageView)
        DisposableEffect(pageKey) {
            val key = pageKey
            // Scheduled and Config lay a list beside what's open in it, so they take a wider page.
            val wide = key == "scheduled" || key == "config" || key?.startsWith("routines:") == true
            if (key != null) mainPage.show(key, { latest() }, close = { closePages() }, wide = wide)
            onDispose { if (key != null) mainPage.clear(key) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .halo()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
    ) {
        if (pageKey != null && !pageInMain && !searchOpen) pageView() else Column(Modifier.fillMaxSize()) {
            when {
                searchOpen -> SearchHeader(viewModel, focusAsks = searchFocusAsks, list = searchList, onClose = ::closeSearch, onSubmit = {
                    // Only results for the words in the box now: none while a search is on its way or still to start.
                    // An empty box shows the recent chats, so Enter opens the first of them.
                    val typed = viewModel.query.text.toString().trim()
                    val shown = when {
                        typed.isEmpty() -> sections.others
                        state.searching || state.searchedFor != typed -> null
                        else -> state.searchResults
                    }
                    shown?.firstOrNull()?.let(open)
                })
                state.filter != SessionListFilter.Recent -> SubpageHeader(
                    title = stringResource(state.filter.label),
                    onBack = { viewModel.setFilter(SessionListFilter.Recent) },
                )
                else -> MainHeader(
                    gatewayLabel = gateway.label,
                    connection = connection,
                    onAccount = {
                        accountAnchor = null
                        openAccount()
                    },
                    onSettings = onOpenSettings,
                    onSearch = { searchOpen = true }.takeIf { mode == SidebarMode.Chats },
                    profiles = roster?.profiles.orEmpty(),
                    activeProfile = profile ?: roster?.launch,
                    onProfile = { picked -> onSwitchProfile(profileToStore(picked, roster)) },
                    onOpenGateways = onOpenGateways,
                )
            }
            if (!searchOpen && state.filter == SessionListFilter.Recent) {
                val modeNames = sidebarModeNames()
                SegmentedControl(
                    options = SidebarMode.entries,
                    selected = mode,
                    onSelect = bots::setMode,
                    // How many bots need the user, readable from the Chats side too.
                    optionLabel = { modeLabel(it, modeNames, needsYou.size) },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
                RenameBanner(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
                UpdateBanner(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
                // A desktop starts a chat from the top of its sidebar, as desktop chat apps do; the footer
                // keeps the account. A phone keeps the pill under the thumb.
                if (isDesktop) {
                    NavTile(
                        NavLink(Lucide.SquarePen, stringResource(Res.string.sessions_new_chat), keys = "Ctrl+N") { onNewChat(state.project?.path) },
                        // In line with the pages listed under it, which sit in the list's own 8dp margin.
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    )
                }
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
                                    override fun open(bot: Bot) = bots.open(bot) { id, readUpTo -> onOpenBot(bot, id, readUpTo) }
                                    override fun setPinned(bot: Bot, pinned: Boolean) = bots.setPinned(bot, pinned)
                                    override fun setHidden(bot: Bot, hidden: Boolean) = bots.setHidden(bot, hidden)
                                    override fun startFresh(bot: Bot) = bots.startFresh(bot) { id -> onOpenBot(bot, id, null) }
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
                    // On a desktop, the recent chats stay in view until a word is typed, so search (opened from
                    // the keyboard as often as not) never shows a blank list. A phone keeps the field alone.
                    searchOpen && searchResults == null && !isDesktop -> Unit
                    searchOpen && searchResults == null -> SessionList(
                        sections.others, selectedId, click, rowActions,
                        menu = rowMenu,
                        swipeRight = pinSwipe,
                        swipeLeft = archiveSwipe,
                        statuses = statuses,
                        drafts = drafts,
                        focus = searchList,
                    )
                    searchResults != null -> when {
                        state.searching && searchResults.isEmpty() -> CenteredSpinner()
                        searchResults.isEmpty() -> EmptyState(
                            Lucide.SearchX,
                            stringResource(Res.string.sessions_no_matches),
                            stringResource(Res.string.sessions_no_matches_detail),
                        )
                        else -> SessionList(
                            searchResults, selectedId, click, rowActions,
                            menu = rowMenu,
                            swipeRight = pinSwipe,
                            swipeLeft = archiveSwipe,
                            showSnippets = true,
                            statuses = statuses,
                            drafts = drafts,
                            focus = searchList,
                        )
                    }
                    state.filter == SessionListFilter.Recent -> SessionList(
                        sessions = sections.others.filter { attentionFilter.keeps(statuses[it.id]) },
                        selectedId = selectedId,
                        onOpen = click,
                        onActions = rowActions,
                        menu = rowMenu,
                        swipeRight = pinSwipe,
                        swipeLeft = archiveSwipe,
                        threads = shownThreads,
                        onOpenThread = { click(it.session) },
                        onThreadActions = { threadActions = it },
                        reorderingThreads = reorderingThreads && attentionFilter == AttentionFilter.All,
                        onReordered = { ids ->
                            reorderingThreads = false
                            if (ids != shownThreads.map { it.thread.id }) viewModel.reorderThreads(ids)
                        },
                        // A filtered list stays short, so the end is always in sight: paging on would
                        // fetch the whole history. What's running or waiting is recent anyway. A project's
                        // chats come whole.
                        canLoadMore = state.canLoadMore && attentionFilter == AttentionFilter.All && state.project == null,
                        loadingMore = state.loadingMore,
                        onLoadMore = viewModel::loadMore,
                        statuses = statuses,
                        drafts = drafts,
                        sectioned = true,
                        projectIndex = projectIndex,
                        shownProject = state.project,
                        pinnedCollapsed = pinnedCollapsed,
                        onTogglePinned = { settingsStore.update { it.copy(pinnedCollapsed = !it.pinnedCollapsed) } },
                        threadsCollapsed = threadsCollapsed,
                        onToggleThreads = { settingsStore.update { it.copy(threadsCollapsed = !it.threadsCollapsed) } },
                        status = {
                            if (!state.loading && state.error == null && (state.projects.isNotEmpty() || state.canMakeProjects)) {
                                item(key = "projects") {
                                    ProjectFilters(
                                        state.projects,
                                        state.project,
                                        asDropdown = projectsAsDropdown,
                                        onSelect = viewModel::selectProject,
                                        onNew = if (state.canMakeProjects) ({ newProject = ProjectDraft() }) else null,
                                        onOptions = { project, button ->
                                            projectAnchor = button
                                            projectActions = project
                                        },
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
                            state.savedCopyAt?.takeIf { state.project == null }?.let { savedAt ->
                                item(key = "saved-copy") {
                                    ListNotice(
                                        stringResource(Res.string.sessions_saved_copy_unreachable, savedCopyText(savedAt)),
                                        action = stringResource(Res.string.sessions_try_again),
                                        onAction = viewModel::refresh,
                                    )
                                }
                            }
                            when {
                                // Stand-in rows rather than a spinner: switching profile keeps the sidebar open, and
                                // the list then fills in where it will be.
                                state.loading -> item(key = "loading") { ListSkeleton() }
                                state.error != null -> item(key = "error") {
                                    ListNotice(
                                        stringResource(Res.string.sessions_load_failed_detail, state.error.orEmpty()),
                                        action = stringResource(Res.string.sessions_try_again),
                                        onAction = viewModel::refresh,
                                    )
                                }
                                state.project != null && state.projectSessions == null -> item(key = "project-loading") { ListSkeleton() }
                                state.listed.isEmpty() -> item(key = "empty") {
                                    ListNotice(
                                        stringResource(if (state.project != null) Res.string.sessions_empty_project else Res.string.sessions_empty),
                                    )
                                }
                                attentionFilter == AttentionFilter.Running && state.listed.none { statuses[it.id]?.running == true } -> item(key = "none-running") {
                                    ListNotice(stringResource(Res.string.sessions_none_running))
                                }
                                attentionFilter == AttentionFilter.NeedsAttention && state.listed.none { statuses[it.id]?.needsAttention == true } ->
                                    item(key = "none-waiting") { ListNotice(stringResource(Res.string.sessions_none_waiting)) }
                            }
                        },
                    ) {
                        item(key = "nav") {
                            NavGrid(
                                // The Inbox goes by a phone's push registration (Notifications anywhere), which
                                // a desktop has none of, so it would only ever say it can't load.
                                listOfNotNull(
                                    NavLink(Lucide.Inbox, stringResource(Res.string.sessions_nav_inbox), badge = inboxUnread) { inboxOpen = true }.takeIf { !isDesktop },
                                    NavLink(Lucide.CalendarClock, stringResource(Res.string.sessions_nav_scheduled), selected = pageInMain && scheduledOpen) { closePages(); scheduledOpen = true },
                                    NavLink(Lucide.ChartColumn, stringResource(Res.string.sessions_nav_insights), selected = pageInMain && insightsOpen) { closePages(); insightsOpen = true },
                                    NavLink(Lucide.Blocks, stringResource(Res.string.sessions_nav_capabilities), selected = pageInMain && capabilitiesOpen) { closePages(); capabilitiesOpen = true },
                                    NavLink(Lucide.Puzzle, stringResource(Res.string.sessions_nav_plugins), selected = pageInMain && pluginsOpen) { closePages(); pluginsOpen = true },
                                    NavLink(Lucide.SlidersHorizontal, stringResource(Res.string.sessions_nav_config), selected = pageInMain && configOpen) { closePages(); configOpen = true },
                                    NavLink(Lucide.Archive, stringResource(Res.string.sessions_nav_archived), selected = isDesktop && state.filter == SessionListFilter.Archived) {
                                        viewModel.setFilter(SessionListFilter.Archived)
                                    },
                                ),
                            )
                        }
                    }
                    state.loading -> ListSkeleton()
                    state.error != null -> EmptyState(Lucide.CloudOff, stringResource(Res.string.sessions_load_failed), state.error, error = true) {
                        Button(stringResource(Res.string.sessions_try_again), onClick = viewModel::refresh, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
                    }
                    state.sessions.isEmpty() -> EmptyState(
                        Lucide.Archive,
                        stringResource(Res.string.sessions_nothing_archived),
                        stringResource(Res.string.sessions_nothing_archived_detail),
                    )
                    else -> SessionList(
                        sessions = state.sessions,
                        selectedId = selectedId,
                        onOpen = open,
                        onActions = rowActions,
                        menu = rowMenu,
                        swipeRight = archiveSwipe,
                        swipeLeft = deleteSwipe,
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
                accountAnchor = accountRow[0]
                openAccount()
            },
            onAccountBounds = { accountRow[0] = it },
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
        onMakeThread = viewModel::makeThread.takeIf {
            actionTarget?.let { s -> !s.archived && threads.none { t -> t.holds(s) } } == true
        },
    )
    ThreadActionsSheet(
        row = threadActions,
        shown = shownThreads.map { it.thread },
        onDismiss = { threadActions = null },
        onStartFresh = { freshThread = it.thread },
        onRename = { renameThread = it.thread },
        onMove = viewModel::moveThread,
        onReorder = {
            viewModel.setAttentionFilter(AttentionFilter.All)
            reorderingThreads = true
        },
        onRemove = { viewModel.removeThread(it.thread) },
        onNewWindow = { row: ThreadRow -> popOut(row.session) }.takeIf { isDesktop },
    )
    RenameThreadDialog(renameThread, onDismiss = { renameThread = null }, onRename = viewModel::renameThread)
    StartThreadFreshDialog(freshThread, onDismiss = { freshThread = null }) { thread ->
        viewModel.startFresh(thread) { fresh -> onOpenSession(SessionSummary(fresh.sessionId, title = fresh.title, pinned = true), null) }
    }
    RenameDialog(renameTarget, onDismiss = { renameTarget = null }, onRename = { session, title ->
        // On the desktop the chat itself shows the new name too, open here or in a window of its own, once the
        // gateway has it: a refused rename goes back in the list, and so stays the old name there.
        viewModel.rename(session, title) {
            if (isDesktop) chatHost.retitled(session.id, title.trim().ifBlank { null })
        }
    })
    ProjectActionsSheet(
        project = projectActions,
        anchor = projectAnchor,
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
        makeFolder = viewModel::makeProjectFolder,
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
        anchor = accountAnchor.takeIf { isDesktop },
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
            onSwitchProfile(profileToStore(picked, roster))
        },
    )
}

/** The launch profile is stored as "none picked", so calls stay exactly as before profiles. */
internal fun profileToStore(picked: Profile, roster: ProfileRoster?): String? = picked.name.takeIf { it != roster?.launch }

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
    /** The projects as one button that opens a list, not chips. */
    projectsAsDropdown: Boolean = false,
    /** The Threads section's threads, whose chats are among [sessions]. */
    threads: List<ChatThread> = emptyList(),
    /** The gateway's profiles, the first in use; with more than one the header names it. */
    profiles: List<Profile> = emptyList(),
    /** The header's profile menu drawn open. */
    profileMenuOpen: Boolean = false,
) {
    // Folds for real in the gallery, kept only while the preview shows.
    var pinnedCollapsed by remember { mutableStateOf(false) }
    var threadsCollapsed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .halo()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
    ) {
        Column(Modifier.fillMaxSize()) {
            MainHeader(
                gatewayLabel = "homelab",
                connection = null,
                onAccount = {},
                onSettings = {},
                onSearch = {},
                profiles = profiles,
                activeProfile = profiles.firstOrNull()?.name,
                menuInitiallyOpen = profileMenuOpen,
            )
            RenameBanner(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val index = remember(projects) { ProjectIndex(projects) }
                val sections = threadSections(threads, sessions, sessions, selectedProject, index)
                SessionList(
                    sections.others,
                    selectedId,
                    onOpen = {},
                    onActions = {},
                    statuses = statuses,
                    drafts = drafts,
                    sectioned = true,
                    projectIndex = index,
                    shownProject = selectedProject,
                    threads = sections.threads,
                    pinnedCollapsed = pinnedCollapsed,
                    onTogglePinned = { pinnedCollapsed = !pinnedCollapsed },
                    threadsCollapsed = threadsCollapsed,
                    onToggleThreads = { threadsCollapsed = !threadsCollapsed },
                    status = {
                        if (projects.isNotEmpty() || canMakeProjects) {
                            item(key = "projects") {
                                ProjectFilters(projects, selected = selectedProject, asDropdown = projectsAsDropdown, onSelect = {}, onNew = if (canMakeProjects) ({}) else null)
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
                                NavLink(Lucide.Inbox, stringResource(Res.string.sessions_nav_inbox), badge = 2) {},
                                NavLink(Lucide.CalendarClock, stringResource(Res.string.sessions_nav_scheduled)) {},
                                NavLink(Lucide.ChartColumn, stringResource(Res.string.sessions_nav_insights)) {},
                                NavLink(Lucide.Blocks, stringResource(Res.string.sessions_nav_capabilities)) {},
                                NavLink(Lucide.Puzzle, stringResource(Res.string.sessions_nav_plugins)) {},
                                NavLink(Lucide.Archive, stringResource(Res.string.sessions_nav_archived)) {},
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

/** The sidebar's Bots side drawn from [state] alone, for previews: the header, the switch and the roster. */
@Composable
internal fun BotsSidebarSample(
    state: BotsUiState,
    userLabel: String,
    nowSeconds: Double,
    needsYou: List<NeedsYou> = emptyList(),
    rooms: List<Room> = emptyList(),
) {
    Box(
        Modifier
            .fillMaxSize()
            .halo()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
    ) {
        Column(Modifier.fillMaxSize()) {
            MainHeader(gatewayLabel = "homelab", connection = null, onAccount = {}, onSettings = {}, onSearch = null)
            val modeNames = sidebarModeNames()
            SegmentedControl(
                options = SidebarMode.entries,
                selected = SidebarMode.Bots,
                onSelect = {},
                optionLabel = { modeLabel(it, modeNames, needsYou.size) },
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                BotsRoster(
                    state = state,
                    avatars = emptyMap(),
                    selectedId = null,
                    selectedRunning = false,
                    nowSeconds = nowSeconds,
                    actions = PreviewBotActions,
                    onRetry = {},
                    onDismissNotice = {},
                    needsYou = needsYou,
                    rooms = rooms,
                    roomsAvailable = rooms.isNotEmpty(),
                )
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

private object PreviewBotActions : BotActions {
    override fun open(bot: Bot) = Unit
    override fun setPinned(bot: Bot, pinned: Boolean) = Unit
    override fun setHidden(bot: Bot, hidden: Boolean) = Unit
    override fun startFresh(bot: Bot) = Unit
    override fun openRecent(bot: Bot) = Unit
    override fun newChat(bot: Bot) = Unit
    override fun create() = Unit
    override fun edit(bot: Bot) = Unit
    override fun duplicate(bot: Bot) = Unit
    override fun delete(bot: Bot) = Unit
    override fun checkAgain(bot: Bot) = Unit
    override fun routines(bot: Bot) = Unit
}

private val SessionListFilter.label: StringResource
    get() = when (this) {
        SessionListFilter.Recent -> Res.string.sessions_mode_chats
        SessionListFilter.Archived -> Res.string.sessions_nav_archived
    }

/** Each side of the Chats / Bots switch by name, read once in composition for the switch's plain label lambda. */
@Composable
private fun sidebarModeNames(): Map<SidebarMode, String> = SidebarMode.entries.associateWith {
    stringResource(
        when (it) {
            SidebarMode.Chats -> Res.string.sessions_mode_chats
            SidebarMode.Bots -> Res.string.sessions_mode_bots
        },
    )
}

/** A side of the switch, Bots with how many bots need the user. */
private fun modeLabel(mode: SidebarMode, names: Map<SidebarMode, String>, needsYou: Int): String {
    val name = names[mode] ?: mode.name
    return if (mode == SidebarMode.Bots && needsYou > 0) "$name · $needsYou" else name
}

/**
 * The app's mark and name over the gateway in use, its dot showing the connection, and Settings; then the
 * search pill. With more than one profile, the name and gateway open a menu of them; otherwise the account sheet.
 */
@Composable
private fun MainHeader(
    gatewayLabel: String,
    /** Null in previews, which have no connection to report. */
    connection: ConnectionState?,
    onAccount: () -> Unit,
    onSettings: () -> Unit,
    onSearch: (() -> Unit)?,
    /** The gateway's profiles; with more than one, the header names the active one and opens them in a menu. */
    profiles: List<Profile> = emptyList(),
    /** The active profile's name, the launch profile's included. */
    activeProfile: String? = null,
    onProfile: (Profile) -> Unit = {},
    onOpenGateways: () -> Unit = {},
    /** For previews: the profile menu drawn open. */
    menuInitiallyOpen: Boolean = false,
) {
    val accountLabel = stringResource(Res.string.sessions_account_and_gateways)
    val connectionText = stringResource(connectionWord(connection))
    val switching = profiles.size > 1
    val active = profiles.firstOrNull { it.name == activeProfile }
    var menuOpen by remember { mutableStateOf(menuInitiallyOpen) }
    Column {
        Row(
            Modifier.fillMaxWidth().clearOfWindowControls().padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isDesktop) 4.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppMark(30.dp, glow = false)
            // On the desktop this strip is the window's title bar: only the name and gateway are a button, as wide
            // as they are, and the rest of the strip drags the window (a double-click maximises it). The Box takes
            // what Settings leaves, so a long gateway name can't push Settings off the strip.
            Box(Modifier.weight(1f)) {
                // With profiles to switch between, they come first in a menu here, one tap from the list; the account
                // sheet is the menu's last row. With one, a tap opens the account sheet as before.
                DropdownMenu(
                    expanded = menuOpen,
                    onExpandedChange = { menuOpen = it },
                    alignment = AnchorAlignment.Start,
                    maxHeight = PROJECT_MENU_MAX_HEIGHT,
                    items = {
                        profiles.forEach { profile ->
                            val picked = profile.name == activeProfile
                            MenuChoice(profile.label, Lucide.CircleUser, selected = picked, onClick = { if (!picked) onProfile(profile) })
                        }
                        MenuSeparator()
                        MenuAction(stringResource(Res.string.sessions_switch_gateway), Lucide.ArrowLeftRight, onClick = onOpenGateways)
                        MenuAction(accountLabel, Lucide.CircleUserRound, onClick = onAccount)
                    },
                ) {
                Column(
                    Modifier
                        .then(if (isDesktop) Modifier.widthIn(max = 200.dp) else Modifier.fillMaxWidth())
                        .heightIn(min = MinTouchTarget)
                        .clip(RoundedCornerShape(Theme[radii][radiusSmall]))
                        .handCursor()
                        .clickable(
                            onClickLabel = if (switching) stringResource(Res.string.sessions_switch_profile) else accountLabel,
                            role = if (switching) Role.DropdownList else Role.Button,
                            interactionSource = null,
                            indication = if (isDesktop) rememberHoverIndication(Theme[colors][text]) else LocalIndication.current,
                            onClick = { if (switching) menuOpen = !menuOpen else onAccount() },
                        )
                        .then(if (isDesktop) Modifier.padding(horizontal = 6.dp) else Modifier)
                        .semantics(mergeDescendants = true) { stateDescription = connectionText },
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "Kyllene",
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
                        // The profile keeps its room; a long gateway name is cut short first.
                        if (switching && active != null) {
                            Text(
                                "· ${active.label}",
                                style = Theme[typography][caption].copy(fontSize = 12.sp),
                                color = Theme[colors][textSecondary],
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        UnstyledIcon(
                            if (menuOpen) Lucide.ChevronUp else Lucide.ChevronDown,
                            contentDescription = null,
                            tint = Theme[colors][textTertiary],
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
                }
            }
            val settingsWord = stringResource(Res.string.common_settings)
            IconButton(
                Lucide.Settings,
                contentDescription = if (isDesktop) "$settingsWord (${keysLabel("Ctrl+,")})" else settingsWord,
                onClick = onSettings,
                tint = Theme[colors][textSecondary],
            )
        }
        // Search looks through chats; the bot roster is short enough to read. The header keeps its height
        // without the pill, so switching sides doesn't move the switch under the finger.
        if (onSearch != null) SearchPill(onSearch) else Spacer(Modifier.height(maxOf(MinTouchTarget, SEARCH_PILL_HEIGHT)))
    }
}

@Composable
private fun connectionDot(connection: ConnectionState?): Color = when (connection) {
    null, is ConnectionState.Connected -> Theme[colors][success]
    is ConnectionState.Failed, is ConnectionState.SessionExpired -> Theme[colors][danger]
    else -> Theme[colors][warning]
}

/** The dot's colour in words, for screen readers. */
internal fun connectionWord(connection: ConnectionState?): StringResource = when (connection) {
    null, is ConnectionState.Connected -> Res.string.sessions_connection_connected
    is ConnectionState.Failed -> Res.string.sessions_connection_not_connected
    is ConnectionState.SessionExpired -> Res.string.sessions_connection_signed_out
    is ConnectionState.Reconnecting -> Res.string.sessions_connection_reconnecting
    is ConnectionState.Connecting, ConnectionState.Idle -> Res.string.sessions_connection_connecting
}

/** How tall the search pill is drawn; the touch target around it can be taller. */
private val SEARCH_PILL_HEIGHT = 40.dp

/** A pill that opens search, in a full-height touch target. */
@Composable
private fun SearchPill(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = CircleShape
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .padding(horizontal = 14.dp)
            .handCursor()
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = SEARCH_PILL_HEIGHT)
                .clip(shape)
                .background(Theme[colors][surface2], shape)
                .border(1.dp, Theme[colors][stroke], shape)
                .indication(interaction, rememberHoverIndication(Theme[colors][text]))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(Lucide.Search, contentDescription = null, tint = Theme[colors][textMuted], modifier = Modifier.size(15.dp))
            Text(stringResource(Res.string.sessions_search_chats), style = Theme[typography][body].copy(fontSize = 14.sp), color = Theme[colors][textMuted], maxLines = 1, modifier = Modifier.weight(1f))
            // Its keys, as New chat names Ctrl+N.
            if (isDesktop) Text(keysLabel("Ctrl+Shift+F"), style = Theme[typography][caption], color = Theme[colors][textMuted], maxLines = 1)
        }
    }
}

/** A page's header: Back with the page's [actions] at the other end, then its title, large, underneath. */
@Composable
internal fun SubpageHeader(title: String, onBack: () -> Unit, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // A narrow page, such as one in the sidebar, takes the smaller title style.
        val style = Theme[typography][if (maxWidth < 360.dp) titleStyle else display]
        // A desktop page beside the sidebar heads itself as a desktop window's toolbar does: Back, the title and
        // the actions on one line, so the page starts higher up.
        if (isDesktop && maxWidth >= 360.dp) {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth().clearOfWindowControls().padding(start = 4.dp, end = 8.dp, top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        Lucide.ArrowLeft,
                        contentDescription = stringResource(Res.string.common_back),
                        onClick = onBack,
                        tint = Theme[colors][textSecondary],
                    )
                    Text(
                        title,
                        style = Theme[typography][titleStyle],
                        color = Theme[colors][text],
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // A long title (a job's name) is cut short rather than the buttons after it.
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = Theme[typography][bodySmall].copy(fontSize = 13.5.sp),
                        color = Theme[colors][textTertiary],
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                    )
                }
            }
            return@BoxWithConstraints
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Row(
                Modifier.fillMaxWidth().clearOfWindowControls().padding(start = 4.dp, end = 8.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    Lucide.ArrowLeft,
                    contentDescription = stringResource(Res.string.common_back),
                    onClick = onBack,
                    tint = Theme[colors][textSecondary],
                )
                // The actions get all the room past Back, pushed to the end; a weighted one can take it all.
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
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
                    // Not cut short: it can carry a note the page relies on, like when changes apply.
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchHeader(viewModel: SessionsViewModel, focusAsks: Int, list: ListFocus, onClose: () -> Unit, onSubmit: () -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Each time it's asked for again (the search key, with the search already open) the field takes the focus back.
    LaunchedEffect(focusAsks) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            state = viewModel.query,
            placeholder = stringResource(Res.string.sessions_search_chats),
            leadingIcon = Lucide.Search,
            clearable = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // On a desktop Enter opens the top match and Down steps from the field into the list. A phone's Search
            // key only puts the keyboard away, so the results can be looked through first.
            onKeyboardAction = if (isDesktop) KeyboardActionHandler { onSubmit() } else null,
            focusRequester = focus,
            modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                (isDesktop && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown).also { down ->
                    if (down && !list.focusFirst()) focusManager.moveFocus(FocusDirection.Down)
                }
            },
        )
        IconButton(Lucide.X, contentDescription = stringResource(Res.string.sessions_close_search), onClick = onClose, tint = Theme[colors][text])
    }
}

/** Lets the search field hand the focus to the first chat the list under it shows. */
private class ListFocus {
    var first: (() -> Boolean)? = null

    fun focusFirst(): Boolean = first?.invoke() ?: false
}

/** How tall a page's row in the sidebar is under a mouse. */
private val DESKTOP_NAV_ROW = 32.dp

/** One of the sidebar's pages, as a tile in [NavGrid]. */
internal class NavLink(
    val icon: ImageVector,
    val label: String,
    val badge: Int = 0,
    val selected: Boolean = false,
    /** Its keys, shown at the row's end on the desktop as a menu shows them. */
    val keys: String? = null,
    val onClick: () -> Unit,
)

/** The sidebar's pages as tiles, two to a row; on the desktop one under another, as a desktop sidebar lists its places. */
@Composable
private fun NavGrid(links: List<NavLink>) {
    if (isDesktop) {
        Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 10.dp)) {
            links.forEach { NavTile(it, Modifier.fillMaxWidth()) }
        }
        return
    }
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

/**
 * A 44dp tile, an accent icon and its label, inside a full-height touch target. On the desktop a flat row, as a
 * desktop sidebar lists its places: filled only under the mouse.
 */
@Composable
private fun NavTile(link: NavLink, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Theme[radii][if (isDesktop) radiusSmall else radiusMedium])
    Box(
        modifier
            .heightIn(min = if (isDesktop) DESKTOP_NAV_ROW else MinTouchTarget)
            .handCursor()
            .clickable(interaction, indication = null, role = Role.Button, onClick = link.onClick)
            .semantics { selected = link.selected },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                // A phone's tile fills its grid cell; a desktop row is as tall as its own line, outside a grid too.
                .then(if (isDesktop) Modifier.fillMaxWidth().heightIn(min = DESKTOP_NAV_ROW) else Modifier.fillMaxSize())
                .padding(vertical = if (isDesktop) 1.dp else 2.dp)
                .clip(shape)
                // On the desktop the page that's open beside the sidebar stays marked, as a chat row does.
                .background(if (isDesktop && !link.selected) Color.Transparent else Theme[colors][surface2], shape)
                .indication(interaction, rememberHoverIndication(Theme[colors][text]))
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
                modifier = Modifier.weight(1f, fill = isDesktop && link.keys != null),
            )
            if (isDesktop && link.keys != null) {
                Text(keysLabel(link.keys), style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1)
            }
            if (link.badge > 0) {
                val unread = pluralStringResource(Res.plurals.sessions_unread_count, link.badge, link.badge)
                Text(
                    if (link.badge > 99) "99+" else link.badge.toString(),
                    style = Theme[typography][code].copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = Theme[colors][onAccent],
                    modifier = Modifier
                        .background(Theme[colors][accent], CircleShape)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                        .semantics { contentDescription = unread },
                )
            }
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
    message: Said?,
    onDismissMessage: () -> Unit,
    onNewChat: () -> Unit,
    onAccount: () -> Unit,
    modifier: Modifier = Modifier,
    /** Where the desktop's account row is, for its menu to open over it. */
    onAccountBounds: (Rect) -> Unit = {},
) {
    val ground = Theme[colors][sidebarColor]
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(16.dp).background(Brush.verticalGradient(listOf(ground.copy(alpha = 0f), ground))))
        // Opaque under the banner too, so no row shows around it.
        Column(Modifier.fillMaxWidth().background(ground)) {
            if (message != null) MessageBanner(message.text(), onDismiss = onDismissMessage, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            if (isDesktop) {
                AccountRow(userLabel, connection, onClick = onAccount, modifier = Modifier.onGloballyPositioned { onAccountBounds(it.boundsInWindow()) })
                return@Column
            }
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
            .handCursor()
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (isDesktop) MinTouchTarget else 46.dp)
                .glow(shape, Shadow(radius = 14.dp, color = fill.copy(alpha = 0.4f), offset = DpOffset(0.dp, 4.dp)))
                .clip(shape)
                .background(fill, shape)
                .indication(interaction, rememberHoverIndication(Theme[colors][onAccent]))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(Lucide.SquarePen, contentDescription = null, tint = Theme[colors][onAccent], modifier = Modifier.size(16.dp))
            Text(
                stringResource(Res.string.sessions_new_chat),
                style = Theme[typography][body].copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                color = Theme[colors][onAccent],
                maxLines = 1,
            )
            // The desktop shows its shortcut, as a desktop menu does.
            if (isDesktop) {
                Text(
                    keysLabel("Ctrl+N"),
                    style = Theme[typography][caption],
                    color = Theme[colors][onAccent].copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The user's initials on a 46dp disc, opening the account sheet, with a dot when the connection isn't healthy:
 * the header that also shows it is gone in search and on subpages.
 */
@Composable
private fun Avatar(userLabel: String?, connection: ConnectionState?, onClick: () -> Unit) {
    val accountWord = stringResource(Res.string.sessions_account)
    val connectionText = stringResource(connectionWord(connection))
    val down = connection != null && connection !is ConnectionState.Connected
    AvatarButton(
        // Who's signed in, and the link when it's down, named on hover as the icon buttons beside it are.
        listOfNotNull(userLabel?.takeIf { it.isNotBlank() } ?: accountWord, connectionText.takeIf { down }).joinToString(" · "),
        userLabel,
        connection,
        accountWord,
        connectionText.takeIf { down },
        onClick,
    )
}

@Composable
private fun AvatarButton(
    tooltip: String,
    userLabel: String?,
    connection: ConnectionState?,
    accountWord: String,
    connectionText: String?,
    onClick: () -> Unit,
) = Tooltip(tooltip) {
    val initials = userLabel?.initials()
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(MinTouchTarget)
            .handCursor()
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = accountWord, onClick = onClick)
            .semantics {
                contentDescription = accountWord
                if (connectionText != null) stateDescription = connectionText
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                // As big as the New chat pill beside it.
                .size(if (isDesktop) MinTouchTarget else 46.dp)
                .clip(CircleShape)
                .background(Theme[colors][surface3])
                .indication(interaction, rememberHoverIndication(Theme[colors][text]))
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

/**
 * The desktop's footer: who's signed in, their initials and the link's state, one row that opens the account
 * menu, as desktop apps keep the account at the sidebar's foot.
 */
@Composable
private fun AccountRow(userLabel: String?, connection: ConnectionState?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Theme[radii][radiusSmall])
    val accountWord = stringResource(Res.string.sessions_account)
    val name = userLabel?.takeIf { it.isNotBlank() } ?: accountWord
    val connectionText = stringResource(connectionWord(connection))
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp)
            .then(modifier)
            .clip(shape)
            .handCursor()
            .clickable(interaction, indication = rememberHoverIndication(Theme[colors][text]), role = Role.Button, onClickLabel = accountWord, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { stateDescription = connectionText },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).background(Theme[colors][surface3], CircleShape), contentAlignment = Alignment.Center) {
            val initials = userLabel?.initials()
            if (initials.isNullOrEmpty()) {
                UnstyledIcon(Lucide.CircleUser, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(16.dp))
            } else {
                Text(initials, style = Theme[typography][label].copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold), color = Theme[colors][textSecondary])
            }
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = Theme[typography][label].copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium), color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(connectionDot(connection), CircleShape))
                Text(connectionText, style = Theme[typography][caption].copy(fontSize = 11.5.sp), color = Theme[colors][textTertiary], maxLines = 1)
            }
        }
        UnstyledIcon(Lucide.ChevronsUpDown, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
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
    /** A chat's actions for its right-click menu, on the desktop; null, rows have none. */
    menu: ((SessionSummary) -> List<RowMenuItem>)? = null,
    /** What a right swipe on a chat's row does; null, the row doesn't swipe right. */
    swipeRight: (SessionSummary) -> SwipeAction? = { null },
    /** What a left swipe on a chat's row does; null, the row doesn't swipe left. */
    swipeLeft: (SessionSummary) -> SwipeAction? = { null },
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
    /** Which project each chat is in, named on pinned rows. */
    projectIndex: ProjectIndex = ProjectIndex.Empty,
    /** The project the list is narrowed to, whose name the rows leave out. */
    shownProject: Project? = null,
    /** The Threads section, above Pinned; [sessions] no longer holds their chats. */
    threads: List<ThreadRow> = emptyList(),
    onOpenThread: (ThreadRow) -> Unit = {},
    onThreadActions: (ThreadRow) -> Unit = {},
    /** The Threads section shows drag handles; [onReordered] gets the order once the user is done. */
    reorderingThreads: Boolean = false,
    onReordered: (List<String>) -> Unit = {},
    /** The Pinned section is folded to the chats [visiblePinned] keeps. */
    pinnedCollapsed: Boolean = false,
    /** Folds or unfolds the Pinned section; null, its label is plain text. */
    onTogglePinned: (() -> Unit)? = null,
    /** The Threads section is folded to the threads [visibleThreads] keeps. */
    threadsCollapsed: Boolean = false,
    /** Folds or unfolds the Threads section; null, its label is plain text. */
    onToggleThreads: (() -> Unit)? = null,
    /** Filters and the loading, error or empty notices, shown above the unpinned chats. */
    /** Given a way to focus the first chat listed, for a field above the list. */
    focus: ListFocus? = null,
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

    // A desktop's type-ahead: letters typed while a chat has the focus move it to the next chat whose title starts so.
    val positions = remember { HashMap<Any, Int>() }
    val requesters = remember { HashMap<String, FocusRequester>() }
    val typeAhead = remember { TypeAhead() }
    val scope = rememberCoroutineScope()
    val inputMode = LocalInputModeManager.current
    // Brings a chat's row into view and gives it the focus, ringed as keys moved it there.
    val focusRow: (String, Int) -> Unit = { id, above ->
        scope.launch {
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == id }) {
                positions[id]?.let { listState.scrollToItem((it - above).coerceAtLeast(0)) }
                withFrameNanos { }
            }
            requesters[id]?.let { row ->
                inputMode.requestInputMode(InputMode.Keyboard)
                runCatching { row.requestFocus() }
            }
        }
    }
    val jump: (KeyEvent) -> Boolean = jump@{ event ->
        // The character a letter types comes after its key-down; the list keeps it, or the window's typing would
        // take it on to the composer.
        if (event.type == KeyEventType.Unknown) return@jump TypeAhead.isLetter(event)
        // The chats as listed: one in a folded section isn't there to go to.
        val listed = sessions.filter { it.id in positions }.sortedBy { positions[it.id] }
        val target = typeAhead.next(event, listed) ?: return@jump false
        focusRow(target.id, 3)
        true
    }
    if (focus != null) {
        val listed by rememberUpdatedState(sessions)
        DisposableEffect(focus) {
            val first = first@{
                val top = listed.filter { it.id in positions }.minByOrNull { positions.getValue(it.id) } ?: return@first false
                focusRow(top.id, 0)
                true
            }
            focus.first = first
            onDispose { if (focus.first === first) focus.first = null }
        }
    }

    val rows: LazyListScope.() -> Unit = rows@{
        header()
        // Pinned chats stay on top, marked with a pin.
        val (pinned, rest) = sessions.partition { it.pinned }
        val row: @Composable (SessionSummary) -> Unit = { session ->
            val focus = remember(session.id) { FocusRequester() }
            DisposableEffect(session.id) {
                requesters[session.id] = focus
                onDispose { if (requesters[session.id] === focus) requesters.remove(session.id) }
            }
            SwipeRow(
                start = swipeRight(session),
                end = swipeLeft(session),
                shape = RoundedCornerShape(Theme[radii][radiusMedium]),
            ) { swipeActions ->
                SessionRow(
                    session,
                    selected = session.id == selectedId,
                    showSnippet = showSnippets,
                    status = statuses[session.id],
                    draft = session.id in drafts,
                    // Pinned chats sit apart from their project's others, so their row names it.
                    projectLabel = if (sectioned && session.pinned) projectIndex.labelFor(session, shownProject) else null,
                    onClick = { onOpen(session) },
                    onActions = { onActions(session) },
                    menu = menu?.let { items -> { items(session) } },
                    modifier = swipeActions.focusRequester(focus),
                )
            }
        }
        if (sectioned) {
            if (threads.isNotEmpty()) {
                if (reorderingThreads) {
                    // One item, so a dragged row moves among the others without the list recycling it.
                    item(key = "threads-reorder") {
                        ThreadReorder(threads, { projectIndex.labelFor(it.session, shownProject) }, onDone = onReordered)
                    }
                } else {
                    item(key = "label-threads", contentType = SectionLabelType) {
                        if (onToggleThreads != null) {
                            SectionToggle(
                                stringResource(Res.string.sessions_section_threads),
                                threads.size,
                                threadsCollapsed,
                                stringResource(Res.string.sessions_show_threads),
                                stringResource(Res.string.sessions_hide_threads),
                                onToggleThreads,
                            )
                        } else {
                            ListLabel(stringResource(Res.string.sessions_section_threads))
                        }
                    }
                    items(
                        visibleThreads(threads, threadsCollapsed, selectedId, statuses, drafts),
                        key = { "thread-${it.thread.id}" },
                        contentType = { SessionRowType },
                    ) { row ->
                        SessionRow(
                            row.session.copy(title = row.thread.name),
                            selected = row.session.id == selectedId || row.thread.sessionId == selectedId,
                            showSnippet = false,
                            status = statuses[row.session.id],
                            draft = row.session.id in drafts,
                            projectLabel = projectIndex.labelFor(row.session, shownProject),
                            thread = true,
                            onClick = { onOpenThread(row) },
                            onActions = { onThreadActions(row) },
                            // On a desktop the thread's actions are a menu at the pointer: a right-click opens it.
                            modifier = if (isDesktop) Modifier.onSecondaryPress { onThreadActions(row) } else Modifier,
                        )
                    }
                }
            }
            if (pinned.isNotEmpty()) {
                item(key = "label-pinned", contentType = SectionLabelType) {
                    if (onTogglePinned != null) {
                        SectionToggle(
                            stringResource(Res.string.sessions_section_pinned),
                            pinned.size,
                            pinnedCollapsed,
                            stringResource(Res.string.sessions_show_pinned),
                            stringResource(Res.string.sessions_hide_pinned),
                            onTogglePinned,
                        )
                    } else {
                        ListLabel(stringResource(Res.string.sessions_section_pinned))
                    }
                }
                items(
                    visiblePinned(pinned, pinnedCollapsed, selectedId, statuses, drafts),
                    key = { it.id },
                    contentType = { SessionRowType },
                ) { row(it) }
            }
            // The filter chips head the rest, with no label of their own.
            status()
            items(rest, key = { it.id }, contentType = { SessionRowType }) { row(it) }
            if (loadingMore) item(key = "loading-more") { ListSpinner() }
            return@rows
        }
        items(pinned + rest, key = { it.id }, contentType = { SessionRowType }) { row(it) }
        if (loadingMore) item(key = "loading-more") { ListSpinner() }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().arrowFocus().then(if (isDesktop) Modifier.onKeyEvent(jump) else Modifier),
            // Room to scroll the last rows out from under the floating footer.
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp),
            content = {
                positions.clear()
                PositionsScope(this, positions).rows()
            },
        )
        VerticalScrollbar(listState, Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(top = 2.dp, bottom = 96.dp, end = 1.dp))
    }
}

/**
 * Notes where each keyed item lands in the list, so a row out of view can be scrolled to by its key. Everything
 * else is the list's own scope.
 */
private class PositionsScope(private val list: LazyListScope, private val positions: MutableMap<Any, Int>) : LazyListScope by list {
    private var next = 0

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        if (key != null) positions[key] = next
        next++
        list.item(key, contentType, content)
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        if (key != null) for (i in 0 until count) positions[key(i)] = next + i
        next += count
        list.items(count, key, contentType, itemContent)
    }
}

/**
 * What a list's type-ahead has typed: letters typed within a second of each other add up to a title's start; the
 * same letter again steps to the next title that starts with it, as a desktop's lists do.
 */
internal class TypeAhead {
    private var typed = ""
    private var at = 0L
    private var last: String? = null

    /** The chat [event] moves to among [sessions], in their order; null when it isn't a letter to look for. */
    fun next(event: KeyEvent, sessions: List<SessionSummary>, now: Long = Clock.System.now().toEpochMilliseconds()): SessionSummary? {
        if (event.type != KeyEventType.KeyDown || !isLetter(event)) return null
        return next(event.utf16CodePoint.toChar(), sessions, now)
    }

    internal fun next(letter: Char, sessions: List<SessionSummary>, now: Long): SessionSummary? {
        typed = if (now - at > TYPE_AHEAD_PAUSE_MS) letter.toString() else typed + letter
        at = now
        val title = { s: SessionSummary -> s.title.orEmpty().trim() }
        // The same letter over and over steps through the chats it starts; anything longer finds its first match.
        val repeat = typed.length > 1 && typed.all { it.equals(typed[0], ignoreCase = true) }
        val prefix = if (repeat) typed.substring(0, 1) else typed
        val matches = sessions.filter { title(it).startsWith(prefix, ignoreCase = true) }
        if (matches.isEmpty()) return null
        val from = if (repeat || typed.length == 1) matches.indexOfFirst { it.id == last } else -1
        return matches[(from + 1) % matches.size].also { last = it.id }
    }

    companion object {
        /** Whether [event] types a character type-ahead looks for: not a control, a bare space or a shortcut. */
        fun isLetter(event: KeyEvent): Boolean {
            if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
            val code = event.utf16CodePoint
            return code > ' '.code && code != 0x7f && code <= 0xffff
        }
    }
}

private const val TYPE_AHEAD_PAUSE_MS = 1_000L

/** How long after the sidebar shows a desktop reads Hermes's config: past the start's own reads. */
private const val CONFIG_PREFETCH_DELAY = 4_000L

// Thread, pinned and other rows are all a SessionRow, so folding a section lets the list reuse their slots.
private const val SessionRowType = "session-row"
private const val SectionLabelType = "section-label"

/**
 * The Threads section while it is being put in order: each row has a handle to drag it up or down, the rows
 * making way as it passes them, and "Done" on the label saves the order.
 */
@Composable
private fun ThreadReorder(rows: List<ThreadRow>, projectLabel: (ThreadRow) -> String?, onDone: (List<String>) -> Unit) {
    val order = remember { mutableStateListOf<String>().apply { addAll(rows.map { it.thread.id }) } }
    // A thread added or removed meanwhile (on another device) joins or leaves the order where it stands.
    LaunchedEffect(rows) {
        val ids = rows.map { it.thread.id }
        order.retainAll(ids)
        ids.filterNot { it in order }.forEach { order.add(it) }
    }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableStateOf(0f) }
    var rowHeight by remember { mutableStateOf(1f) }
    val byId = rows.associateBy { it.thread.id }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, top = 6.dp).semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.sessions_threads_drag_to_reorder).uppercase(),
                style = Theme[typography][eyebrow],
                color = Theme[colors][textTertiary],
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Button(stringResource(Res.string.sessions_done), onClick = { onDone(order.toList()) }, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
        }
        order.forEach { id ->
            val row = byId[id] ?: return@forEach
            // Keyed, so a row that swaps places keeps its drag going rather than starting over.
            key(id) {
                val lifted = dragging == id
                val dragLabel = stringResource(Res.string.sessions_drag_thread, row.thread.name)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .onSizeChanged { rowHeight = it.height.toFloat().coerceAtLeast(1f) }
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer { translationY = if (lifted) offset else 0f }
                        .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
                        .then(if (lifted) Modifier.background(Theme[colors][surface2]) else Modifier)
                        .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        UnstyledIcon(Lucide.Hash, contentDescription = null, tint = Theme[colors][textMuted], modifier = Modifier.size(14.dp))
                    }
                    TitleAndProject(projectLabel(row), Modifier.weight(1f)) {
                        Text(
                            row.thread.name,
                            style = Theme[typography][body].copy(fontSize = 14.5.sp, lineHeight = 19.sp),
                            color = Theme[colors][text],
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        Modifier
                            .size(MinTouchTarget)
                            .handCursor()
                            .pointerInput(id) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragging = id
                                        offset = 0f
                                    },
                                    onDragEnd = { dragging = null; offset = 0f },
                                    onDragCancel = { dragging = null; offset = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    offset += amount.y
                                    val at = order.indexOf(id)
                                    // Past half a row, it takes the neighbour's place and the offset starts from there.
                                    if (offset > rowHeight / 2 && at < order.lastIndex) {
                                        order.add(at + 1, order.removeAt(at))
                                        offset -= rowHeight
                                    } else if (offset < -rowHeight / 2 && at > 0) {
                                        order.add(at - 1, order.removeAt(at))
                                        offset += rowHeight
                                    }
                                }
                            }
                            .semantics { contentDescription = dragLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        UnstyledIcon(Lucide.GripVertical, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/**
 * A thread's verbs, from its row's long press. Move up and Move down go past the next thread in [shown] (the
 * section as it shows now) and are left out at its ends.
 */
@Composable
internal fun ThreadActionsSheet(
    row: ThreadRow?,
    shown: List<ChatThread>,
    onDismiss: () -> Unit,
    onStartFresh: (ThreadRow) -> Unit,
    onRename: (ThreadRow) -> Unit,
    /** Moves the thread past the other one. */
    onMove: (ChatThread, ChatThread) -> Unit,
    onReorder: () -> Unit,
    onRemove: (ThreadRow) -> Unit,
    /** Opens the thread's chat in a window of its own, as a chat's menu does; null where there are no windows. */
    onNewWindow: ((ThreadRow) -> Unit)? = null,
) {
    // Keep the last row, and the section as it was then, while the sheet animates out: a move that lands
    // meanwhile mustn't add or drop items.
    var kept by remember { mutableStateOf(row to shown) }
    if (row != null) kept = row to shown
    ActionSheet(visible = row != null, onDismiss = onDismiss) {
        val (r, order) = kept
        r ?: return@ActionSheet
        val at = order.indexOfFirst { it.id == r.thread.id }
        val above = order.getOrNull(at - 1)?.takeIf { at >= 0 }
        val below = order.getOrNull(at + 1)?.takeIf { at >= 0 }
        SheetHeader(r.thread.name, stringResource(Res.string.sessions_thread))
        fun act(block: (ThreadRow) -> Unit) = { onDismiss(); block(r) }
        onNewWindow?.let { SheetAction(stringResource(Res.string.sessions_open_in_new_window), Lucide.AppWindow, act(it)) }
        SheetAction(stringResource(Res.string.sessions_start_fresh), Lucide.RotateCcw, act(onStartFresh))
        SheetAction(stringResource(Res.string.sessions_rename_thread), Lucide.Pencil, act(onRename))
        above?.let { other -> SheetAction(stringResource(Res.string.sessions_move_up), Lucide.ArrowUp, act { onMove(it.thread, other) }) }
        below?.let { other -> SheetAction(stringResource(Res.string.sessions_move_down), Lucide.ArrowDown, act { onMove(it.thread, other) }) }
        if (above != null || below != null) SheetAction(stringResource(Res.string.sessions_reorder), Lucide.GripVertical, { onDismiss(); onReorder() })
        SheetAction(stringResource(Res.string.sessions_remove_thread), Lucide.X, act(onRemove))
    }
}

/** Names a thread, and its chat the same. A thread always has a name, so Save waits for one. */
@Composable
internal fun RenameThreadDialog(thread: ChatThread?, onDismiss: () -> Unit, onRename: (ChatThread, String) -> Unit) {
    var shown by remember { mutableStateOf(thread) }
    if (thread != null) shown = thread
    val t = shown ?: return
    val name = rememberTextFieldState(t.name)
    // On every open, so a cancelled edit doesn't come back.
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(thread != null, t.id) { if (thread != null) name.resetTo(t.name, inputMode) }
    val blank = name.text.isBlank()
    val submit = {
        if (!blank) {
            onDismiss()
            onRename(t, name.text.toString())
        }
    }
    Dialog(
        visible = thread != null,
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.sessions_rename_thread),
        message = stringResource(Res.string.sessions_rename_thread_detail),
        actions = {
            Button(stringResource(Res.string.common_cancel), onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(stringResource(Res.string.sessions_save), onClick = submit, size = ButtonSize.Small, enabled = !blank)
        },
    ) {
        TextField(
            state = name,
            placeholder = t.name,
            autofocus = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = { submit() },
        )
    }
}

/** Asks before a thread starts fresh: its chat is archived under the thread's name and the time. */
@Composable
internal fun StartThreadFreshDialog(thread: ChatThread?, onDismiss: () -> Unit, onConfirm: (ChatThread) -> Unit) {
    var shown by remember { mutableStateOf(thread) }
    if (thread != null) shown = thread
    val t = shown ?: return
    Dialog(
        visible = thread != null,
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.sessions_start_thread_fresh_title, t.name),
        message = stringResource(Res.string.sessions_start_thread_fresh_detail, t.name),
        actions = {
            Button(stringResource(Res.string.common_cancel), onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(stringResource(Res.string.sessions_start_fresh), onClick = { onDismiss(); onConfirm(t) }, size = ButtonSize.Small)
        },
    )
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

/**
 * A section's "[title] · n" label as a toggle: a chevron points down while the section is open and right
 * while it is folded. [show] and [hide] are the click labels, naming what it holds ("Show pinned chats").
 */
@Composable
private fun SectionToggle(title: String, count: Int, collapsed: Boolean, show: String, hide: String, onToggle: () -> Unit) {
    val state = stringResource(if (collapsed) Res.string.sessions_collapsed else Res.string.sessions_expanded)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(Theme[radii][radiusSmall]))
            .handCursor()
            .clickable(onClickLabel = if (collapsed) show else hide, role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                heading()
                stateDescription = state
            }
            .padding(start = 10.dp, end = 10.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${title.uppercase()} · $count",
            style = Theme[typography][eyebrow],
            color = Theme[colors][textTertiary],
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        UnstyledIcon(
            Lucide.ChevronDown,
            contentDescription = null,
            tint = Theme[colors][textTertiary],
            modifier = Modifier.size(14.dp).rotate(if (collapsed) -90f else 0f),
        )
    }
}

/**
 * The [pinned] chats the Pinned section lists: all of them while it is open; folded, only the open chat
 * and those with something going on (waiting on the user, running, an unread reply, a draft), so folding
 * never hides what needs a look.
 */
internal fun visiblePinned(
    pinned: List<SessionSummary>,
    collapsed: Boolean,
    selectedId: String?,
    statuses: Map<String, RowStatus>,
    drafts: Set<String>,
): List<SessionSummary> =
    if (!collapsed) pinned
    else pinned.filter { it.id == selectedId || rowTone(statuses[it.id], it.id in drafts) != RowTone.Idle }

/** The [threads] the Threads section lists, folded the way [visiblePinned] folds Pinned. */
internal fun visibleThreads(
    threads: List<ThreadRow>,
    collapsed: Boolean,
    selectedId: String?,
    statuses: Map<String, RowStatus>,
    drafts: Set<String>,
): List<ThreadRow> =
    if (!collapsed) threads
    else threads.filter {
        it.session.id == selectedId || it.thread.sessionId == selectedId ||
            rowTone(statuses[it.session.id], it.session.id in drafts) != RowTone.Idle
    }

/** Whether a row with [status] stays in the list under this filter. */
internal fun AttentionFilter.keeps(status: RowStatus?): Boolean = when (this) {
    AttentionFilter.All -> true
    AttentionFilter.Running -> status?.running == true
    AttentionFilter.NeedsAttention -> status?.needsAttention == true
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

/** The row's second line: everything [status] and [draft] say, as words to join, or empty for an idle chat. */
internal fun rowStatusWords(status: RowStatus?, draft: Boolean): List<StringResource> = listOfNotNull(
    status?.waiting?.let(::waitingWord),
    Res.string.sessions_status_running.takeIf { status?.running == true },
    Res.string.sessions_status_new_reply.takeIf { status?.unread == true },
    Res.string.sessions_status_draft.takeIf { draft },
)

/** What a chat waits on the user for, in a row's words. */
internal fun waitingWord(waiting: Waiting): StringResource = when (waiting) {
    Waiting.Approval -> Res.string.sessions_waiting_approval
    Waiting.Question -> Res.string.sessions_waiting_question
    Waiting.Input -> Res.string.sessions_waiting_input
    Waiting.Unknown -> Res.string.sessions_waiting_unknown
}

/** [rowStatusWords] as the row shows them, or null for an idle chat. */
@Composable
private fun rowStatusLine(status: RowStatus?, draft: Boolean): String? =
    rowStatusWords(status, draft).map { stringResource(it) }.joinToString(" · ").ifEmpty { null }

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
    /** The project the chat is in, said lightly after the title; null shows none. */
    projectLabel: String? = null,
    /** The row is a thread's: marked as one rather than as pinned. */
    thread: Boolean = false,
    onClick: () -> Unit,
    onActions: () -> Unit,
    /** The row's right-click menu, on the desktop; null, a right-click does nothing. */
    menu: (() -> List<RowMenuItem>)? = null,
    /** Goes on the row's clickable node, as [SwipeRow]'s screen-reader actions must. */
    modifier: Modifier = Modifier,
) {
    if (menu == null) return SessionRowContent(session, selected, showSnippet, status, draft, projectLabel, thread, onClick, onActions, modifier)
    var menuOpen by remember { mutableStateOf(false) }
    SessionRowContent(
        session, selected, showSnippet, status, draft, projectLabel, thread, onClick, onActions,
        modifier.onSecondaryPress { menuOpen = true }.menuKeys(menu),
        menuOpen = menuOpen,
    )
    RowContextMenu(menuOpen, onDismiss = { menuOpen = false }, items = menu)
}

/**
 * A chat's action in its row's right-click menu; [destructive] ones are in the danger color. [key] does it too while
 * the row has the focus (F2, Delete), named at the menu row's end.
 */
internal class RowMenuItem(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val key: Key? = null,
    val run: () -> Unit,
)

/** A right-click menu of [items], at the pointer; picking one closes it, then runs it. */
@Composable
internal fun RowContextMenu(visible: Boolean, onDismiss: () -> Unit, items: () -> List<RowMenuItem>) {
    ContextMenu(visible, onDismiss) {
        for (item in items()) {
            MenuRow(item.label, item.icon, onClick = { onDismiss(); item.run() }, destructive = item.destructive, keys = item.key?.let(::keyName))
        }
    }
}

private fun keyName(key: Key): String? = when (key) {
    Key.F2 -> "F2"
    Key.Delete -> if (macKeys) "⌫" else "Del"
    else -> null
}

/** A focused row's [RowMenuItem.key]s, pressed alone. */
private fun Modifier.menuKeys(menu: () -> List<RowMenuItem>): Modifier = onKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || event.isShiftPressed) {
        return@onKeyEvent false
    }
    // A Mac deletes with Backspace, which its keyboards label Delete.
    val pressed = if (macKeys && event.key == Key.Backspace) Key.Delete else event.key
    val item = menu().firstOrNull { it.key == pressed } ?: return@onKeyEvent false
    item.run()
    true
}

/**
 * A right-button press, which opens a context menu on the desktop. Keyed on [onPress], so it acts on the row as
 * it is now; one [unless] says no to goes on to what's under it, such as a selection's own menu.
 */
internal fun Modifier.onSecondaryPress(unless: () -> Boolean = { false }, onPress: () -> Unit): Modifier = pointerInput(onPress) {
    awaitEachGesture {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && !unless()) {
            event.changes.forEach { it.consume() }
            onPress()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRowContent(
    session: SessionSummary,
    selected: Boolean,
    showSnippet: Boolean,
    status: RowStatus?,
    draft: Boolean,
    projectLabel: String?,
    thread: Boolean,
    onClick: () -> Unit,
    onActions: () -> Unit,
    modifier: Modifier,
    /** Its right-click menu is open: it stays lit as under the pointer, so it's clear which chat the menu is for. */
    menuOpen: Boolean = false,
) {
    // A desktop row is cut to a mouse's scale, as desktop sidebars list their items; a phone's to a thumb's.
    val shape = RoundedCornerShape(Theme[radii][if (isDesktop) radiusSmall else radiusMedium])
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
        modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(shape)
            .then(
                when {
                    selected -> Modifier.background(Theme[colors][surface2], shape)
                    menuOpen -> Modifier.background(Theme[colors][text].copy(alpha = 0.08f), shape)
                    else -> Modifier
                },
            )
            .handCursor()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onActions,
                onLongClickLabel = stringResource(Res.string.sessions_session_actions),
                interactionSource = null,
                indication = rememberHoverIndication(Theme[colors][text]),
            )
            .padding(horizontal = 10.dp, vertical = if (isDesktop) 5.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (isDesktop) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val marked = thread || session.pinned
        val mark = if (thread) Lucide.Hash else Lucide.Pin
        val markLabel = stringResource(if (thread) Res.string.sessions_thread else Res.string.sessions_section_pinned)
        val pinOnly = marked && tone == RowTone.Idle
        if (pinOnly) {
            Box(Modifier.size(if (isDesktop) DESKTOP_DISC else 28.dp), contentAlignment = Alignment.Center) {
                UnstyledIcon(mark, contentDescription = markLabel, tint = Theme[colors][textMuted], modifier = Modifier.size(14.dp))
            }
        } else {
            StatusDisc(tone, toneColor)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TitleAndProject(projectLabel, Modifier.weight(1f, fill = false)) {
                    Text(
                        session.displayTitle,
                        style = Theme[typography][body].copy(
                            fontSize = if (isDesktop) 14.sp else 14.5.sp,
                            lineHeight = 19.sp,
                            fontWeight = if (selected || tone != RowTone.Idle && tone != RowTone.Draft) FontWeight.Medium else FontWeight.Normal,
                        ),
                        color = Theme[colors][text],
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (marked && !pinOnly) {
                    UnstyledIcon(mark, contentDescription = markLabel, tint = Theme[colors][textMuted], modifier = Modifier.size(12.dp))
                }
            }
            // Said in words as well as by the disc.
            line?.let {
                Text(it, style = Theme[typography][caption].copy(fontSize = 12.sp), color = toneColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (showSnippet) {
                val detail = session.snippet?.replace('\n', ' ')?.takeIf { it.isNotBlank() } ?: shortAge(session.activityAt)
                if (detail.isNotEmpty()) {
                    Text(detail, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary], maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (!showSnippet) {
            Text(
                shortAge(session.activityAt),
                style = Theme[typography][code].copy(fontSize = 11.5.sp),
                color = Theme[colors][textMuted],
                maxLines = 1,
                // Level with the title when a status line follows it.
                modifier = if (line != null) Modifier.align(Alignment.Top).padding(top = 2.dp) else Modifier,
            )
        }
    }
}

/**
 * The project a row's chat is in, after its title: small and muted like the row's time, and held to a narrow
 * width so a long project name is cut short before the title is.
 */
@Composable
private fun ProjectTag(label: String) {
    val spoken = stringResource(Res.string.sessions_in_project, label)
    Text(
        "· $label",
        style = Theme[typography][caption].copy(fontSize = 12.sp),
        color = Theme[colors][textMuted],
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // Read as words in place of the "·" text, not as well as it.
        modifier = Modifier.widthIn(max = PROJECT_TAG_MAX_WIDTH).clearAndSetSemantics { contentDescription = spoken },
    )
}

/**
 * A row's [title] with its project after it. The title has first claim on the room: the project name is cut
 * down to [PROJECT_TAG_MIN_WIDTH] before the title is cut short at all.
 */
@Composable
private fun TitleAndProject(label: String?, modifier: Modifier = Modifier, title: @Composable () -> Unit) {
    if (label == null) {
        Box(modifier) { title() }
        return
    }
    Layout(contents = listOf(title, { ProjectTag(label) }), modifier = modifier) { (titles, tags), constraints ->
        val gap = 6.dp.roundToPx()
        val tag = tags.first()
        val wanted = minOf(tag.maxIntrinsicWidth(Constraints.Infinity), PROJECT_TAG_MAX_WIDTH.roundToPx())
        val kept = minOf(wanted, PROJECT_TAG_MIN_WIDTH.roundToPx())
        val t = titles.first().measure(Constraints(maxWidth = (constraints.maxWidth - gap - kept).coerceAtLeast(0)))
        val p = tag.measure(Constraints(maxWidth = minOf(wanted, (constraints.maxWidth - t.width - gap).coerceAtLeast(0))))
        val height = maxOf(t.height, p.height)
        val width = (t.width + gap + p.width).coerceAtLeast(constraints.minWidth)
        layout(width, height) {
            t.placeRelative(0, (height - t.height) / 2)
            p.placeRelative(t.width + gap, (height - p.height) / 2)
        }
    }
}

/** The most room a row's project name takes beside the title. */
private val PROJECT_TAG_MAX_WIDTH = 112.dp

/** As little of a row's project name as is kept when the title needs the room ("· herm…"). */
private val PROJECT_TAG_MIN_WIDTH = 48.dp

/** A 28dp disc tinted for [tone], its icon spinning while a turn runs. The row says the same in words. */
@Composable
private fun StatusDisc(tone: RowTone, color: Color) {
    val fill = when (tone) {
        RowTone.Approval, RowTone.Question -> Theme[colors][warningSoft]
        RowTone.Running, RowTone.Reply -> Theme[colors][accentSoft]
        RowTone.Draft, RowTone.Idle -> Theme[colors][surface2]
    }
    Box(Modifier.size(if (isDesktop) DESKTOP_DISC else 28.dp).background(fill, CircleShape).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        when (tone) {
            RowTone.Running -> Spinner(Modifier.size(if (isDesktop) 11.dp else 13.dp), color = color)
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
                modifier = Modifier.size(if (isDesktop) 12.dp else 14.dp),
            )
        }
    }
}

/** A chat row's status disc on the desktop, where rows are packed to a mouse's scale. */
private val DESKTOP_DISC = 22.dp

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
                text = stringResource(filter.label),
                selected = filter == selected,
                onClick = { onSelect(filter) },
                count = count?.takeIf { it > 0 },
                countColor = color,
            )
        }
    }
}

/** The projects as [ProjectDropdown] when the "Projects as a list" setting is on, else as [ProjectChips]. */
@Composable
private fun ProjectFilters(
    projects: List<Project>,
    selected: Project?,
    asDropdown: Boolean,
    onSelect: (Project?) -> Unit,
    /** Null when the gateway can't make projects. */
    onNew: (() -> Unit)? = null,
    /** With the options button's bounds in the window, or null from a right-click on the chip. */
    onOptions: (Project, Rect?) -> Unit = { _, _ -> },
) {
    if (asDropdown) ProjectDropdown(projects, selected, onSelect, onNew, onOptions)
    else ProjectChips(projects, selected, onSelect, onNew, onOptions)
}

/**
 * The gateway's projects as chips, as Desktop's sidebar groups chats: "All", then each project with its chat
 * count, Home last. A new chat started under a project runs in its folder.
 */
@Composable
private fun ProjectChips(
    projects: List<Project>,
    selected: Project?,
    onSelect: (Project?) -> Unit,
    /** Null when the gateway can't make projects. */
    onNew: (() -> Unit)? = null,
    onOptions: (Project, Rect?) -> Unit = { _, _ -> },
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
            if (projects.isNotEmpty()) Chip(text = stringResource(Res.string.sessions_all_projects), selected = selected == null, onClick = { onSelect(null) })
            projects.forEach { project ->
                val isPicked = project.id == selected?.id
                Chip(
                    text = project.label,
                    count = project.sessionCount,
                    selected = isPicked,
                    onClick = { onSelect(project) },
                    // On the desktop its options come straight from the chip too, with a right-click.
                    onLongClick = { onOptions(project, null) }.takeIf { isDesktop && project.hasOptions(onNew != null) },
                    onLongClickLabel = stringResource(Res.string.sessions_project_options, project.label),
                    modifier = if (isPicked) {
                        Modifier.onPlaced { picked = it.positionInParent().x.let { x -> x..(x + it.size.width) } }
                    } else {
                        Modifier
                    },
                )
            }
            if (projects.isEmpty() && onNew != null) Chip(text = stringResource(Res.string.sessions_new_project), selected = false, onClick = onNew)
        }
        // Outside the scrolling chips, so they stay in reach.
        ProjectActions(selected, onNew, onOptions, showNew = projects.isNotEmpty())
    }
}

/**
 * The picked project's options (Rename and Delete for one the user made, "Save as project" for a folder the
 * gateway only grouped chats by) and New project, at the end of the projects' row.
 */
@Composable
private fun ProjectActions(selected: Project?, onNew: (() -> Unit)?, onOptions: (Project, Rect?) -> Unit, showNew: Boolean = true) {
    if (selected != null && selected.hasOptions(onNew != null)) {
        // A plain holder: only the click reads it.
        val bounds = remember { arrayOfNulls<Rect>(1) }
        IconButton(
            Lucide.Ellipsis,
            contentDescription = stringResource(Res.string.sessions_project_options, selected.label),
            onClick = { onOptions(selected, bounds[0]) },
            modifier = Modifier.onGloballyPositioned { bounds[0] = it.boundsInWindow() },
        )
    }
    if (onNew != null && showNew) IconButton(Lucide.FolderPlus, contentDescription = stringResource(Res.string.sessions_new_project), onClick = onNew)
}

/** Whether a project has options to show: one the user made, or a folder that can be saved as one when [canMake]. */
private fun Project.hasOptions(canMake: Boolean): Boolean = !isNoProject && (isUserMade || (path != null && canMake))

/**
 * Tallest the projects menu gets before it scrolls, so many projects are a menu, not a wall: the 4dp top padding
 * and 8½ rows of 48dp, so the last row shows cut in half and the menu reads as one that goes on.
 */
private val PROJECT_MENU_MAX_HEIGHT = 412.dp

/**
 * The gateway's projects behind one button, as Desktop's sidebar groups chats: the button names the picked
 * project (or "All projects") and opens a menu of "All projects", then each project with its chat count, Home
 * last. However many projects there are, they stay in reach and the sidebar stays one row tall. A new chat
 * started under a project runs in its folder.
 */
@Composable
private fun ProjectDropdown(
    projects: List<Project>,
    selected: Project?,
    onSelect: (Project?) -> Unit,
    /** Null when the gateway can't make projects. */
    onNew: (() -> Unit)? = null,
    onOptions: (Project, Rect?) -> Unit = { _, _ -> },
) {
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (projects.isEmpty()) {
            if (onNew != null) Chip(text = stringResource(Res.string.sessions_new_project), selected = false, onClick = onNew)
        } else {
            ProjectMenuButton(projects, selected, onSelect)
            // Beside the button, so they stay in reach.
            ProjectActions(selected, onNew, onOptions)
        }
    }
}

/** The button naming the picked project, taking the room the actions beside it leave, and its menu of projects. */
@Composable
private fun RowScope.ProjectMenuButton(projects: List<Project>, selected: Project?, onSelect: (Project?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shape = CircleShape
    val chooseLabel = stringResource(Res.string.sessions_choose_project)
    val menuState = stringResource(if (open) Res.string.sessions_expanded else Res.string.sessions_collapsed)
    val shown = selected?.label ?: stringResource(Res.string.sessions_all_projects)
    // Takes all the room the buttons beside it leave, with the pill hugging the start: a long name gets that
    // room before it is cut short, and the buttons stay at the end.
    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
        DropdownMenu(
            expanded = open,
            onExpandedChange = { open = it },
            alignment = AnchorAlignment.Start,
            maxHeight = PROJECT_MENU_MAX_HEIGHT,
            items = {
                MenuChoice(stringResource(Res.string.sessions_all_projects), projectIcon(null), selected = selected == null, onClick = { onSelect(null) })
                projects.forEach { project ->
                    MenuChoice(
                        project.label,
                        projectIcon(project),
                        selected = project.id == selected?.id,
                        onClick = { onSelect(project) },
                        count = project.sessionCount,
                    )
                }
            },
        ) {
            val interaction = remember { MutableInteractionSource() }
            val content = Theme[colors][textSecondary]
            // A 32dp pill drawn inside a full-height touch target, as a Chip is.
            Box(
                Modifier
                    .heightIn(min = MinTouchTarget)
                    .handCursor()
                    .clickable(interaction, indication = null, role = Role.DropdownList, onClickLabel = chooseLabel) { open = !open }
                    // The name and count are read from the pill's text; the state says whether the menu shows.
                    .semantics { stateDescription = menuState },
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    Modifier
                        .heightIn(min = 32.dp)
                        .clip(shape)
                        .border(1.dp, Theme[colors][stroke], shape)
                        .background(Theme[colors][surface2], shape)
                        .indication(interaction, rememberHoverIndication(content))
                        .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The same icon the picked project has in the menu.
                    UnstyledIcon(projectIcon(selected), contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
                    Text(
                        shown,
                        style = Theme[typography][label].copy(fontWeight = FontWeight.Medium),
                        color = content,
                        singleLine = true,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    selected?.let {
                        Text(
                            it.sessionCount.toString(),
                            style = Theme[typography][code].copy(fontSize = 12.sp),
                            color = Theme[colors][textTertiary],
                            singleLine = true,
                        )
                    }
                    UnstyledIcon(
                        if (open) Lucide.ChevronUp else Lucide.ChevronDown,
                        contentDescription = null,
                        tint = Theme[colors][textTertiary],
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** A project's icon in the projects menu and on its button: Home a house, all projects a stack, the rest a folder. */
private fun projectIcon(project: Project?): ImageVector = when {
    project == null -> Lucide.Folders
    project.isNoProject -> Lucide.House
    else -> Lucide.Folder
}

@Composable
internal fun ListNotice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CopyableOnDesktop { Text(text, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary]) }
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
    /** The chat's goal and loops sheet; null when the chat has no goal, loop or heartbeat. */
    onControl: ((SessionSummary) -> Unit)? = null,
    /** A bot's chat: archive it and begin an empty one. */
    onStartFresh: ((SessionSummary) -> Unit)? = null,
    /** Makes the chat a thread; null when it can't be one (a thread's chat already, or archived). */
    onMakeThread: ((SessionSummary) -> Unit)? = null,
    /** The button it opened from, on the desktop; null opens it at the pointer, as a right-click's menu. */
    anchor: Rect? = null,
) {
    // Keep the last target while the sheet animates out.
    var shown by remember { mutableStateOf(session) }
    if (session != null) shown = session
    ActionSheet(visible = session != null, onDismiss = onDismiss, anchor = anchor) {
        val s = shown ?: return@ActionSheet
        val age = ageOf(s.activityAt)?.let { stringResource(Res.string.sessions_active_ago, agoPhrase(it)) }
        SheetHeader(s.displayTitle, age)
        fun act(block: (SessionSummary) -> Unit) = { onDismiss(); block(s) }
        onStartFresh?.let { SheetAction(stringResource(Res.string.sessions_start_fresh), Lucide.RotateCcw, act(it)) }
        onRename?.let { SheetAction(stringResource(Res.string.sessions_rename), Lucide.Pencil, act(it)) }
        onMakeThread?.let { SheetAction(stringResource(Res.string.sessions_make_thread), Lucide.Hash, act(it)) }
        onTogglePinned?.let {
            SheetAction(
                stringResource(if (s.pinned) Res.string.sessions_unpin else Res.string.sessions_pin),
                if (s.pinned) Lucide.PinOff else Lucide.Pin,
                act(it),
            )
        }
        onUsage?.let { SheetAction(stringResource(Res.string.sessions_usage_and_cost), Lucide.Gauge, act(it)) }
        onProcesses?.let { SheetAction(stringResource(Res.string.sessions_background_processes), Lucide.SquareTerminal, act(it)) }
        onCheckpoints?.let { SheetAction(stringResource(Res.string.sessions_checkpoints), Lucide.History, act(it)) }
        onControl?.let { SheetAction(stringResource(Res.string.sessions_goal_and_loops), Lucide.Target, act(it)) }
        onExport?.let { SheetAction(stringResource(Res.string.sessions_export_markdown), Lucide.Download, act(it)) }
        onCopyId?.let { SheetAction(stringResource(Res.string.sessions_copy_session_id), Lucide.Copy, act(it)) }
        onToggleArchived?.let {
            SheetAction(
                stringResource(if (s.archived) Res.string.sessions_unarchive else Res.string.sessions_archive),
                if (s.archived) Lucide.ArchiveRestore else Lucide.Archive,
                act(it),
            )
        }
        onDelete?.let { SheetAction(stringResource(Res.string.sessions_delete), Lucide.Trash2, act(it), destructive = true) }
    }
}

@Composable
internal fun RenameDialog(session: SessionSummary?, onDismiss: () -> Unit, onRename: (SessionSummary, String) -> Unit) {
    var shown by remember { mutableStateOf(session) }
    if (session != null) shown = session
    val s = shown ?: return
    val title = rememberTextFieldState(s.title.orEmpty())
    // On every open, so a cancelled edit doesn't come back. A desktop selects the name, as its rename boxes do,
    // so typing replaces it.
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(session != null, s.id) { if (session != null) title.resetTo(s.title.orEmpty(), inputMode) }
    val submit = {
        onDismiss()
        onRename(s, title.text.toString())
    }
    Dialog(
        visible = session != null,
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.sessions_rename_chat),
        message = stringResource(Res.string.sessions_rename_chat_detail),
        actions = {
            Button(stringResource(Res.string.common_cancel), onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(stringResource(Res.string.sessions_save), onClick = submit, size = ButtonSize.Small)
        },
    ) {
        TextField(
            state = title,
            placeholder = s.displayTitle,
            autofocus = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = { submit() },
        )
    }
}

/**
 * Puts [name] back in a rename box. A desktop selects it, as its rename boxes do, so typing replaces it: as from
 * the keyboard, since a selection made in touch mode (a click puts a desktop in it) shows a phone's drag handles.
 * A phone puts the cursor after it, wherever an earlier edit left it.
 */
internal fun TextFieldState.resetTo(name: String, inputMode: InputModeManager) {
    if (isDesktop) inputMode.requestInputMode(InputMode.Keyboard)
    edit {
        replace(0, length, name)
        if (isDesktop) selectAll() else placeCursorAtEnd()
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
        title = stringResource(Res.string.sessions_delete_chat_title),
        message = stringResource(Res.string.sessions_delete_chat_detail, s.displayTitle),
        actions = {
            Button(stringResource(Res.string.common_cancel), onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(
                stringResource(Res.string.sessions_delete),
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
    /** The desktop's account row: the menu opens over it. */
    anchor: Rect? = null,
) {
    ActionSheet(visible = visible, onDismiss = onDismiss, anchor = anchor) {
        SheetHeader(userLabel ?: stringResource(Res.string.sessions_signed_in), gateway.url)
        // In line with the rows: a desktop's menu is narrower than a phone's sheet.
        Box(Modifier.padding(horizontal = if (isDesktop) 12.dp else 20.dp, vertical = if (isDesktop) 6.dp else 8.dp)) {
            ConnectionLine(connection, detailed = true)
        }
        if (profileLabel != null) {
            SheetAction(stringResource(Res.string.sessions_profile_named, profileLabel), Lucide.Bot, onClick = { onDismiss(); onOpenProfiles() })
        }
        SheetAction(stringResource(Res.string.common_settings), Lucide.Settings, onClick = { onDismiss(); onOpenSettings() })
        SheetAction(
            stringResource(if (refreshing) Res.string.sessions_refreshing_chats else Res.string.sessions_refresh_chats),
            Lucide.RefreshCw,
            onClick = { onDismiss(); onRefresh() },
        )
        if (connection is ConnectionState.Reconnecting || connection is ConnectionState.Failed) {
            SheetAction(stringResource(Res.string.sessions_retry_connection), Lucide.RefreshCw, onClick = onRetry)
        }
        SheetAction(stringResource(Res.string.sessions_sign_out), Lucide.LogOut, onClick = { onDismiss(); onSignOut() })
        SheetAction(stringResource(Res.string.sessions_switch_gateway), Lucide.ArrowLeftRight, onClick = { onDismiss(); onOpenGateways() })
    }
}

/**
 * The gateway's profiles, default first, the active one checked. Each is its
 * own agent, so switching changes the chats listed and starts from where that profile left off.
 */
@Composable
internal fun ProfileSheet(
    visible: Boolean,
    profiles: List<Profile>,
    selected: String?,
    onDismiss: () -> Unit,
    onSelect: (Profile) -> Unit,
) {
    BottomSheet(visible = visible, onDismiss = onDismiss) {
        SheetHeader(stringResource(Res.string.sessions_profiles), stringResource(Res.string.sessions_profiles_detail))
        // A long list scrolls under the header; a short one keeps the sheet sized to its content. It opens with the
        // active profile in sight, wherever it is in the list: two frames on, as the desktop's panel is only laid out
        // after the frame it opens on.
        val activeRow = remember { BringIntoViewRequester() }
        LaunchedEffect(visible) {
            if (!visible) return@LaunchedEffect
            withFrameNanos {}
            withFrameNanos {}
            runCatching { activeRow.bringIntoView() }
        }
        Column(Modifier.weight(1f, fill = false).verticalScrollWithBar()) {
            profiles.forEach { profile ->
                val checked = profile.name == selected
                ProfileRow(
                    profile,
                    checked = checked,
                    onClick = { onSelect(profile) },
                    modifier = if (checked) Modifier.bringIntoViewRequester(activeRow) else Modifier,
                )
            }
        }
        if (profiles.size < 2) {
            Text(
                stringResource(Res.string.sessions_profiles_add_hint),
                style = Theme[typography][bodySmall],
                color = Theme[colors][textTertiary],
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ProfileRow(profile: Profile, checked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val detail = profile.description?.trim()?.takeIf { it.isNotEmpty() } ?: profile.model?.let(::displayModelName)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .handCursor()
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
            val sub = listOfNotNull(stringResource(Res.string.sessions_profile_default).takeIf { profile.isDefault }, detail).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary], maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (checked) UnstyledIcon(Lucide.Check, contentDescription = stringResource(Res.string.sessions_profile_active), tint = Theme[colors][accentText], modifier = Modifier.size(20.dp))
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
            IconButton(Lucide.X, contentDescription = stringResource(Res.string.sessions_dismiss), onClick = onDismiss, tint = Theme[colors][accentText])
        }
    }
}
