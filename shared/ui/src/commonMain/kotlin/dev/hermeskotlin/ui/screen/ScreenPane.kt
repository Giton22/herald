package dev.hermeskotlin.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.MonitorOff
import com.composables.icons.lucide.MonitorPlay
import com.composables.icons.lucide.MonitorX
import com.composables.icons.lucide.MousePointerClick
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.TriangleAlert
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.screen.DisplayLease
import dev.hermeskotlin.core.screen.ScreenKey
import dev.hermeskotlin.core.screen.ScreenStage
import dev.hermeskotlin.core.screen.keysymForKey
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.components.plainTextClipEntry
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.KeepScreenOn
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.sessions.ListSpinner
import dev.hermeskotlin.ui.sessions.SubpageHeader
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * A bot's screen: the live picture of what the bot is doing, and the user's own hand on it.
 *
 * The gateway decides what reaches the screen, so the viewer is told whether the user may drive it and the
 * gateway has the last word; the pane says who holds it rather than holding the user's hand back.
 */
@Composable
fun ScreenPane(
    bot: Bot,
    gateway: SavedGateway,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScreenViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(gateway, bot.name) { viewModel.bind(gateway, bot.name) }
    // The screen goes away with the pane, so the gateway isn't left sending pictures nobody watches.
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    // The screen going dark stops the app, so the phone keeps its own awake while the bot's is watched.
    KeepScreenOn(true)
    ScreenView(
        state = state,
        title = bot.label,
        onBack = onBack,
        onStart = viewModel::start,
        onReopen = viewModel::reopen,
        onRefresh = viewModel::refresh,
        onTakeOver = viewModel::takeOver,
        onHandBack = viewModel::handBack,
        onViewerEvent = viewModel::viewerEvent,
        modifier = modifier,
    )
}

/**
 * The screen: who holds it, the picture or the reason there is none, and the keys.
 *
 * The picture is the bot's own desktop, drawn by the viewer; the user's taps go to the bot's screen
 * whatever this shows, because the viewer is the one that sends them.
 */
@Composable
internal fun ScreenView(
    state: ScreenUiState,
    title: String,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onReopen: () -> Unit,
    /** Asks the gateway again about the screen; only the pane's own button needs it. */
    onRefresh: () -> Unit = {},
    onTakeOver: () -> Unit,
    onHandBack: () -> Unit,
    /** What the viewer says about the screen. */
    onViewerEvent: (ScreenViewerEvent) -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * The screen itself. The app draws the live one; a preview draws a made-up picture instead, which is
     * what the screenshots show.
     */
    screen: @Composable (onHandle: (ScreenViewerHandle) -> Unit, area: Modifier) -> Unit =
        { onHandle, area ->
            ScreenViewer(
                streamUrl = state.streamUrl.orEmpty(),
                viewOnly = !state.holding,
                onHandle = onHandle,
                onEvent = onViewerEvent,
                modifier = area,
            )
        },
) {
    val stage = screenStage(state)
    // What the app can ask of the viewer: the keys go through it to the bot's screen.
    var handle by remember { mutableStateOf<ScreenViewerHandle?>(null) }
    Column(
        modifier
            .fillMaxSize()
            // The keys that have no character are the pane's own, wherever the focus is.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val keysym = screenKey(event.key)?.let { keysymForKey(it) } ?: return@onPreviewKeyEvent false
                press(handle, keysym)
                true
            },
    ) {
        SubpageHeader(title, onBack)
        // Who holds the screen is worth knowing even when there is nothing to watch yet.
        if (state.supported && state.status != null) {
            ScreenControl(state, onTakeOver, onHandBack)
        }
        state.error?.let { ScreenError(it) }
        // The keys are worth having only when there is a screen to type into. They sit above the picture, not
        // below it: on a phone this page opens in the sidebar's drawer, whose floating bottom bar covers the
        // foot of the page, and a field under it cannot be tapped.
        if (stage == ScreenPaneStage.Watching) {
            ScreenKeys(handle = { keysym -> press(handle, keysym) }, onCharacter = { ch -> type(handle, ch) })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // The viewer is there as soon as there is something to open: it is the one that says how the
            // screen is doing, so it cannot wait for the app to know that it is showing.
            state.streamUrl?.let {
                screen({ handle = it }, Modifier.fillMaxSize())
            }
            when (stage) {
                ScreenPaneStage.Unsupported -> EmptyState(
                    Lucide.MonitorX,
                    "This gateway has no Bot Screen",
                    "The gateway is too old to have one. Update Hermes on the gateway host and it will show up here.",
                )
                ScreenPaneStage.Looking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ListSpinner() }
                ScreenPaneStage.NotInstalled -> ScreenNotInstalled(state, onRefresh)
                ScreenPaneStage.Blocked -> EmptyState(
                    Lucide.TriangleAlert,
                    "The screen can't start",
                    state.status?.blocker ?: "The gateway would not say why.",
                    error = true,
                )
                ScreenPaneStage.Stopped -> EmptyState(
                    Lucide.MonitorOff,
                    "The screen is stopped",
                    "Start it to watch what the bot is doing.",
                    action = { Button("Start the screen", onClick = onStart, leadingIcon = Lucide.MonitorPlay) },
                )
                ScreenPaneStage.Opening -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ListSpinner() }
                ScreenPaneStage.Failed -> EmptyState(
                    Lucide.MonitorOff,
                    "The screen didn't open",
                    state.error ?: "The gateway is still running the screen. Open it again to watch it.",
                    action = { Button("Open it again", onClick = onReopen, leadingIcon = Lucide.MonitorPlay) },
                )
                ScreenPaneStage.Watching -> Unit
            }
        }
    }
}

