package dev.hermeskotlin.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.pm.ShortcutManagerCompat
import dev.hermeskotlin.android.notify.BotShortcuts
import dev.hermeskotlin.android.notify.ChatNotifications
import dev.hermeskotlin.core.chat.AppLink
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatLinks
import dev.hermeskotlin.core.rooms.RoomLinks
import dev.hermeskotlin.core.chat.ComposeDraft
import dev.hermeskotlin.core.chat.SharedContent
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.ui.App
import dev.hermeskotlin.ui.chat.readAttachments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

// A FragmentActivity because BiometricPrompt (App lock) needs one.
class MainActivity : FragmentActivity() {

    // The last theme shown, read synchronously so the window behind the first frame already matches it.
    private val windowPrefs by lazy { getSharedPreferences("window", MODE_PRIVATE) }

    private val host: ChatHost by inject()
    private val connection: GatewayConnection by inject()
    private val links: ChatLinks by inject()
    private val roomLinks: RoomLinks by inject()
    private val settings: SettingsStore by inject()
    private val appScope: CoroutineScope by inject()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val app get() = application as HermesApplication
    private val appLock get() = app.appLock
    private val lockGate by lazy { AppLockGate(this, appLock, onUnlocked = ::openHeldIntent) }

    /** Set when the lock closes, so the unlock prompt shows by itself once, not again after a cancel. */
    private var promptOnResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (windowPrefs.contains(KEY_DARK)) applyWindowTheme(windowPrefs.getBoolean(KEY_DARK, false))
        // The stored setting is read asynchronously; this copy decides the very first frame.
        val lockOn = windowPrefs.getBoolean(KEY_APP_LOCK, false)
        appLock.onLaunch(lockOn, fresh = savedInstanceState == null)
        lockGate.hideFromRecents(lockOn)
        promptOnResume = appLock.locked.value
        followAppLockSetting()
        askForNotificationsOnFirstTurn()
        if (savedInstanceState == null) handleIntent(intent)
        val browser = InAppBrowser(this) { windowPrefs.getBoolean(KEY_DARK, false) }
        setContent {
            val locked by appLock.locked.collectAsState()
            CompositionLocalProvider(LocalUriHandler provides remember { browser }) {
                App(
                    appVersion = BuildConfig.VERSION_NAME,
                    releasesRepo = BuildConfig.RELEASES_REPO,
                    onDarkTheme = { dark ->
                        applyWindowTheme(dark)
                        windowPrefs.edit { putBoolean(KEY_DARK, dark) }
                    },
                    locked = locked,
                    onUnlock = lockGate::ask,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** A link, shortcut, notification or share, opened now or once App lock lets the user in. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (appLock.locked.value) {
            app.heldIntent = intent
            return
        }
        val shared = sharedContent(this, intent)
        if (shared != null) share(shared, sharedToBot(intent)) else openLinkedChat(intent)
    }

    /** The bot a share was aimed at from the share sheet (its conversation shortcut), or null for a new chat. */
    private fun sharedToBot(intent: Intent): String? =
        intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)
            ?.takeIf { it.startsWith(BotShortcuts.ID_PREFIX) }
            ?.removePrefix(BotShortcuts.ID_PREFIX)
            ?.takeIf { it.isNotBlank() }

    private fun openHeldIntent() {
        val intent = app.heldIntent ?: return
        app.heldIntent = null
        handleIntent(intent)
    }

    /**
     * A tapped notification about a chat opens that chat, not just the last one; a bot's notification or
     * shortcut opens that bot's chat; a room's notification opens that room; a `hermes://` link opens what it
     * names (a new chat, a stored one, a bot).
     */
    private fun openLinkedChat(intent: Intent) {
        val bot = intent.getStringExtra(ChatNotifications.EXTRA_OPEN_BOT)
        val room = intent.getStringExtra(ChatNotifications.EXTRA_OPEN_ROOM)
        val sessionId = intent.getStringExtra(ChatNotifications.EXTRA_OPEN_SESSION)
        val title = intent.getStringExtra(ChatNotifications.EXTRA_OPEN_TITLE)
        val link = intent.data?.let { AppLink.parse(it.toString()) }
        when {
            room != null -> roomLinks.open(room, title)
            bot != null -> links.openBot(bot, title, sessionId)
            sessionId != null -> links.open(sessionId, title)
            link != null -> links.follow(link)
            else -> return
        }
        // Handled once: a recreated activity must not jump back to it.
        intent.removeExtra(ChatNotifications.EXTRA_OPEN_SESSION)
        intent.removeExtra(ChatNotifications.EXTRA_OPEN_BOT)
        intent.removeExtra(ChatNotifications.EXTRA_OPEN_ROOM)
        intent.data = null
    }

    /**
     * Shared text and files become a draft for the user to look over and send: in a new chat, or in
     * [bot]'s chat when the share sheet aimed it at a bot. The files are read through the composer's own
     * pipeline (photos scaled down, size limits). Before sign-in the draft waits and opens once signed in.
     */
    private fun share(shared: SharedContent, bot: String?) {
        if (shared.isEmpty) return
        // The app's scope, so a rotation mid-read doesn't drop the share.
        appScope.launch {
            val (files, error) = readAttachments(applicationContext, shared.filesToAttach.map(Uri::parse))
            val draft = ComposeDraft(text = shared.draftText, attachments = files, notice = error ?: shared.leftOverNotice)
            if (bot != null) links.shareToBot(bot, draft) else links.newChat(draft)
        }
    }

    /** Mirrors the App lock setting into the lock, the Recents privacy and the copy read at launch. */
    private fun followAppLockSetting() {
        lifecycleScope.launch {
            settings.settings.filterNotNull().map { it.appLock }.distinctUntilChanged().collect { on ->
                windowPrefs.edit { putBoolean(KEY_APP_LOCK, on) }
                appLock.setEnabled(on)
                lockGate.hideFromRecents(on)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appLock.onForeground(SystemClock.elapsedRealtime())
        if (appLock.locked.value) promptOnResume = true
        // Android cuts a background app's network once no turn keeps it in the foreground; coming back
        // shouldn't sit out the rest of a reconnect backoff.
        if (connection.state.value is ConnectionState.Reconnecting) connection.retry()
    }

    override fun onResume() {
        super.onResume()
        if (promptOnResume) {
            promptOnResume = false
            lockGate.ask()
        }
    }

    override fun onStop() {
        super.onStop()
        // A rotation isn't leaving the app.
        if (!isChangingConfigurations) appLock.onBackground(SystemClock.elapsedRealtime())
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
        const val KEY_APP_LOCK = "app_lock"
    }
}
