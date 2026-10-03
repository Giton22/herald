package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Archive
import com.composables.icons.lucide.ArchiveRestore
import com.composables.icons.lucide.ArrowLeftRight
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.CircleUser
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.PinOff
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Search
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
import dev.hermeskotlin.core.sessions.SessionListFilter
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Chip
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.SheetAction
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.components.ConnectionLine
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.components.relativeTime
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SessionsScreen(
    gateway: SavedGateway,
    onOpenSession: (SessionSummary) -> Unit,
    onNewChat: () -> Unit,
    onSessionExpired: () -> Unit,
    onSignOut: () -> Unit,
    onChangeGateway: () -> Unit,
    /** False while a chat covers the list; turning true again refetches, since that chat may have changed it. */
    onTop: Boolean = true,
    viewModel: SessionsViewModel = koinViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()

    var actionTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var renameTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var accountOpen by remember { mutableStateOf(false) }

    LaunchedEffect(gateway) { viewModel.bind(gateway) }
    var wasOnTop by remember { mutableStateOf(onTop) }
    LaunchedEffect(onTop) {
        if (onTop && !wasOnTop) viewModel.refreshQuietly()
        wasOnTop = onTop
    }
    LaunchedEffect(state.sessionExpired) { if (state.sessionExpired) onSessionExpired() }
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(4_000)
            viewModel.dismissMessage()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            TopBar(
                connection = connection,
                refreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                onAccount = { accountOpen = true },
            )
            TextField(
                state = viewModel.query,
                placeholder = "Search sessions",
                leadingIcon = Lucide.Search,
                clearable = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            )
            val searchResults = state.searchResults
            if (searchResults == null) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FILTERS.forEach { (filter, label) ->
                        Chip(label, selected = state.filter == filter, onClick = { viewModel.setFilter(filter) })
                    }
                }
            } else {
                Box(Modifier.padding(top = 12.dp))
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    searchResults != null -> when {
                        state.searching && searchResults.isEmpty() -> CenteredSpinner()
                        searchResults.isEmpty() -> EmptyState(Lucide.SearchX, "No matches", "Search looks at titles, session ids and message text.")
                        else -> SessionList(
                            sessions = searchResults,
                            sectioned = false,
                            canLoadMore = false,
                            loadingMore = false,
                            onLoadMore = {},
                            onOpen = onOpenSession,
                            onActions = { actionTarget = it },
                        )
                    }
                    state.loading -> CenteredSpinner()
                    state.error != null -> EmptyState(Lucide.CloudOff, "Couldn't load sessions", state.error) {
                        Button("Try again", onClick = viewModel::refresh, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
                    }
                    state.sessions.isEmpty() -> when (state.filter) {
                        SessionListFilter.Recent ->
                            EmptyState(Lucide.Inbox, "No sessions yet", "Conversations from the desktop app, CLI and messaging platforms show up here.")
                        SessionListFilter.Scheduled ->
                            EmptyState(Lucide.CalendarClock, "No scheduled runs", "Sessions started by cron jobs on the gateway show up here.")
                        SessionListFilter.Archived ->
                            EmptyState(Lucide.Archive, "Nothing archived", "Archived sessions are hidden from Recent but stay resumable.")
                    }
                    else -> SessionList(
                        sessions = state.sessions,
                        sectioned = state.filter == SessionListFilter.Recent,
                        canLoadMore = state.canLoadMore,
                        loadingMore = state.loadingMore,
                        onLoadMore = viewModel::loadMore,
                        onOpen = onOpenSession,
                        onActions = { actionTarget = it },
                    )
                }

                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.message?.let { message -> MessageBanner(message, onDismiss = viewModel::dismissMessage) }
                    Button(
                        "New chat",
                        onClick = onNewChat,
                        leadingIcon = Lucide.SquarePen,
                        size = ButtonSize.Large,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                }
            }
        }
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
    DeleteDialog(deleteTarget, onDismiss = { deleteTarget = null }, onDelete = viewModel::delete)
    AccountSheet(
        visible = accountOpen,
        gateway = gateway,
        userLabel = user?.label,
        connection = connection,
        onDismiss = { accountOpen = false },
        onRetry = viewModel::retryConnection,
        onSignOut = onSignOut,
        onChangeGateway = onChangeGateway,
    )
}

@Composable
private fun TopBar(
    connection: ConnectionState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onAccount: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Sessions", style = Theme[typography][title], color = Theme[colors][text])
            ConnectionLine(connection)
        }
        if (refreshing) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Spinner(Modifier.size(18.dp)) }
        } else {
            IconButton(Lucide.RefreshCw, contentDescription = "Refresh", onClick = onRefresh)
        }
        IconButton(Lucide.CircleUser, contentDescription = "Account", onClick = onAccount)
    }
}

