package dev.hermeskotlin.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import dev.hermeskotlin.android.notify.ChatNotifications
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatLinks
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.ui.App
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    // The last theme shown, read synchronously so the window behind the first frame already matches it.
    private val windowPrefs by lazy { getSharedPreferences("window", MODE_PRIVATE) }

    private val host: ChatHost by inject()
    private val connection: GatewayConnection by inject()
    private val links: ChatLinks by inject()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (windowPrefs.contains(KEY_DARK)) applyWindowTheme(windowPrefs.getBoolean(KEY_DARK, false))
        askForNotificationsOnFirstTurn()
        if (savedInstanceState == null) openLinkedChat(intent)
        setContent {
            App(appVersion = BuildConfig.VERSION_NAME, releasesRepo = BuildConfig.RELEASES_REPO, onDarkTheme = { dark ->
                applyWindowTheme(dark)
                windowPrefs.edit { putBoolean(KEY_DARK, dark) }
            })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLinkedChat(intent)
    }

    /**
     * A tapped notification about a chat opens that chat, not just the last one; a bot's notification,
     * shortcut or `hermes://bot/<profile>` link opens that bot's chat.
     */
    private fun openLinkedChat(intent: Intent?) {
        intent ?: return
        val bot = intent.getStringExtra(ChatNotifications.EXTRA_OPEN_BOT)
            ?: intent.data?.takeIf { it.scheme == "hermes" && it.host == "bot" }?.lastPathSegment
        val sessionId = intent.getStringExtra(ChatNotifications.EXTRA_OPEN_SESSION)
        val title = intent.getStringExtra(ChatNotifications.EXTRA_OPEN_TITLE)
        when {
            bot != null -> links.openBot(bot, title, sessionId)
            sessionId != null -> links.open(sessionId, title)
            else -> return
        }
        // Handled once: a recreated activity must not jump back to it.
        intent.removeExtra(ChatNotifications.EXTRA_OPEN_SESSION)
        intent.removeExtra(ChatNotifications.EXTRA_OPEN_BOT)
        intent.data = null
    }

    override fun onStart() {
        super.onStart()
        // Android cuts a background app's network once no turn keeps it in the foreground; coming back
        // shouldn't sit out the rest of a reconnect backoff.
        if (connection.state.value is ConnectionState.Reconnecting) connection.retry()
    }

    /** The app theme can differ from the system's, so the window and bar icons follow the app. */
    private fun applyWindowTheme(dark: Boolean) {
        window.setBackgroundDrawableResource(if (dark) R.color.window_background_dark else R.color.window_background_light)
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    /** Asked once, when the first turn starts: that is when "tell me when it's done" makes sense. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun askForNotificationsOnFirstTurn() {
        if (Build.VERSION.SDK_INT < 33 || windowPrefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return
        lifecycleScope.launch {
            host.session.flatMapLatest { it?.state ?: emptyFlow() }.first { it.running }
            windowPrefs.edit { putBoolean(KEY_ASKED_NOTIFICATIONS, true) }
            notificationPermission.launch(permission)
        }
    }

    private companion object {
        const val KEY_DARK = "dark"
        const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
    }
}
