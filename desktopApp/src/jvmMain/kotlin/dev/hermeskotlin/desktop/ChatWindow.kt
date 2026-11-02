package dev.hermeskotlin.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.ui.HeraldTheme
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.ui.LocalAppSettings
import dev.hermeskotlin.ui.zoomAfter
import dev.hermeskotlin.ui.DesktopBack
import dev.hermeskotlin.ui.LocalDesktopBack
import dev.hermeskotlin.ui.LocalFocusHeld
import dev.hermeskotlin.ui.keepFocusHeld
import dev.hermeskotlin.ui.LocalShortcuts
import dev.hermeskotlin.ui.Shortcut
import dev.hermeskotlin.ui.ShortcutBus
import dev.hermeskotlin.ui.ShortcutHandler
import dev.hermeskotlin.ui.chat.ChatScreen
import dev.hermeskotlin.ui.chat.ChatTarget
import dev.hermeskotlin.ui.chat.ChatViewModel
import dev.hermeskotlin.ui.chat.ProvideReplySpeakerFor
import dev.hermeskotlin.ui.di.CHAT_WINDOW
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named

/**
 * One chat in a window of its own, as Hermes Desktop's session windows: compact, beside the main one, with the
 * chat's keys and nothing else. It runs its own [ChatHost], since the app's holds the main window's chat, and its
 * replies and questions notify as the main chat's do.
 */
@Composable
fun ChatWindow(
    /** The chat the window shows; it moves to another one through [onShown]. */
    shown: ChatTarget,
    onShown: (ChatTarget) -> Unit,
    koin: Koin,
    icon: Painter,
    mac: Boolean,
    /** Brings the main window forward (the chat's sidebar button, which this window hasn't room for). */
    onShowMain: () -> Unit,
    onPost: (DesktopNotice) -> Unit,
    onClose: () -> Unit,
    /** Goes up each time the chat is asked for again, which brings this window forward. */
    raise: Int = 0,
) {
    val shortcuts = remember { ShortcutBus() }
    val back = remember { DesktopBack() }
    // As big as the last chat window was left; where is the system's to pick, so two chats don't open one on the other.
    val saved = remember { WindowMemory.read(koin.get(), WindowMemory.CHAT_KEY) }
    val state = rememberWindowState(
        size = saved?.let { DpSize(it.width.dp, it.height.dp) } ?: DpSize(560.dp, 760.dp),
        position = WindowPosition.PlatformDefault,
    )
    WindowMemory.Remember(state, koin.get(), saved, WindowMemory.CHAT_KEY)
    // The chat's title as the gateway has it now: a rename, or the title it gives a new chat, shows on the window.
    var liveTitle by remember(shown) { mutableStateOf<String?>(null) }
    val title = liveTitle ?: shown.title ?: "Kyllene"
    Window(
        onCloseRequest = onClose,
        state = state,
        title = title,
        icon = icon,
        onPreviewKeyEvent = { event -> Shortcut.of(event, mac)?.let(shortcuts::fire) ?: false },
        onKeyEvent = { event ->
            when {
                event.type == KeyEventType.KeyDown && event.key == Key.Escape -> back.back() || shortcuts.fire(Shortcut.Escape)
                else -> Shortcut.unhandled(event, mac)?.let(shortcuts::fire) ?: false
            }
        },
    ) {
        // The window's own view models, gone with it.
        val owner = remember {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }
        // A chat window narrows to a phone's width at most: the composer and replies still fit.
        LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(380, 420) }
        LaunchedEffect(raise) {
            if (raise > 0) {
                window.toFront()
                window.requestFocus()
            }
        }
        val host = remember { ChatHost(koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get()) }
        val focused = remember { MutableStateFlow(false) }
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused) { focused.value = windowFocused }
        LaunchedEffect(host) { DesktopNotifier(host, koin.get(), focused, onPost).start(this) }
        val mainHost = koinInject<ChatHost>()
        DisposableEffect(owner) {
            onDispose {
                host.close()
                owner.viewModelStore.clear()
            }
        }
        val titleBar = DesktopTitleBar.rememberFor(window)
        val focusHeld = remember { mutableStateOf(false) }
        CompositionLocalProvider(
            LocalViewModelStoreOwner provides owner,
            LocalShortcuts provides shortcuts,
            LocalFocusHeld provides focusHeld,
            LocalDesktopBack provides back,
            LocalClipboard provides rememberPatientClipboard(),
        ) {
            val settingsStore = remember { koin.get<SettingsStore>() }
            ShortcutHandler { shortcut ->
                // The app's zoom works from this window too: it's one setting, so both windows follow.
                val zoom = settingsStore.settings.value?.let { zoomAfter(shortcut, it.zoom) }
                when {
                    shortcut == Shortcut.CloseWindow -> true.also { onClose() }
                    zoom != null -> true.also { settingsStore.update { it.copy(zoom = zoom) } }
                    else -> false
                }
            }
            HeraldTheme {
                val chat: ChatViewModel = koinViewModel(qualifier = named(CHAT_WINDOW)) { parametersOf(host) }
                // The app's own host leaves this chat to this window: the sidebar's watcher doesn't count it as
                // another. A chat begun here is held too, once the gateway has stored it.
                val chatState = chat.state.collectAsState().value
                LaunchedEffect(chatState.title) { liveTitle = chatState.title?.takeIf { it.isNotBlank() } }
                val storedId = chatState.storedSessionId ?: shown.storedSessionId
                // Renamed in the main window's list: the chat and this window's title follow.
                LaunchedEffect(storedId) {
                    mainHost.retitled.collect { (id, newTitle) -> if (id == storedId) chat.showTitle(newTitle) }
                }
                DisposableEffect(storedId) {
                    storedId?.let(mainHost::holdSideChat)
                    onDispose { storedId?.let(mainHost::releaseSideChat) }
                }
                val dark = Theme[colors][background].luminance() < 0.5f
                LaunchedEffect(dark) { titleBar?.dark(dark) }
                Column(Modifier.fillMaxSize().background(Theme[colors][background]).keepFocusHeld(focusHeld)) {
                    titleBar?.Content(icon, title)
                    // The app's zoom, under the title bar as in the main window.
                    val text = LocalDensity.current
                    val zoom = LocalAppSettings.current.zoom / 100f
                    CompositionLocalProvider(LocalDensity provides Density(text.density * zoom, text.fontScale)) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            ProvideReplySpeakerFor(shown) {
                                ChatScreen(
                                    target = shown,
                                    onOpenSidebar = onShowMain,
                                    onNewChat = { onShown(ChatTarget(shown.gateway, null, null, nonce = System.nanoTime(), profile = shown.profile)) },
                                    onOpenMenu = null,
                                    onOpenChat = { id, title -> onShown(ChatTarget(shown.gateway, id, title, profile = shown.profile)) },
                                    onSwitchProfile = {},
                                    viewModel = chat,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
