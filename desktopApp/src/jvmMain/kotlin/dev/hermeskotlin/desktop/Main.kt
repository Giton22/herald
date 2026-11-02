package dev.hermeskotlin.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.LayoutDirection
import dev.hermeskotlin.core.chat.AppLink
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatLinks
import dev.hermeskotlin.core.chat.InputAnswers
import kotlinx.coroutines.launch
import dev.hermeskotlin.core.chat.ComposeDraft
import dev.hermeskotlin.core.chat.DraftStart
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.appDataDir
import dev.hermeskotlin.desktop.web.Jcef
import dev.hermeskotlin.ui.App
import dev.hermeskotlin.ui.DesktopBack
import dev.hermeskotlin.ui.LocalDesktopBack
import dev.hermeskotlin.ui.LocalFocusHeld
import dev.hermeskotlin.ui.QuickEntryHotkey
import dev.hermeskotlin.ui.keepFocusHeld
import dev.hermeskotlin.ui.LocalShortcuts
import dev.hermeskotlin.ui.Shortcut
import dev.hermeskotlin.ui.ShortcutBus
import dev.hermeskotlin.ui.ShortcutHandler
import dev.hermeskotlin.ui.chat.ChatTarget
import dev.hermeskotlin.ui.chat.dialogOwner
import dev.hermeskotlin.ui.di.sharedModules
import dev.hermeskotlin.ui.update.LocalUpdateInstaller
import dev.hermeskotlin.ui.useAppTextContextMenu
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.startKoin
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.io.File
import java.time.Instant
import java.awt.Frame
import java.awt.Taskbar
import java.awt.desktop.AppReopenedListener
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

private const val TITLE = "Kyllene"

/** ⌘ stands where Ctrl does elsewhere. */
private val mac = System.getProperty("os.name").orEmpty().startsWith("Mac")

/** A chat in a window of its own; [raise] goes up when it's asked for again. */
private data class PoppedChat(val target: ChatTarget, val key: Int, val raise: Int = 0)

