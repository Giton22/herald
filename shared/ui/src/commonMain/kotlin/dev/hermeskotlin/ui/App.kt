package dev.hermeskotlin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.theme.ColorScheme
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.ThemeMode
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.LocalAccentPalette
import dev.hermeskotlin.designsystem.PureBlack
import dev.hermeskotlin.designsystem.hermesTheme
import dev.hermeskotlin.designsystem.components.LocalCodeWrap
import dev.hermeskotlin.designsystem.components.LocalGlow
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.SidebarLayout
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.rememberSidebarState
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.ui.bots.BotEditor
import dev.hermeskotlin.ui.bots.BotFaces
import dev.hermeskotlin.ui.bots.StartOverDialog
import dev.hermeskotlin.ui.bots.BotsViewModel
import dev.hermeskotlin.ui.bots.LocalBotFaces
import dev.hermeskotlin.ui.chat.ChatScreen
import dev.hermeskotlin.ui.rooms.RoomScreen
import dev.hermeskotlin.ui.rooms.DesktopRoomScreen
import dev.hermeskotlin.ui.rooms.RoomsViewModel
import dev.hermeskotlin.ui.rooms.RoomFileLink
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.core.media.MediaApi
import dev.hermeskotlin.ui.chat.GatewayFileViewer
import dev.hermeskotlin.ui.chat.ChatViewModel
import dev.hermeskotlin.ui.chat.ModelConfirmDialog
import dev.hermeskotlin.ui.chat.ModelSheet
import dev.hermeskotlin.ui.chat.ProvideReplySpeaker
import dev.hermeskotlin.ui.chat.replySpeaker
import dev.hermeskotlin.ui.sessions.CapabilitiesPage
import dev.hermeskotlin.ui.sessions.ConfigPage
import dev.hermeskotlin.core.chat.ChatState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import dev.hermeskotlin.ui.connect.ConnectScreen
import dev.hermeskotlin.ui.gateways.GatewaysSheet
import dev.hermeskotlin.ui.sessions.ArchiveUndoToast
import dev.hermeskotlin.ui.sessions.ChatMenu
import dev.hermeskotlin.ui.sessions.SessionsSidebar
import dev.hermeskotlin.ui.settings.AppLockCover
import dev.hermeskotlin.ui.settings.PhoneQuestion
import dev.hermeskotlin.ui.settings.PhoneQuestionDialog
import dev.hermeskotlin.ui.settings.SettingsScreen
import dev.hermeskotlin.ui.plugins.PluginWebView
import dev.hermeskotlin.ui.signin.SignInScreen
import dev.hermeskotlin.ui.update.LocalRenameOffer
import dev.hermeskotlin.ui.update.LocalUpdateOffer
import dev.hermeskotlin.ui.update.rememberRenameOffer
import dev.hermeskotlin.ui.update.rememberUpdateOffer
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import dev.hermeskotlin.core.inbox.InboxLink
import dev.hermeskotlin.core.inbox.InboxLinks
import dev.hermeskotlin.core.rooms.RoomLinks
import org.koin.compose.koinInject
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import dev.hermeskotlin.ui.sessions.OtherChat
import dev.hermeskotlin.ui.sessions.OtherChatEvent
import dev.hermeskotlin.ui.sessions.OtherChatRow
import dev.hermeskotlin.ui.sessions.OtherChatsViewModel
import dev.hermeskotlin.ui.sessions.SessionsBadge
import dev.hermeskotlin.ui.sessions.SessionsViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The current [AppSettings], for screens that change how they draw. */
val LocalAppSettings = compositionLocalOf { AppSettings() }

/** The app's own version name, shown in Settings; null when the platform didn't pass one. */
val LocalAppVersion = staticCompositionLocalOf<String?> { null }

/**
 * Root composable shared by every platform. Koin must be started by the platform host first.
 * [onDarkTheme] tells the host which theme is showing, e.g. to color the system bar icons.
 * [releasesRepo] is the GitHub `owner/name` the build was released from, to look for newer releases.
 * While [locked] (App lock), a cover hides everything until [onUnlock] lets the user back in.
 * [phoneQuestion] is a phone tool asking before it changes something, shown over any screen.
 */
