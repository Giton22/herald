package dev.hermeskotlin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import dev.hermeskotlin.ui.chat.ChatViewModel
import dev.hermeskotlin.ui.chat.ModelConfirmDialog
import dev.hermeskotlin.ui.chat.ModelSheet
import dev.hermeskotlin.ui.sessions.CapabilitiesPage
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
import dev.hermeskotlin.ui.settings.SettingsScreen
import dev.hermeskotlin.ui.signin.SignInScreen
import dev.hermeskotlin.ui.update.LocalUpdateOffer
import dev.hermeskotlin.ui.update.rememberUpdateOffer
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** The current [AppSettings], for screens that change how they draw. */
val LocalAppSettings = compositionLocalOf { AppSettings() }

/** The app's own version name, shown in Settings; null when the platform didn't pass one. */
val LocalAppVersion = staticCompositionLocalOf<String?> { null }

/**
 * Root composable shared by every platform. Koin must be started by the platform host first.
 * [onDarkTheme] tells the host which theme is showing, e.g. to color the system bar icons.
 * [releasesRepo] is the GitHub `owner/name` the build was released from, to look for newer releases.
 * While [locked] (App lock), a cover hides everything until [onUnlock] lets the user back in.
 */
@Composable
fun App(
    appVersion: String? = null,
    releasesRepo: String? = null,
    onDarkTheme: (Boolean) -> Unit = {},
    locked: Boolean = false,
    onUnlock: () -> Unit = {},
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
            LocalAppVersion provides appVersion,
            LocalUpdateOffer provides rememberUpdateOffer(releasesRepo, appVersion, settings.checkForUpdates),
            LocalDensity provides Density(density.density, density.fontScale * settings.textSize.scale),
        ) {
            // The screens leave composition rather than sit under the cover, so their sheets and dialogs
            // (windows of their own) can't show over it. View models and drafts outlive this.
            if (locked) AppLockCover(onUnlock) else Routes()
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
    var menuOpen by remember { mutableStateOf(false) }
    val chatsProfile by app.chatsProfile.collectAsStateWithLifecycle()
    val bots: BotsViewModel = koinViewModel()
    val roster by bots.state.collectAsStateWithLifecycle()
    val pictures by bots.avatars.collectAsStateWithLifecycle()
    val faces = remember(roster.all, pictures) { BotFaces(roster.all, pictures) }
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
        if (open != liveChat.id && open != next && !chatState.running) app.openBotChat(bot, next)
    }

    fun closeDrawer() {
        if (!sidebar.docked) scope.launch { sidebar.close() }
    }

    PlatformBackHandler(enabled = sidebar.isOpen && !sidebar.docked) { closeDrawer() }
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
                onOpenSession = {
                    if (it.id != openSessionId) app.openSession(it.id, it.displayTitle)
                    closeDrawer()
                },
                onNewChat = {
                    app.newChat()
                    closeDrawer()
                },
                onDeleted = { if (it.id == openSessionId) app.newChat() },
                onSessionExpired = app::onSessionExpired,
                onSignOut = app::signOut,
                onOpenGateways = onOpenGateways,
                onOpenSettings = { settingsOpen = true },
                onSwitchProfile = {
                    app.switchProfile(it)
                    closeDrawer()
                },
                onOpenBot = { bot, storedSessionId ->
                    if (storedSessionId != openSessionId || route.target.bot == null) app.openBotChat(bot, storedSessionId)
                    closeDrawer()
                },
                onOpenBotSession = { bot, id, title ->
                    app.openBotSession(bot, id, title)
                    closeDrawer()
                },
                onNewBotChat = { bot ->
                    app.newBotChat(bot)
                    closeDrawer()
                },
                onEditBot = { bot -> editing = BotEditing(bot) },
                onBotDeleted = { bot -> if (route.target.bot?.name == bot.name || route.target.profile == bot.name) app.newChat() },
                selectedRunning = chatState.running,
            )
        },
    ) {
        CompositionLocalProvider(LocalBotFaces provides faces) {
            ChatScreen(
                target = route.target,
                onOpenSidebar = { scope.launch { sidebar.toggle() } },
                onNewChat = app::newChat,
                onOpenMenu = openSessionId?.let { { menuOpen = true } },
                onOpenChat = { id, title -> app.openSession(id, title ?: "Untitled session") },
                onSwitchProfile = app::switchProfile,
                viewModel = chat,
            )
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
        botChat = route.target.bot != null,
        onStartFresh = openBot?.let { bot -> { startOver = bot } },
    )
    StartOverDialog(startOver, onDismiss = { startOver = null }) { bot ->
        bots.startFresh(bot) { id -> app.openBotChat(bot, id) }
    }

    editing?.let { target ->
        val busy by bots.busy.collectAsStateWithLifecycle()
        val modelPicker by bots.modelPicker.collectAsStateWithLifecycle()
        var pickingModel by remember { mutableStateOf(false) }
        var capabilitiesOpen by remember { mutableStateOf(false) }
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
                        onBack = { capabilitiesOpen = false },
                        onSessionExpired = app::onSessionExpired,
                        title = "${bot.label}'s capabilities",
                        // Hermes fixes a chat's tools when it starts (#124211).
                        note = "Changes apply to ${bot.label}'s new chats. If its Bot Chat doesn't pick one up, Start fresh gives it a new chat with everything set here.",
                        viewModel = koinViewModel(key = "capabilities-${bot.name}"),
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
        )
    }
}
