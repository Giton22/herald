package dev.hermeskotlin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.SidebarLayout
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.rememberSidebarState
import dev.hermeskotlin.ui.chat.ChatScreen
import dev.hermeskotlin.ui.chat.ChatViewModel
import dev.hermeskotlin.ui.connect.ConnectScreen
import dev.hermeskotlin.ui.sessions.SessionsSidebar
import dev.hermeskotlin.ui.signin.SignInScreen
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** Root composable shared by every platform. Koin must be started by the platform host first. */
@Composable
fun App() {
    HermesTheme {
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
}

/** The signed-in home: the chat, with the sessions sidebar to its left (a drawer on phones, docked on wide screens). */
@Composable
private fun Home(route: Route.Chat, app: AppViewModel) {
    val chat: ChatViewModel = koinViewModel()
    val chatState by chat.state.collectAsStateWithLifecycle()
    val sidebar = rememberSidebarState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

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
            )
        },
    ) {
        ChatScreen(
            target = route.target,
            onOpenSidebar = { scope.launch { sidebar.toggle() } },
            onNewChat = app::newChat,
            viewModel = chat,
        )
    }
}
