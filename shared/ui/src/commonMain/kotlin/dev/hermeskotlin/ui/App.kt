package dev.hermeskotlin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.ui.connect.ConnectScreen
import dev.hermeskotlin.ui.sessions.SessionsScreen
import dev.hermeskotlin.ui.signin.SignInScreen
import dev.hermeskotlin.ui.transcript.TranscriptScreen
import org.koin.compose.viewmodel.koinViewModel

/** Root composable shared by every platform. Koin must be started by the platform host first. */
@Composable
fun App() {
    HermesTheme {
        val app: AppViewModel = koinViewModel()
        val route by app.route.collectAsStateWithLifecycle()

        PlatformBackHandler(enabled = route is Route.SignIn || route is Route.Transcript) { app.back() }

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

            is Route.Sessions, is Route.Transcript -> {
                val gateway = (r as? Route.Sessions)?.gateway ?: (r as Route.Transcript).gateway
                // The list stays composed under an open transcript so its scroll position survives Back.
                Box(Modifier.fillMaxSize()) {
                    SessionsScreen(
                        gateway = gateway,
                        onOpenSession = { app.openSession(it.id, it.displayTitle) },
                        onSessionExpired = app::onSessionExpired,
                        onSignOut = app::signOut,
                        onChangeGateway = app::changeGateway,
                    )
                    if (r is Route.Transcript) {
                        TranscriptScreen(
                            gateway = r.gateway,
                            sessionId = r.sessionId,
                            title = r.title,
                            onBack = { app.back() },
                            onSessionExpired = app::onSessionExpired,
                        )
                    }
                }
            }
        }
    }
}
