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
import dev.hermeskotlin.ui.home.HomeScreen
import dev.hermeskotlin.ui.signin.SignInScreen
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

            is Route.Home -> HomeScreen(
                gateway = r.gateway,
                onSignOut = app::signOut,
                onChangeGateway = app::changeGateway,
            )
        }
    }
}