fun main(args: Array<String>) {
    // An error that ends the app ends the process too: Compose exits only when the app closes normally, and the
    // gateway's socket would otherwise keep Herald running with no window, its tray icon and the one-Herald lock
    // still held, so the next launch could only ask it to come forward. What went wrong goes to crash.log.
    Thread.currentThread().setUncaughtExceptionHandler { _, error ->
        runCatching { File(appDataDir, "crash.log").appendText("${Instant.now()} ${error.stackTraceToString()}\n") }
        error.printStackTrace()
        exitProcess(1)
    }
    if (ClassArchive.recording) ClassArchive.record()
    // A `herald://` link Windows started this launch with: a notification's, or one from a browser.
    val launchLink = args.firstOrNull { it.startsWith("${AppLink.DESKTOP_SCHEME}:", ignoreCase = true) }
    // A second launch brings the running Herald forward, with its link, instead of opening another.
    val instance = SingleInstance.claim(appDataDir, launchLink) ?: exitProcess(0)
    val koin = startKoin { modules(sharedModules + desktopModule) }.koin
    useAppTextContextMenu()
    val settingsStore = koin.get<SettingsStore>()
    val links = koin.get<ChatLinks>()
    val saved = WindowMemory.read(koin.get())
    // The same mark as docs/logo.svg, for the title bar, the taskbar and the tray.
    val icon = Thread.currentThread().contextClassLoader.getResourceAsStream("herald.svg")!!.use { loadSvgPainter(it, Density(1f)) }
    WindowsToasts.register(icon.toAwtImage(Density(1f), LayoutDirection.Ltr, Size(256f, 256f)), appDataDir)
    // Pages met for the first time open as quickly as they do the second time.
    Warmup.start()
    // And launches after this one open sooner.
    ClassArchive.refreshLater()
    application(exitProcessOnExit = false) {
        val shortcuts = remember { ShortcutBus() }
        val back = remember { DesktopBack() }
        val settings = settingsStore.settings.collectAsState().value ?: AppSettings()
        var windowShown by remember { mutableStateOf(true) }
        var frame by remember { mutableStateOf<Frame?>(null) }
        val focused = remember { MutableStateFlow(false) }
        var quickOpen by remember { mutableStateOf(false) }
        val tray = rememberTrayState()
        // The chat the last notification was about, which a click on it opens.
        var lastNotice by remember { mutableStateOf<DesktopNotice?>(null) }

        fun show() {
            windowShown = true
            frame?.let {
                if (it.extendedState and Frame.ICONIFIED != 0) it.extendedState = it.extendedState and Frame.ICONIFIED.inv()
                // Raised once Compose has made it visible again, after this pass: a hidden window can't come forward.
                SwingUtilities.invokeLater {
                    it.toFront()
                    it.requestFocus()
                }
            }
        }

        val scope = rememberCoroutineScope()
        val host = remember { koin.get<ChatHost>() }

        /**
         * Follows a `herald://` link. A notification's Approve or Deny answers its approval where it waits, without
         * the window; one that can't be answered there any more (answered, or another chat open) opens its chat.
         */
        fun follow(link: String) {
            val answer = WindowsToasts.takeAnswer(link)
            if (answer != null) {
                val session = host.session.value?.takeIf { s ->
                    s.state.value.storedSessionId == answer.sessionId && s.state.value.inputRequests.any { it.id == answer.request.id }
                }
                if (session != null) {
                    scope.launch { session.answer(answer.request, InputAnswers.approval(answer.choice)) }
                    return
                }
                show()
                answer.sessionId?.let { links.open(it, title = null) }
                return
            }
            show()
            AppLink.parse(link)?.let(links::follow)
        }

        LaunchedEffect(Unit) {
            launchLink?.let(::follow)
            instance.listen { link -> SwingUtilities.invokeLater { if (link == null) show() else follow(link) } }
        }
        // A Mac's Dock icon brings back a window put away to the menu bar, and the app menu's Settings… opens Settings.
        LaunchedEffect(Unit) {
            if (!mac || !Desktop.isDesktopSupported()) return@LaunchedEffect
            val desktop = Desktop.getDesktop()
            runCatching { desktop.addAppEventListener(AppReopenedListener { SwingUtilities.invokeLater(::show) }) }
            // A herald:// link, which the app's Info.plist claims.
            runCatching { desktop.setOpenURIHandler { event -> SwingUtilities.invokeLater { follow(event.uri.toString()) } } }
            runCatching {
                desktop.setPreferencesHandler {
                    SwingUtilities.invokeLater {
                        show()
                        shortcuts.fire(Shortcut.Settings)
                    }
                }
            }
        }
        // Back from sleep (the clock jumped) or back at the window, a connection waiting out its backoff tries now.
        val connection = remember { koin.get<GatewayConnection>() }
        LaunchedEffect(Unit) {
            var last = System.currentTimeMillis()
            while (true) {
                delay(5_000)
                val now = System.currentTimeMillis()
                if (now - last > 20_000 && connection.state.value is ConnectionState.Reconnecting) connection.retry()
                last = now
            }
        }
        // Chats opened in windows of their own, each with its own key so closing one keeps the others' state.
        val chatWindows = remember { mutableStateListOf<PoppedChat>() }
        var nextWindow by remember { mutableStateOf(0) }
        val post: (DesktopNotice) -> Unit = { notice ->
            lastNotice = notice
            // Windows' own notification where it can be had (it has buttons and stays in the Action Center);
            // otherwise the tray's.
            if (!WindowsToasts.show(notice)) tray.sendNotification(Notification(notice.title, notice.body, Notification.Type.None))
            // The taskbar button flashes (the Dock icon bounces once) until the user comes back.
            if (!focused.value) runCatching {
                if (Taskbar.isTaskbarSupported()) {
                    val taskbar = Taskbar.getTaskbar()
                    val window = frame
                    when {
                        window != null && taskbar.isSupported(Taskbar.Feature.USER_ATTENTION_WINDOW) -> taskbar.requestWindowUserAttention(window)
                        taskbar.isSupported(Taskbar.Feature.USER_ATTENTION) -> taskbar.requestUserAttention(true, false)
                    }
                }
            }
        }
        LaunchedEffect(Unit) { DesktopNotifier(koin.get(), settingsStore, focused, post).start(this) }
        // Ctrl+Shift+Space from any app, while the setting is on.
        DisposableEffect(settings.quickEntry) {
            val hotkey = if (settings.quickEntry) GlobalHotkey.register { SwingUtilities.invokeLater { quickOpen = true } } else null
            // Off where Windows has none for us (another platform) is no news; held by another app, Settings says so.
            QuickEntryHotkey.taken = settings.quickEntry && hotkey == null && System.getProperty("os.name").orEmpty().startsWith("Windows")
            onDispose { hotkey?.unregister() }
        }

        Tray(
            icon = icon,
            state = tray,
            tooltip = TITLE,
            // A double-click on the icon, or a click on a notification: the window, at that notification's chat.
            onAction = {
                show()
                lastNotice?.sessionId?.let { links.open(it, lastNotice?.chatTitle) }
                lastNotice = null
            },
            menu = {
                Item("Open Kyllene", onClick = ::show)
                Item("New chat") {
                    show()
                    links.newChat()
                }
                Item("Quick entry", onClick = { quickOpen = true })
                Separator()
                Item("Quit Kyllene", onClick = ::exitApplication)
            },
        )

        val (placement, position, size) = WindowMemory.initial(saved, DpSize(1280.dp, 820.dp))
        // Only where there's a tray to come back from: without one, a hidden window could only be found by launching again.
        val toTray = settings.closeToTray && java.awt.SystemTray.isSupported()
        val windowState = rememberWindowState(placement = placement, position = position, size = size)
        WindowMemory.Remember(windowState, koin.get(), saved)
        Window(
            // Closed, it stays in the tray when the setting says so; otherwise Herald quits.
            onCloseRequest = { if (toTray) windowShown = false else exitApplication() },
            visible = windowShown,
            title = TITLE,
            icon = icon,
            state = windowState,
            // The app's shortcuts go before anything focused, so Ctrl+N is a new chat even from the composer.
            onPreviewKeyEvent = { event -> Shortcut.of(event, mac)?.let(shortcuts::fire) ?: false },
            onKeyEvent = { event ->
                when {
                    // Escape is the desktop's back; with nothing to close, it stops the reply.
                    event.type == KeyEventType.KeyDown && event.key == Key.Escape -> back.back() || shortcuts.fire(Shortcut.Escape)
                    // Typing or pasting that nothing focused took goes to the composer.
                    else -> Shortcut.unhandled(event, mac)?.let(shortcuts::fire) ?: false
                }
            },
        ) {
            dialogOwner = window
            frame = window
            // Narrow enough for Windows' snap layouts (a third of a screen), where the sidebar folds into a drawer;
            // past that, things would only be cut off.
            LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(500, 520) }
            val windowFocused = LocalWindowInfo.current.isWindowFocused
            LaunchedEffect(windowFocused, windowShown) {
                focused.value = windowFocused && windowShown
                // Back in the window, the user has seen what's new: a later tray click shouldn't jump to an old chat.
                if (focused.value) lastNotice = null
                if (focused.value && connection.state.value is ConnectionState.Reconnecting) connection.retry()
            }
            val titleBar = DesktopTitleBar.rememberFor(window)
            val focusHeld = remember { mutableStateOf(false) }
            CompositionLocalProvider(
                LocalShortcuts provides shortcuts,
                LocalDesktopBack provides back,
                LocalClipboard provides rememberPatientClipboard(),
                LocalFocusHeld provides focusHeld,
            ) {
                // Under the app's handlers, so a page open over the chat sees Ctrl+W first.
                ShortcutHandler { shortcut ->
                    when (shortcut) {
                        is Shortcut.PopOut -> {
                            val id = shortcut.target.storedSessionId ?: return@ShortcutHandler false
                            val open = chatWindows.indexOfFirst { it.target.storedSessionId == id }
                            if (open >= 0) chatWindows[open] = chatWindows[open].let { it.copy(raise = it.raise + 1) }
                            else chatWindows += PoppedChat(shortcut.target, nextWindow++)
                            true
                        }
                        Shortcut.CloseWindow -> {
                            if (toTray) windowShown = false else exitApplication()
                            true
                        }
                        else -> false
                    }
                }
                Box(Modifier.fillMaxSize().keepFocusHeld(focusHeld)) {
                    CompositionLocalProvider(LocalUpdateInstaller provides DesktopUpdater) {
                        App(
                            appVersion = System.getProperty("herald.version"),
                            releasesRepo = System.getProperty("herald.releasesRepo"),
                            onDarkTheme = { titleBar?.dark(it) },
                            titleBar = titleBar?.forScreens(),
                        )
                    }
                }
            }
        }

        for (popped in chatWindows) {
            key(popped.key) {
                ChatWindow(
                    shown = popped.target,
                    onShown = { next ->
                        val at = chatWindows.indexOfFirst { it.key == popped.key }
                        // A chat already in a window of its own comes forward there rather than open twice.
                        val other = next.storedSessionId?.let { id -> chatWindows.indexOfFirst { it.target.storedSessionId == id } } ?: -1
                        when {
                            other >= 0 -> chatWindows[other] = chatWindows[other].let { it.copy(raise = it.raise + 1) }
                            at >= 0 -> chatWindows[at] = chatWindows[at].copy(target = next)
                        }
                    },
                    koin = koin,
                    icon = icon,
                    mac = mac,
                    onShowMain = ::show,
                    onPost = post,
                    onClose = { chatWindows.removeAll { it.key == popped.key } },
                    raise = popped.raise,
                )
            }
        }

        QuickEntryWindow(
            visible = quickOpen,
            onDismiss = { quickOpen = false },
            onSend = { text, target ->
                quickOpen = false
                // The open chat takes it if it can; one it can't reach (Settings over it, still connecting) gets a new chat.
                if (target == QuickTarget.NewChat || !shortcuts.fire(Shortcut.Prompt(text))) {
                    links.newChat(ComposeDraft(text = text, start = DraftStart.Send))
                }
            },
        )
    }
    // The windows are gone; Chromium's helper processes go with the app, not after it.
    Jcef.shutdown()
    exitProcess(0)
}
