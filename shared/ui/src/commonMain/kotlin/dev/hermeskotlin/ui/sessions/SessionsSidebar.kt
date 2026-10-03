package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.models.displayModelName
import dev.hermeskotlin.core.profiles.Profile
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
    onNewChat: () -> Unit,
    onDeleted: (SessionSummary) -> Unit,
    onSessionExpired: () -> Unit,
    onSignOut: () -> Unit,
    onChangeGateway: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchProfile: (String?) -> Unit,
    viewModel: SessionsViewModel = koinViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val roster by viewModel.roster.collectAsStateWithLifecycle()
    var profilesOpen by remember { mutableStateOf(false) }

    var searchOpen by remember { mutableStateOf(false) }
    var scheduledOpen by remember { mutableStateOf(false) }
    var capabilitiesOpen by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var renameTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var accountOpen by remember { mutableStateOf(false) }

    fun closeSearch() {
        searchOpen = false
        viewModel.query.clearText()
    }

    // Back steps out of search or the Scheduled / Archived pages before it closes the drawer.
    PlatformBackHandler(enabled = visible && (searchOpen || scheduledOpen || capabilitiesOpen || state.filter != SessionListFilter.Recent)) {
        when {
            searchOpen -> closeSearch()
            scheduledOpen -> scheduledOpen = false
            capabilitiesOpen -> capabilitiesOpen = false
            else -> viewModel.setFilter(SessionListFilter.Recent)
        }
    }
    LaunchedEffect(gateway, profile) { viewModel.bind(gateway, profile) }
    var wasVisible by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible && !wasVisible) viewModel.refreshQuietly()
        wasVisible = visible
    }
    LaunchedEffect(state.sessionExpired) { if (state.sessionExpired) onSessionExpired() }
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(4_000)
            viewModel.dismissMessage()
        }
    }

    val open: (SessionSummary) -> Unit = {
        onOpenSession(it)
        if (searchOpen) closeSearch()
    }
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
        } else if (capabilitiesOpen && !searchOpen) {
            CapabilitiesPage(
                gateway = gateway,
                profile = profile,
                onBack = { capabilitiesOpen = false },
                onSessionExpired = onSessionExpired,
            )
        } else Column(Modifier.fillMaxSize()) {
            when {
                searchOpen -> SearchHeader(viewModel, onClose = ::closeSearch)
                state.filter != SessionListFilter.Recent -> SubpageHeader(
                    title = state.filter.label,
                    onBack = { viewModel.setFilter(SessionListFilter.Recent) },
                )
                else -> MainHeader(onSearch = { searchOpen = true })
            }
            if (!searchOpen && state.filter == SessionListFilter.Recent) {
                UpdateBanner(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val searchResults = state.searchResults
                when {
                    searchOpen && searchResults == null -> Unit
                    searchResults != null -> when {
                        state.searching && searchResults.isEmpty() -> CenteredSpinner()
                        searchResults.isEmpty() -> EmptyState(Lucide.SearchX, "No matches", "Search looks at titles, session ids and message text.")
                        else -> SessionList(searchResults, selectedId, open, rowActions, showSnippets = true)
                    }
                    state.filter == SessionListFilter.Recent -> SessionList(
                        sessions = state.sessions,
                        selectedId = selectedId,
                        onOpen = open,
                        onActions = rowActions,
                        canLoadMore = state.canLoadMore,
                        loadingMore = state.loadingMore,
                        onLoadMore = viewModel::loadMore,
                        sectioned = true,
                        status = {
                            when {
                                state.loading -> item(key = "loading") { ListSpinner() }
                                state.error != null -> item(key = "error") {
                                    ListNotice("Couldn't load sessions. ${state.error}", action = "Try again", onAction = viewModel::refresh)
                                }
                                state.sessions.isEmpty() -> item(key = "empty") {
                                    ListNotice("Your conversations will show up here.")
                                }
                            }
                        },
                    ) {
                        item(key = "nav") {
                            Column(Modifier.padding(bottom = 4.dp)) {
                                NavRow(Lucide.CalendarClock, "Scheduled jobs") { scheduledOpen = true }
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
                onNewChat()
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
        onChangeGateway = onChangeGateway,
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
internal fun SessionsSidebarSample(sessions: List<SessionSummary>, selectedId: String?, userLabel: String) {
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
    ) {
        Column(Modifier.fillMaxSize()) {
            MainHeader(onSearch = {})
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SessionList(sessions, selectedId, onOpen = {}, onActions = {}, sectioned = true) {
                    item(key = "nav") {
                        Column(Modifier.padding(bottom = 4.dp)) {
                            NavRow(Lucide.CalendarClock, "Scheduled jobs") {}
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
private fun MainHeader(onSearch: () -> Unit) {
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
        SquareButton(Lucide.Search, "Search chats", onClick = onSearch)
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

/** A 44dp square icon button with no fill. */
@Composable
private fun SquareButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
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
            .heightIn(min = 44.dp)
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
 * Desktop's session row: a status dot, the title and its age. The dot lights up while the session is
 * running. Long-press for actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionRow(
    session: SessionSummary,
    selected: Boolean,
    showSnippet: Boolean,
    onClick: () -> Unit,
    onActions: () -> Unit,
) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
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
                    if (session.isActive) Theme[colors][success] else Theme[colors][strokeStrong],
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
            if (!showSnippet) {
                Text(relativeTime(session.activityAt), style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1)
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
    onTogglePinned: (SessionSummary) -> Unit,
    onRename: (SessionSummary) -> Unit,
    onToggleArchived: (SessionSummary) -> Unit,
    onDelete: (SessionSummary) -> Unit,
    onExport: ((SessionSummary) -> Unit)? = null,
    onCopyId: ((SessionSummary) -> Unit)? = null,
    onUsage: ((SessionSummary) -> Unit)? = null,
    onProcesses: ((SessionSummary) -> Unit)? = null,
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
        SheetAction("Rename", Lucide.Pencil, act(onRename))
        SheetAction(if (s.pinned) "Unpin" else "Pin", if (s.pinned) Lucide.PinOff else Lucide.Pin, act(onTogglePinned))
        onUsage?.let { SheetAction("Usage and cost", Lucide.Gauge, act(it)) }
        onProcesses?.let { SheetAction("Background processes", Lucide.SquareTerminal, act(it)) }
        onExport?.let { SheetAction("Export as Markdown", Lucide.Download, act(it)) }
        onCopyId?.let { SheetAction("Copy session ID", Lucide.Copy, act(it)) }
        SheetAction(if (s.archived) "Unarchive" else "Archive", if (s.archived) Lucide.ArchiveRestore else Lucide.Archive, act(onToggleArchived))
        SheetAction("Delete", Lucide.Trash2, act(onDelete), destructive = true)
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
    onChangeGateway: () -> Unit,
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
        SheetAction("Use a different gateway", Lucide.ArrowLeftRight, onClick = { onDismiss(); onChangeGateway() })
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