/** A key on the bot's screen: pressed and released, which is what a keystroke is. */
private fun press(handle: ScreenViewerHandle?, keysym: Int) {
    handle?.key(keysym, true)
    handle?.key(keysym, false)
}

/** A character typed on the bot's screen. */
private fun type(handle: ScreenViewerHandle?, ch: Char) {
    val keysym = dev.hermeskotlin.core.screen.keysymForCharacter(ch)
    if (keysym != 0) press(handle, keysym)
}

/** Who holds the screen, and the button that changes it. */
@Composable
private fun ScreenControl(state: ScreenUiState, onTakeOver: () -> Unit, onHandBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                controlLabel(state.lease, state.holding),
                style = Theme[typography][body],
                color = Theme[colors][text],
            )
            Text(
                if (state.holding) {
                    "Your taps and keys go to the bot's screen."
                } else {
                    "Your taps and keys are ignored until you take over."
                },
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
            )
        }
        if (state.holding) {
            Button("Hand back", onClick = onHandBack, variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = Lucide.RotateCcw)
        } else {
            Button("Take over", onClick = onTakeOver, size = ButtonSize.Small, leadingIcon = Lucide.MousePointerClick)
        }
    }
}

/** What went wrong, in the gateway's own words. */
@Composable
private fun ScreenError(message: String) {
    Row(
        Modifier.fillMaxWidth().background(Theme[colors][surface]).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, style = Theme[typography][bodySmall], color = Theme[colors][danger])
    }
}

/** The screen isn't installed on the gateway host: the command to run there, and the way to copy it. */
@Composable
private fun ScreenNotInstalled(state: ScreenUiState, onRefresh: () -> Unit) {
    val status = state.status
    val command = status?.installCommand
    val missing = status?.missing.orEmpty()
    EmptyState(
        Lucide.Monitor,
        "The Bot Screen isn't installed",
        command ?: missing.joinToString("\n").ifEmpty { "Install the Bot Screen on the gateway host." },
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (command != null) CopyCommand(command)
                // The command is run on the gateway host, so the gateway is asked again afterwards.
                Button("Check again", onClick = onRefresh, leadingIcon = Lucide.RefreshCw)
            }
        },
    )
}