@Composable
fun App(
    appVersion: String? = null,
    releasesRepo: String? = null,
    onDarkTheme: (Boolean) -> Unit = {},
    locked: Boolean = false,
    onUnlock: () -> Unit = {},
    phoneQuestion: PhoneQuestion? = null,
) {
    // Nothing is drawn until the stored settings are read, so the first frame has the right theme.
    val settings = koinInject<SettingsStore>().settings.collectAsStateWithLifecycle().value ?: return
    val dark = when (settings.theme) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    LaunchedEffect(dark) { onDarkTheme(dark) }
    val scheme = when {
        !dark -> ColorScheme.Light
        settings.pureBlack -> PureBlack
        else -> ColorScheme.Dark
    }
    val density = LocalDensity.current
    val palette = AccentPalette.named(settings.accent)
    hermesTheme(palette)(scheme) {
        CompositionLocalProvider(
            LocalAccentPalette provides palette,
            LocalAppSettings provides settings,
            LocalCodeWrap provides settings.wrapCode,
            LocalGlow provides settings.glow,
            LocalAppVersion provides appVersion,
            LocalUpdateOffer provides rememberUpdateOffer(releasesRepo, appVersion, settings.checkForUpdates),
            LocalRenameOffer provides rememberRenameOffer(),
            LocalDensity provides Density(density.density, density.fontScale * settings.textSize.scale),
        ) {
            // The screens leave composition rather than sit under the cover, so their sheets and dialogs
            // (windows of their own) can't show over it. View models and drafts outlive this.
            if (locked) {
                AppLockCover(onUnlock)
            } else {
                Routes()
                PhoneQuestionDialog(phoneQuestion)
            }
        }
    }
}

@Composable
private fun Routes() {
    val app: AppViewModel = koinViewModel()
    val route by app.route.collectAsStateWithLifecycle()

    val choices by app.gatewayChoices.collectAsStateWithLifecycle()
    var gatewaysOpen by remember { mutableStateOf(false) }

    PlatformBackHandler(enabled = (route as? Route.SignIn)?.adding == true || (route as? Route.Connect)?.canCancel == true) { app.back() }

    when (val r = route) {
        Route.Loading -> Box(
            Modifier.fillMaxSize().background(Theme[colors][background]),
            contentAlignment = Alignment.Center,
        ) { Spinner() }

        is Route.Connect -> ConnectScreen(
            onContinue = app::onGatewayChosen,
            onCancel = if (r.canCancel) app::cancelAddGateway else null,
        )

        is Route.SignIn -> SignInScreen(
            gateway = r.gateway,
            notice = r.notice,
            onSignedIn = { app.onSignedIn(r.gateway) },
            // With nothing else saved, the sheet would only offer "Add a gateway".
            onChangeGateway = {
                when {
                    r.adding -> app.back()
                    choices.list.gateways.any { it.url != r.gateway.url } -> gatewaysOpen = true
                    else -> app.addGateway()
                }
            },
        )

        is Route.Chat -> Home(r, app, onOpenGateways = { gatewaysOpen = true })
    }

    GatewaysSheet(
        visible = gatewaysOpen,
        choices = choices,
        activeUrl = (route as? Route.Chat)?.gateway?.url ?: (route as? Route.SignIn)?.gateway?.url,
        onDismiss = { gatewaysOpen = false },
        onSwitch = app::switchGateway,
        onAdd = app::addGateway,
        onSetPrimary = app::setPrimaryGateway,
        onRename = app::renameGateway,
        onRemove = app::removeGateway,
    )
}

/** The bot editor's subject: [bot] to change, or null for a new one. */
private class BotEditing(val bot: Bot?)

