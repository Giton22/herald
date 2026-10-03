package dev.hermeskotlin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.ui.chat.ChatScreen
import dev.hermeskotlin.ui.connect.ConnectScreen
import dev.hermeskotlin.ui.sessions.SessionsScreen
import dev.hermeskotlin.ui.signin.SignInScreen
import org.koin.compose.viewmodel.koinViewModel

/** Root composable shared by every platform. Koin must be started by the platform host first. */
@Composable
fun App() {
    HermesTheme {
        val app: AppViewModel = koinViewModel()
        val route by app.route.collectAsStateWithLifecycle()

        PlatformBackHandler(enabled = route is Route.SignIn || route is Route.Chat) { app.back() }

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

            is Route.Sessions, is Route.Chat -> {
                val gateway = (r as? Route.Sessions)?.gateway ?: (r as Route.Chat).gateway
                // The list stays composed under an open chat so its scroll position survives Back.
                Box(Modifier.fillMaxSize()) {
                    SessionsScreen(
                        gateway = gateway,
                        onOpenSession = { app.openSession(it.id, it.displayTitle) },
                        onNewChat = app::newChat,
                        onSessionExpired = app::onSessionExpired,
                        onSignOut = app::signOut,
                        onChangeGateway = app::changeGateway,
                        onTop = r is Route.Sessions,
                    )
                    if (r is Route.Chat) {
                        // Swallow taps that land on empty chat space so they never reach the list below.
                        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
                            ChatScreen(target = r.target, onBack = { app.back() })
                        }
                    }
                }
            }
        }
    }
}