/** The command to run on the gateway host, and a button that copies it. */
@Composable
private fun CopyCommand(command: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Button(
        if (copied) "Copied" else "Copy the command",
        onClick = {
            scope.launch {
                clipboard.setClipEntry(plainTextClipEntry(command))
                copied = true
            }
        },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        leadingIcon = Lucide.Copy,
    )
}

/**
 * The keys, as one field.
 *
 * The phone's software keyboard and the desktop's own keys both type into it, so a character is a character
 * on the bot's screen; the keys with no character are the pane's own.
 */
@Composable
private fun ScreenKeys(handle: (keysym: Int) -> Unit, onCharacter: (ch: Char) -> Unit) {
    val state = remember { TextFieldState() }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { typed ->
            if (typed.isNotEmpty()) {
                typed.forEach { onCharacter(it) }
                // The field is a key, not a message: what was typed is gone once it is sent.
                state.clearText()
            }
        }
    }
    TextField(
        state = state,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        placeholder = "Type to send keys to the screen",
        // On the desktop the field takes the focus as it shows, so the keys work straight away.
        autofocus = true,
    )
}

/** What the pane shows for the screen's state. */
internal enum class ScreenPaneStage {
    /** The gateway has no Bot Screen at all. */
    Unsupported,

    /** The gateway hasn't answered yet. */
    Looking,

    /** The screen isn't installed on the gateway host. */
    NotInstalled,

    /** The gateway says the screen can't run. */
    Blocked,

    /** The screen is stopped. */
    Stopped,

    /** The viewer is opening the screen. */
    Opening,

    /** The screen could not be opened, or it closed again. */
    Failed,

    /** The screen is being watched. */
    Watching,
}

/** What the pane shows, given what the gateway last said. */
internal fun screenStage(state: ScreenUiState): ScreenPaneStage = when {
    !state.supported -> ScreenPaneStage.Unsupported
    state.status == null -> ScreenPaneStage.Looking
    !state.status.installed -> ScreenPaneStage.NotInstalled
    state.status.blocker != null -> ScreenPaneStage.Blocked
    !state.status.running -> ScreenPaneStage.Stopped
    state.stage == ScreenStage.Failed -> ScreenPaneStage.Failed
    state.stage == ScreenStage.Connected -> ScreenPaneStage.Watching
    state.stage == ScreenStage.Opening -> ScreenPaneStage.Opening
    else -> ScreenPaneStage.Looking
}

/** Who holds the screen, in the user's words. */
internal fun controlLabel(lease: DisplayLease?, holding: Boolean): String = when {
    holding -> "You are in control"
    lease?.heldByHuman == true -> "Someone else is in control"
    else -> "The bot is in control"
}

/** The named key a key event means, or null for a key that is only a character. */
internal fun screenKey(key: Key): ScreenKey? = when (key) {
    Key.Enter, Key.NumPadEnter -> ScreenKey.Enter
    Key.Backspace -> ScreenKey.Backspace
    Key.Tab -> ScreenKey.Tab
    Key.Escape -> ScreenKey.Escape
    Key.DirectionLeft -> ScreenKey.Left
    Key.DirectionRight -> ScreenKey.Right
    Key.DirectionUp -> ScreenKey.Up
    Key.DirectionDown -> ScreenKey.Down
    Key.MoveHome -> ScreenKey.Home
    Key.MoveEnd -> ScreenKey.End
    Key.PageUp -> ScreenKey.PageUp
    Key.PageDown -> ScreenKey.PageDown
    Key.Delete -> ScreenKey.Delete
    Key.Insert -> ScreenKey.Insert
    Key.F1 -> ScreenKey.F1
    Key.F2 -> ScreenKey.F2
    Key.F3 -> ScreenKey.F3
    Key.F4 -> ScreenKey.F4
    Key.F5 -> ScreenKey.F5
    Key.F6 -> ScreenKey.F6
    Key.F7 -> ScreenKey.F7
    Key.F8 -> ScreenKey.F8
    Key.F9 -> ScreenKey.F9
    Key.F10 -> ScreenKey.F10
    Key.F11 -> ScreenKey.F11
    Key.F12 -> ScreenKey.F12
    else -> null
}