/** The signed-in home: the chat, with the sessions sidebar to its left (a drawer on phones, docked on wide screens). */
@Composable
private fun Home(route: Route.Chat, app: AppViewModel, onOpenGateways: () -> Unit) {
    val chat: ChatViewModel = koinViewModel()
    val chatState by chat.state.collectAsStateWithLifecycle()
    val sidebar = rememberSidebarState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // A plugin's page open over everything, served by the gateway itself; null when none is open.
    var pluginUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var pluginLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val chatsProfile by app.chatsProfile.collectAsStateWithLifecycle()
    val bots: BotsViewModel = koinViewModel()
    val rooms: RoomsViewModel = koinViewModel()

    // The hosted room filling the content area; null shows the chat. The view model owns it, so a
    // gateway switch (which closes the room) brings the chat back on its own.
    val openRoom by rooms.opened.collectAsStateWithLifecycle()
    val roster by bots.state.collectAsStateWithLifecycle()
    val pictures by bots.avatars.collectAsStateWithLifecycle()
    val faces = remember(roster.all, pictures) { BotFaces(roster.all, pictures) }
    // A Desktop room open from the sidebar's "On Desktop" group: read-only, so it is only local state.
    var openDesktopRoom by rememberSaveable { mutableStateOf<String?>(null) }
    val desktopRoom = openDesktopRoom?.let { key -> roster.desktopRooms.firstOrNull { it.key == key } }
    // A file a room's bot linked to, open over the room; it closes when the gateway or the room changes.
    val roomKey = RoomFileLink.roomKey(openRoom?.room?.roomId, desktopRoom?.key)
    var roomFile by rememberSaveable(route.gateway.url, roomKey, stateSaver = RoomFileLink.Saver) { mutableStateOf<RoomFileLink?>(null) }
    val openRoomFile: (String?, GatewayFile) -> Unit = { profile, file ->
        if (roomKey != null) roomFile = RoomFileLink(route.gateway.gatewayUrl, roomKey, profile, file)
    }
    // The bot whose chat is open, as the roster knows it now.
    val openBot = route.target.bot?.let { open -> roster.all.firstOrNull { it.name == open.name } }
    var startOver by remember { mutableStateOf<Bot?>(null) }
    var editing by remember { mutableStateOf<BotEditing?>(null) }

    // The open chat, once it exists on the gateway (a new chat gets its row with the first prompt).
    val openSessionId = chatState.storedSessionId?.takeIf { route.target.storedSessionId != null || chatState.hasConversation }

    // The open bot's chat was started over elsewhere (Desktop, another phone): follow it to the new one,
    // as Desktop does. Never mid-turn. A chat that only moved on by compression is still this one.
    val liveChat = openBot?.canonicalSession
    LaunchedEffect(liveChat?.id, liveChat?.openId) {
        val bot = openBot ?: return@LaunchedEffect
        val next = liveChat?.openId ?: return@LaunchedEffect
        val open = openSessionId ?: return@LaunchedEffect
        // Just opened, the chat's state is still the previous chat's; the route already has the live one.
        if (next == route.target.storedSessionId) return@LaunchedEffect
        if (open != liveChat.id && open != next && !chatState.running) app.openBotChat(bot, next)
    }

    fun closeDrawer() {
        if (!sidebar.docked) scope.launch { sidebar.close() }
    }

    /** Picking a chat in the sidebar leaves the open room, so the chat is what shows. */
    fun closeDrawerToChat() {
        rooms.close()
        openDesktopRoom = null
        closeDrawer()
    }

    PlatformBackHandler(enabled = sidebar.isOpen && !sidebar.docked) { closeDrawer() }
    // A hosted room closes back to the chat before anything else.
    PlatformBackHandler(enabled = openRoom != null) { rooms.close() }
    // A Desktop room's read-only copy closes back to the chat too.
    PlatformBackHandler(enabled = desktopRoom != null) { openDesktopRoom = null }
    // A chat opened from anywhere else (a notification, a link, the share sheet, a new bot) leaves the
    // room too. Only a change counts: coming back to the same chat, e.g. after a rotation, keeps it open.
    var shownTarget by remember { mutableStateOf(route.target) }
    LaunchedEffect(route.target) {
        if (route.target != shownTarget) {
            shownTarget = route.target
            rooms.close()
            openDesktopRoom = null
        }
    }
    // Another gateway has other rooms, and its own mirror. Only a change counts here too: the effect also
    // runs when Home is composed again (a rotation), and the open copy must survive that.
    var shownGateway by remember { mutableStateOf(route.gateway.url) }
    LaunchedEffect(route.gateway.url) {
        if (route.gateway.url != shownGateway) {
            shownGateway = route.gateway.url
            openDesktopRoom = null
        }
    }
    // The open copy is read from the roster, so the roster keeps refreshing while it shows, sidebar closed or not.
    LaunchedEffect(openDesktopRoom != null) { bots.setDesktopRoomOpen(openDesktopRoom != null) }
    // A room deleted on Desktop (or a mirror that went away) closes its copy here; a switched gateway
    // clears it through the roster reset too, once the first read comes back.
    LaunchedEffect(desktopRoom, roster.loading) {
        if (openDesktopRoom != null && desktopRoom == null && !roster.loading) openDesktopRoom = null
    }
    // A tapped Inbox notification: the drawer opens here, then the sidebar takes the link and consumes it.
    // Only one of them consumes, after the drawer has settled, so the opening isn't cut short. A touch that
    // stops the opening halfway still hands the link on: kept pending, the same notification tapped again
    // would change nothing and open nothing.
    val inboxLinks = koinInject<InboxLinks>()
    val inboxLink by inboxLinks.pending.collectAsStateWithLifecycle()
    var shownInboxLink by remember { mutableStateOf<InboxLink?>(null) }
    LaunchedEffect(inboxLink) {
        val link = inboxLink ?: return@LaunchedEffect
        try {
            sidebar.open()
        } finally {
            shownInboxLink = link
        }
    }
    // A room asked for from outside the screens (a tapped notification) opens over the chat.
    val roomLinks = koinInject<RoomLinks>()
    val roomLink by roomLinks.pending.collectAsStateWithLifecycle()
    LaunchedEffect(roomLink) {
        val link = roomLink ?: return@LaunchedEffect
        rooms.openById(link.roomId, link.name)
        openDesktopRoom = null
        roomLinks.consume(link)
        closeDrawer()
    }
    // Another gateway's plugins are other plugins. Only a change counts: a rotation brings the same
    // gateway back, and the open page with it.
    var pluginGateway by rememberSaveable { mutableStateOf(route.gateway.url) }
    LaunchedEffect(route.gateway.url) {
        if (route.gateway.url != pluginGateway) {
            pluginGateway = route.gateway.url
            pluginUrl = null
            pluginLabel = null
        }
    }
    // Other chats that need the user: the Sessions badge, the banner over the chat, Next and the buzz, with
    // "Alerts for other chats" on (off by default). Watched only while the chat itself is on screen with Herald in
    // the foreground; that keeps the gateway's live list polled then (every 10 s, as while the sidebar shows), and
    // never in the background.
    val sessions: SessionsViewModel = koinViewModel()
    val profileRoster by sessions.roster.collectAsStateWithLifecycle()
    // The bot answering in the open chat, named above its replies. Only a launch profile the gateway named counts:
    // a guessed `default` could name the wrong bot. Equal unless what's drawn changed, so a roster refresh doesn't
    // redraw the chat.
    val speaker = replySpeaker(
        faces,
        route.target.bot?.name,
        route.target.profile,
        profileRoster?.takeIf { it.launchKnown }?.launch,
    )
    val others: OtherChatsViewModel = koinViewModel { parametersOf(sessions) }
    val settings = LocalAppSettings.current
    val alerts = settings.otherChatsAlerts
    LaunchedEffect(alerts, settings.otherChatsBanner) { others.setOtherChats(alerts, banner = settings.otherChatsBanner) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // A page drawn over the chat (Settings, a plugin, the bot editor) hides it too: a banner there would time out unseen.
    val chatShown = lifecycleState.isAtLeast(Lifecycle.State.STARTED) && openRoom == null && desktopRoom == null &&
        !settingsOpen && pluginUrl == null && editing == null
    LaunchedEffect(chatShown) { others.setChatShown(chatShown) }
    // The phone's drawer over the chat: the sidebar says it all there, so the banner waits until it closes.
    val drawerOver by remember { derivedStateOf { sidebar.fraction > 0f && !sidebar.docked } }
    DisposableEffect(others) { onDispose { others.setChatShown(false) } }
    // Every id the open chat goes by (a bot's chat moves on after /compress), so it never counts as another.
    val openIds = setOfNotNull(openSessionId, route.target.storedSessionId, openBot?.canonicalSession?.id, openBot?.canonicalSession?.openId)
    LaunchedEffect(openIds) { others.setOpenChat(openIds) }
    // Bot Chats are hidden sessions, so the chat list doesn't have them; the roster does, with what's unread.
    LaunchedEffect(roster.all, roster.unread) {
        others.setBotChats(
            roster.all.filterNot { it.meta.hidden }.mapNotNull { bot ->
                val chat = bot.canonicalSession ?: return@mapNotNull null
                val id = chat.openId ?: return@mapNotNull null
                OtherChatRow(
                    OtherChat(id, bot.label, unread = bot.name in roster.unread, activityAt = chat.lastActive, bot = bot.name),
                    ids = setOfNotNull(chat.id, id),
                )
            },
        )
    }
    val sessionsBadge by others.badge.collectAsStateWithLifecycle()
    val banner by others.banner.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val vibrate = alerts && settings.otherChatsVibrate
    LaunchedEffect(others, vibrate) {
        if (!vibrate) return@LaunchedEffect
        others.otherChatEvents.collect { if (it.waiting) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }

    /** Opens another chat as a sidebar tap does, with where its new messages start. */
    fun openOther(id: String, title: String, bot: String?) {
        val botOf = bot?.let { name -> roster.all.firstOrNull { it.name == name } }
        // A bot's chat opens as its sidebar row does, which finds where its new messages start.
        if (botOf != null) bots.open(botOf) { storedId, readUpTo -> app.openBotChat(botOf, storedId, readUpTo) }
        else app.openSession(id, title, others.openListed(id))
        closeDrawerToChat()
    }

    // On phones the keyboard makes way for the drawer.
    LaunchedEffect(sidebar) {
        snapshotFlow { sidebar.fraction > 0f && !sidebar.docked }.filter { it }.collect { focusManager.clearFocus() }
    }

    SidebarLayout(
        state = sidebar,
        sidebar = {
            SessionsSidebar(
                gateway = route.gateway,
                profile = chatsProfile,
                selectedId = openSessionId,
                visible = sidebar.isOpen,
                onOpenSession = { session, readUpTo ->
                    if (session.id != openSessionId) app.openSession(session.id, session.displayTitle, readUpTo)
                    closeDrawerToChat()
                },
                onNewChat = { cwd ->
                    app.newChat(cwd = cwd)
                    closeDrawerToChat()
                },
                onDeleted = { if (it.id == openSessionId) app.newChat() },
                onSessionExpired = app::onSessionExpired,
                onSignOut = app::signOut,
                onOpenGateways = onOpenGateways,
                onOpenSettings = { settingsOpen = true },
                onSwitchProfile = {
                    app.switchProfile(it)
                    closeDrawerToChat()
                },
                onOpenBot = { bot, storedSessionId, readUpTo ->
                    if (storedSessionId != openSessionId || route.target.bot == null) app.openBotChat(bot, storedSessionId, readUpTo)
                    closeDrawerToChat()
                },
                onOpenBotSession = { bot, id, title ->
                    app.openBotSession(bot, id, title)
                    closeDrawerToChat()
                },
                onNewBotChat = { bot ->
                    app.newBotChat(bot)
                    closeDrawerToChat()
                },
                onEditBot = { bot -> editing = BotEditing(bot) },
                onBotDeleted = { bot -> if (route.target.bot?.name == bot.name || route.target.profile == bot.name) app.newChat() },
                onOpenRoom = { room ->
                    rooms.open(room)
                    closeDrawer()
                },
                onOpenDesktopRoom = { room ->
                    rooms.close()
                    openDesktopRoom = room.key
                    closeDrawer()
                },
                onOpenPlugin = { plugin ->
                    // The page lives on the gateway the app is signed in to; view it there.
                    plugin.openPath?.let { path ->
                        pluginUrl = route.gateway.gatewayUrl.resolve(path)
                        pluginLabel = plugin.label
                    }
                },
                selectedRunning = chatState.running,
                inboxLink = shownInboxLink,
                onInboxLinkTaken = { link ->
                    // A newer link may be handed on already: it is left for the sidebar to take.
                    if (shownInboxLink == link) shownInboxLink = null
                    inboxLinks.consume(link)
                },
            )
        },
    ) {
        CompositionLocalProvider(LocalBotFaces provides faces) {
            when {
                openRoom != null -> RoomScreen(
                    viewModel = rooms,
                    onOpenSidebar = { scope.launch { sidebar.toggle() } },
                    onBack = rooms::close,
                    onOpenFile = openRoomFile,
                )
                desktopRoom != null -> DesktopRoomScreen(
                    room = desktopRoom,
                    onOpenSidebar = { scope.launch { sidebar.toggle() } },
                    onBack = { openDesktopRoom = null },
                    onOpenFile = openRoomFile,
                )
                // Rooms draw their own transcripts, each bot under its own name; only the chat has one speaker.
                else -> ProvideReplySpeaker(speaker) {
                    ChatScreen(
                        target = route.target,
                        onOpenSidebar = { scope.launch { sidebar.toggle() } },
                        onNewChat = app::newChat,
                        onOpenMenu = openSessionId?.let { { menuOpen = true } },
                        onOpenChat = { id, title -> app.openSession(id, title ?: "Untitled session") },
                        onSwitchProfile = app::switchProfile,
                        viewModel = chat,
                        sessionsBadge = if (alerts) sessionsBadge else SessionsBadge(),
                        otherChatBanner = banner.takeIf { alerts && settings.otherChatsBanner && !drawerOver },
                        onOpenOtherChat = { event: OtherChatEvent ->
                            others.dismissBanner(event)
                            openOther(event.chatId, event.title, event.bot)
                        },
                        onDismissOtherChat = others::dismissBanner,
                        onNextChat = if (!alerts) null else fun(): Boolean {
                            val next = others.nextChat()
                            if (next != null) {
                                banner?.let(others::dismissBanner)
                                openOther(next.id, next.title, next.bot)
                            }
                            return next != null
                        },
                    )
                }
            }
        }
    }

    roomFile?.shownIn(roomKey, roster.all)?.let { target ->
        // Keyed by the whole link: the viewer caches by path, and the same path is another file for another bot.
        key(target) {
            val media = koinInject<MediaApi>()
            GatewayFileViewer(target.file, onDismiss = { roomFile = null },
                load = { target.preview(media, it) }, download = { target.download(media, it) })
        }
    }

    ArchiveUndoToast()

    ChatMenu(
        visible = menuOpen && openSessionId != null,
        sessionId = openSessionId,
        title = route.target.bot?.label ?: chatState.title?.takeIf { it.isNotBlank() } ?: route.target.title ?: "Untitled session",
        messages = chatState.messages,
        onDismiss = { menuOpen = false },
        onRenamed = chat::showTitle,
        onDeleted = app::newChat,
        onUsage = chat::openUsage,
        onProcesses = chat::openProcesses,
        onCheckpoints = chat::openCheckpoints,
        // Only a chat carrying a goal, loop or heartbeat gets the row.
        onControl = chat::openControl.takeIf { chatState.control != null },
        botChat = route.target.bot != null,
        onStartFresh = openBot?.let { bot -> { startOver = bot } },
        onOpenChat = app::openSession,
    )
    StartOverDialog(startOver, onDismiss = { startOver = null }) { bot ->
        bots.startFresh(bot) { id -> app.openBotChat(bot, id) }
    }

    editing?.let { target ->
        val busy by bots.busy.collectAsStateWithLifecycle()
        val modelPicker by bots.modelPicker.collectAsStateWithLifecycle()
        var pickingModel by remember { mutableStateOf(false) }
        var capabilitiesOpen by remember { mutableStateOf(false) }
        var configOpen by remember { mutableStateOf(false) }
        val live = target.bot?.let { edited -> roster.all.firstOrNull { it.name == edited.name } ?: edited }
        BotEditor(
            bot = target.bot,
            taken = roster.all.map { it.name }.toSet(),
            busy = busy,
            loadDetails = { target.bot?.let { bots.details(it) } },
            onBack = { editing = null },
            onCreate = { draft ->
                bots.create(draft, onDone = { editing = null }) { bot, id ->
                    app.openBotChat(bot, id)
                    closeDrawer()
                }
            },
            onSave = { description, soul, look ->
                target.bot?.let { bot -> bots.save(bot, description, soul, look) { editing = null } }
            },
            live = live,
            onPickModel = { pickingModel = true },
            onCapabilities = { capabilitiesOpen = true },
            onConfig = { configOpen = true },
        )
        live?.let { bot ->
            ModelSheet(
                visible = pickingModel,
                onDismiss = { pickingModel = false },
                state = ChatState(model = bot.model, provider = bot.provider),
                picker = modelPicker,
                onRefresh = { bots.loadModels(bot) },
                onSelectModel = { model -> bots.setModel(bot, model) { pickingModel = false } },
                onSelectEffort = {},
                onFast = {},
                modelOnly = true,
                note = "${bot.label} runs this in all its chats, its Bot Chat and routines included.",
            )
            ModelConfirmDialog(
                modelPicker.confirm,
                onConfirm = { model -> bots.setModel(bot, model, confirmed = true) { pickingModel = false } },
                onDismiss = bots::dismissModelConfirm,
            )
            if (capabilitiesOpen) {
                PlatformBackHandler(enabled = true) { capabilitiesOpen = false }
                Box(Modifier.fillMaxSize().background(Theme[colors][background]).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    CapabilitiesPage(
                        gateway = route.gateway,
                        profile = bot.name,
                        profileLabel = bot.label,
                        onBack = { capabilitiesOpen = false },
                        onSessionExpired = app::onSessionExpired,
                        title = "${bot.label}'s capabilities",
                        // Hermes fixes a chat's tools when it starts (#124211).
                        note = "Changes apply to ${bot.label}'s new chats. If its Bot Chat doesn't pick one up, Start fresh gives it a new chat with everything set here.",
                        viewModel = koinViewModel(key = "capabilities-${bot.name}"),
                    )
                }
            }
            if (configOpen) {
                PlatformBackHandler(enabled = true) { configOpen = false }
                Box(Modifier.fillMaxSize().background(Theme[colors][background]).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    ConfigPage(
                        gateway = route.gateway,
                        profile = bot.name,
                        onBack = { configOpen = false },
                        onSessionExpired = app::onSessionExpired,
                        title = "${bot.label}'s config",
                        note = "${bot.label}'s own settings, saved to its profile's config.yaml. Most apply from its next chat.",
                        viewModel = koinViewModel(key = "config-${bot.name}"),
                    )
                }
            }
        }
    }

    if (settingsOpen) {
        SettingsScreen(
            gateway = route.gateway,
            onBack = { settingsOpen = false },
            onSignOut = app::signOut,
            onOpenGateways = onOpenGateways,
            profile = chatsProfile,
        )
    }

    val openPluginUrl = pluginUrl
    val openPluginTitle = pluginLabel
    if (openPluginUrl != null && openPluginTitle != null) {
        PluginWebView(
            url = openPluginUrl,
            gateway = route.gateway.url,
            title = openPluginTitle,
            onClose = { pluginUrl = null; pluginLabel = null },
        )
    }
}
