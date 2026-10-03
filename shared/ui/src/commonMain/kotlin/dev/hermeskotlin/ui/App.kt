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
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.PureBlack
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.SidebarLayout
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.rememberSidebarState
import dev.hermeskotlin.ui.chat.ChatScreen
import dev.hermeskotlin.ui.chat.ChatViewModel
import dev.hermeskotlin.ui.connect.ConnectScreen
import dev.hermeskotlin.ui.sessions.ChatMenu
import dev.hermeskotlin.ui.sessions.SessionsSidebar
import dev.hermeskotlin.ui.settings.SettingsScreen
import dev.hermeskotlin.ui.signin.SignInScreen
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
 */
@Composable
fun App(appVersion: String? = null, onDarkTheme: (Boolean) -> Unit = {}) {
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
    HermesTheme(scheme) {
        CompositionLocalProvider(
            LocalAppSettings provides settings,
            LocalAppVersion provides appVersion,
            LocalDensity provides Density(density.density, density.fontScale * settings.textSize.scale),
        ) { Routes() }
    }
}

@Composable
private fun Routes() {
    val app: AppViewModel = koinViewModel()
    val route by app.route.collectAsStateWithLifecycle()

    PlatformBackHandler(enabled = route is Route.SignIn) { app.back() }

    when (val r = route) {
        Route.Loading -> Box(
            Modifier.fillMaxSize().background(Theme[colors][background]),
            contentAlignment = Alignment.Center,
        ) { Spinner() }

        Route.Connect -> ConnectScreen(onContinue = app::onGatewayChosen)

        is Route.SignIn -> SignInScreen(
            gateway = r.gateway,
            notice = r.notice,
            onSignedIn = { app.onSignedIn(r.gateway) },
            onChangeGateway = app::changeGateway,
        )

        is Route.Chat -> Home(r, app)
    }
}

/** The signed-in home: the chat, with the sessions sidebar to its left (a drawer on phones, docked on wide screens). */
@Composable
private fun Home(route: Route.Chat, app: AppViewModel) {
    val chat: ChatViewModel = koinViewModel()
    val chatState by chat.state.collectAsStateWithLifecycle()
    val sidebar = rememberSidebarState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // The open chat, once it exists on the gateway (a new chat gets its row with the first prompt).
    val openSessionId = chatState.storedSessionId?.takeIf { route.target.storedSessionId != null || chatState.messages.isNotEmpty() }

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
                onChangeGateway = app::changeGateway,
                onOpenSettings = { settingsOpen = true },
            )
        },
    ) {
        ChatScreen(
            target = route.target,
            onOpenSidebar = { scope.launch { sidebar.toggle() } },
            onNewChat = app::newChat,
            onOpenMenu = openSessionId?.let { { menuOpen = true } },
            viewModel = chat,
        )
    }

    ChatMenu(
        visible = menuOpen && openSessionId != null,
        sessionId = openSessionId,
        title = chatState.title?.takeIf { it.isNotBlank() } ?: route.target.title ?: "Untitled session",
        messages = chatState.messages,
        onDismiss = { menuOpen = false },
        onRenamed = chat::showTitle,
        onDeleted = app::newChat,
    )

    if (settingsOpen) {
        SettingsScreen(
            gateway = route.gateway,
            onBack = { settingsOpen = false },
            onSignOut = app::signOut,
            onChangeGateway = app::changeGateway,
        )
    }
}