@Composable
private fun SessionList(
    sessions: List<SessionSummary>,
    sectioned: Boolean,
    canLoadMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onOpen: (SessionSummary) -> Unit,
    onActions: (SessionSummary) -> Unit,
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

    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // Room for the floating "New chat" button.
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = bottomInset + 88.dp),
    ) {
        if (sectioned) {
            val (pinned, recent) = sessions.partition { it.pinned }
            if (pinned.isNotEmpty()) {
                section("Pinned", pinned, onOpen, onActions)
                section("Recent", recent, onOpen, onActions)
            } else {
                rows(recent, onOpen, onActions)
            }
        } else {
            rows(sessions, onOpen, onActions)
        }
        if (loadingMore) {
            item(key = "loading-more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner(Modifier.size(20.dp)) }
            }
        }
    }
}

private fun LazyListScope.section(
    title: String,
    sessions: List<SessionSummary>,
    onOpen: (SessionSummary) -> Unit,
    onActions: (SessionSummary) -> Unit,
) {
    if (sessions.isEmpty()) return
    item(key = "header-$title") {
        Text(
            title.uppercase(),
            style = Theme[typography][caption],
            color = Theme[colors][textTertiary],
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
        )
    }
    rows(sessions, onOpen, onActions)
}

private fun LazyListScope.rows(
    sessions: List<SessionSummary>,
    onOpen: (SessionSummary) -> Unit,
    onActions: (SessionSummary) -> Unit,
) {
    items(sessions, key = { it.id }) { session ->
        SessionRow(session, onClick = { onOpen(session) }, onActions = { onActions(session) })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(session: SessionSummary, onClick: () -> Unit, onActions: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onActions,
                onLongClickLabel = "Session actions",
                interactionSource = null,
                indication = rememberColoredIndication(Theme[colors][text]),
            )
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (session.isActive) Box(Modifier.size(7.dp).background(Theme[colors][success], CircleShape))
                Text(
                    session.displayTitle,
                    style = Theme[typography][body],
                    color = Theme[colors][text],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (session.pinned) {
                    UnstyledIcon(Lucide.Pin, contentDescription = "Pinned", tint = Theme[colors][textTertiary], modifier = Modifier.size(13.dp))
                }
            }
            val excerpt = session.snippet ?: session.preview?.takeIf { session.title != null }
            if (!excerpt.isNullOrBlank()) {
                Text(
                    excerpt.replace('\n', ' '),
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textSecondary],
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                listOfNotNull(
                    relativeTime(session.activityAt).takeIf { it.isNotEmpty() },
                    session.source?.takeIf { it.isNotBlank() },
                    session.model?.takeIf { it.isNotBlank() },
                    "${session.messageCount} msgs".takeIf { session.messageCount > 0 },
                ).joinToString(" · "),
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(Lucide.EllipsisVertical, contentDescription = "Session actions", onClick = onActions)
    }
}

@Composable
private fun SessionActionsSheet(
    session: SessionSummary?,
    onDismiss: () -> Unit,
    onTogglePinned: (SessionSummary) -> Unit,
    onRename: (SessionSummary) -> Unit,
    onToggleArchived: (SessionSummary) -> Unit,
    onDelete: (SessionSummary) -> Unit,
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
        SheetAction(if (s.pinned) "Unpin" else "Pin", if (s.pinned) Lucide.PinOff else Lucide.Pin, act(onTogglePinned))
        SheetAction("Rename", Lucide.Pencil, act(onRename))
        SheetAction(if (s.archived) "Unarchive" else "Archive", if (s.archived) Lucide.ArchiveRestore else Lucide.Archive, act(onToggleArchived))
        SheetAction("Delete", Lucide.Trash2, act(onDelete), destructive = true)
    }
}

@Composable
private fun RenameDialog(session: SessionSummary?, onDismiss: () -> Unit, onRename: (SessionSummary, String) -> Unit) {
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
private fun DeleteDialog(session: SessionSummary?, onDismiss: () -> Unit, onDelete: (SessionSummary) -> Unit) {
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
    connection: ConnectionState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    onChangeGateway: () -> Unit,
) {
    BottomSheet(visible = visible, onDismiss = onDismiss) {
        SheetHeader(userLabel ?: "Signed in", gateway.url)
        Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { ConnectionLine(connection, detailed = true) }
        if (connection is ConnectionState.Reconnecting || connection is ConnectionState.Failed) {
            SheetAction("Retry connection now", Lucide.RefreshCw, onClick = onRetry)
        }
        SheetAction("Sign out", Lucide.LogOut, onClick = { onDismiss(); onSignOut() })
        SheetAction("Use a different gateway", Lucide.ArrowLeftRight, onClick = { onDismiss(); onChangeGateway() })
    }
}

@Composable
private fun CenteredSpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
}


@Composable
private fun MessageBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.padding(horizontal = 16.dp).fillMaxWidth(), elevated = true) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = Theme[typography][bodySmall], color = Theme[colors][text], modifier = Modifier.weight(1f))
            IconButton(Lucide.X, contentDescription = "Dismiss", onClick = onDismiss, tint = Theme[colors][accent])
        }
    }
}

private val FILTERS = listOf(
    SessionListFilter.Recent to "Recent",
    SessionListFilter.Scheduled to "Scheduled",
    SessionListFilter.Archived to "Archived",
)
