package dev.hermeskotlin.ui.assistant

import dev.hermeskotlin.ui.imeVisible
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.LassoSelect
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.ScanText
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.TextInput
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.UnstyledTextField
import com.composeunstyled.theme.ColorScheme
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.ThemeMode
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.LocalAccentPalette
import dev.hermeskotlin.designsystem.PureBlack
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.LocalCodeWrap
import dev.hermeskotlin.designsystem.components.LocalGlow
import dev.hermeskotlin.designsystem.components.glow
import dev.hermeskotlin.designsystem.components.MarkdownText
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.halo
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.hermesTheme
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onUserBubble
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.radiusXLarge
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.userBubble
import dev.hermeskotlin.ui.LocalAppSettings
import dev.hermeskotlin.ui.chat.AppMark
import dev.hermeskotlin.ui.chat.BarButton
import dev.hermeskotlin.ui.chat.BarStatus
import dev.hermeskotlin.ui.chat.ComposerButton
import dev.hermeskotlin.ui.chat.DictationButton
import dev.hermeskotlin.ui.chat.DockShadow
import dev.hermeskotlin.ui.chat.InputRequestPanel
import dev.hermeskotlin.ui.chat.ReplyHeader
import dev.hermeskotlin.ui.chat.SendButton
import dev.hermeskotlin.ui.chat.SendIcon
import dev.hermeskotlin.ui.chat.SentAttachments
import dev.hermeskotlin.ui.chat.StatusLine
import dev.hermeskotlin.ui.chat.StatusTone
import dev.hermeskotlin.ui.chat.bubbleGlow
import dev.hermeskotlin.ui.chat.chatStatus
import dev.hermeskotlin.ui.chat.connectionLabel
import dev.hermeskotlin.ui.chat.currentAction
import dev.hermeskotlin.ui.chat.rememberImageBitmap
import dev.hermeskotlin.ui.chat.userBubbleShape
import dev.hermeskotlin.ui.voice.DictationState
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import org.koin.compose.koinInject

/**
 * The assistant panel, drawn over whatever app was in front: a card at the bottom with the chat so far,
 * the screen it will ask about, and a composer. Tapping above the card closes it, as with any assistant.
 * Themed like the app, since it is Herald, whatever the app behind looks like.
 */
@Composable
fun AssistantPanel(
    model: AssistantPanelModel,
    /** Whether the app may record; without it the microphone asks first ([onAllowMicrophone]). */
    microphoneAllowed: Boolean,
    onOpenHerald: (storedSessionId: String?) -> Unit,
    onAllowMicrophone: () -> Unit,
    /** The system's assistant settings, where the screen is shared with the assistant or not. */
    onOpenScreenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val settings = koinInject<SettingsStore>().settings.collectAsState().value ?: return
    val dark = when (settings.theme) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val scheme = when {
        !dark -> ColorScheme.Light
        settings.pureBlack -> PureBlack
        else -> ColorScheme.Dark
    }
    val density = LocalDensity.current
    val palette = AccentPalette.named(settings.accent)
    hermesTheme(palette)(scheme) {
        CompositionLocalProvider(
            LocalAccentPalette provides palette,
            LocalAppSettings provides settings,
            LocalCodeWrap provides settings.wrapCode,
            LocalGlow provides settings.glow,
            LocalDensity provides Density(density.density, density.fontScale * settings.textSize.scale),
        ) {
            AssistantBackdrop(onClose) {
                val circling by model.circling.collectAsState()
                if (circling) {
                    val screen by model.screen.collectAsState()
                    CircleOverlay(screen, onCircled = model::circled, onCancel = model::cancelCircling)
                    MessengerEdge(model, Modifier.fillMaxSize())
                } else {
                    MessengerEdge(model, Modifier.fillMaxSize())
                    Card(model, microphoneAllowed, onOpenHerald, onAllowMicrophone, onOpenScreenSettings, onClose)
                }
            }
        }
    }
}

/** The dimmed screen behind the card, which closes the panel when tapped. */
@Composable
internal fun AssistantBackdrop(onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.18f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) { content() }
}

/** What the card shows, read off [AssistantPanelModel]; previews draw it from samples. */
internal data class AssistantCardState(
    val phase: AssistantPhase,
    val chat: ChatState = ChatState(),
    val connected: Boolean = false,
    /** What the status line says while the link is down. */
    val linkLabel: String = "",
    val screen: ScreenCapture = ScreenCapture(),
    val includeScreen: Boolean = false,
    val circled: ScreenContext? = null,
    val dictation: DictationState = DictationState(),
)

/** What the card does: [AssistantPanelModel]'s calls, or nothing in a preview. */
internal interface AssistantActions {
    val composer: TextFieldState
    fun send()
    fun stop()
    fun answer(request: InputRequest, result: JsonObject)
    fun toggleDictation()
    fun setIncludeScreen(include: Boolean)
    fun startCircling()
    fun dropCircled()
}

@Composable
private fun Card(
    model: AssistantPanelModel,
    microphoneAllowed: Boolean,
    onOpenHerald: (String?) -> Unit,
    onAllowMicrophone: () -> Unit,
    onOpenScreenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val phase by model.phase.collectAsState()
    val session by model.session.collectAsState()
    val chat by (session?.state ?: remember { emptyFlow() }).collectAsState(ChatState())
    val connection by model.connectionState.collectAsState()
    val screen by model.screen.collectAsState()
    val includeScreen by model.includeScreen.collectAsState()
    val circled by model.circledPart.collectAsState()
    val dictation by model.voice.dictation.collectAsState()
    val shows by model.shows.collectAsState()

    // Calling the assistant up is asking to talk: listen straight away, on every call-up, when the app may.
    if (phase == AssistantPhase.Ready) {
        LaunchedEffect(model, shows, microphoneAllowed) {
            if (microphoneAllowed && !chat.hasConversation && !dictation.active && model.claimListening(shows)) model.toggleDictation()
        }
    }
    val actions = remember(model) {
        object : AssistantActions {
            override val composer get() = model.composer
            override fun send() = model.send()
            override fun stop() = model.stop()
            override fun answer(request: InputRequest, result: JsonObject) = model.answer(request, result)
            override fun toggleDictation() = model.toggleDictation()
            override fun setIncludeScreen(include: Boolean) = model.setIncludeScreen(include)
            override fun startCircling() = model.startCircling()
            override fun dropCircled() = model.dropCircled()
        }
    }
    AssistantCard(
        state = AssistantCardState(
            phase, chat, connection is ConnectionState.Connected, connectionLabel(connection), screen, includeScreen, circled, dictation,
        ),
        actions = actions,
        microphoneAllowed = microphoneAllowed,
        onOpenHerald = { onOpenHerald(model.storedSessionId()) },
        onSignIn = { onOpenHerald(null) },
        onAllowMicrophone = onAllowMicrophone,
        onOpenScreenSettings = onOpenScreenSettings,
        onClose = onClose,
    )
}

/**
 * The card itself, a small chat page: the app's halo on its background, the chat's top bar with its status
 * line, replies under the Hermes header, and the chat's composer floating at the bottom.
 */
@Composable
internal fun AssistantCard(
    state: AssistantCardState,
    actions: AssistantActions,
    microphoneAllowed: Boolean,
    onOpenHerald: () -> Unit,
    onSignIn: () -> Unit,
    onAllowMicrophone: () -> Unit,
    onOpenScreenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    // Rounded as the composer is, the app's largest radius.
    val shape = RoundedCornerShape(topStart = Theme[radii][radiusXLarge], topEnd = Theme[radii][radiusXLarge])
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxCard = maxHeight * 0.75f
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxCard)
                .clip(shape)
                .background(Theme[colors][background], shape)
                .halo()
                .border(1.dp, Theme[colors][stroke], shape)
                // The card itself doesn't close the panel.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        ) {
            val connected = state.connected
            Header(
                status = when (state.phase) {
                    AssistantPhase.Loading -> null
                    AssistantPhase.SignedOut -> BarStatus("Not signed in", StatusTone.Neutral)
                    AssistantPhase.Ready -> chatStatus(state.chat, connected, state.linkLabel, place = null)
                },
                // A question from Hermes takes the composer's place, and circling with it; the bar keeps it then.
                onCircle = actions::startCircling.takeIf {
                    state.phase == AssistantPhase.Ready && state.chat.inputRequests.isNotEmpty() && state.screen.screenshot != null
                },
                onOpenHerald = onOpenHerald,
                onClose = onClose,
            )
            when (state.phase) {
                AssistantPhase.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner() }
                AssistantPhase.SignedOut -> SignedOut(onSignIn)
                AssistantPhase.Ready -> Ready(state, actions, connected, microphoneAllowed, onAllowMicrophone, onOpenScreenSettings)
            }
        }
    }
}

/** The chat's top bar in small: the mark, "Herald" over its status line, and Open and Close in a ringed pill. */
@Composable
private fun Header(status: BarStatus?, onCircle: (() -> Unit)?, onOpenHerald: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppMark(28.dp, glow = false)
        Column(Modifier.weight(1f)) {
            Text(
                "Herald",
                style = Theme[typography][body].copy(fontWeight = FontWeight.SemiBold, lineHeight = 19.sp),
                color = Theme[colors][textColor],
                maxLines = 1,
            )
            if (status != null && status.text.isNotBlank()) StatusLine(status)
        }
        val shape = RoundedCornerShape(Theme[radii][radiusMedium])
        Row(
            Modifier
                .clip(shape)
                .background(Theme[colors][surface], shape)
                .border(1.dp, Theme[colors][stroke], shape),
        ) {
            onCircle?.let { BarButton(Lucide.LassoSelect, "Circle part of the screen", onClick = it) }
            BarButton(Lucide.Maximize2, "Open in Herald", onClick = onOpenHerald)
            BarButton(Lucide.X, "Close", onClick = onClose)
        }
    }
}

@Composable
private fun SignedOut(onOpenHerald: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Sign in to a Hermes gateway in Herald to ask about your screen.",
            style = Theme[typography][body],
            color = Theme[colors][textSecondary],
        )
        Button("Open Herald", onClick = onOpenHerald, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ColumnScope.Ready(
    state: AssistantCardState,
    actions: AssistantActions,
    connected: Boolean,
    microphoneAllowed: Boolean,
    onAllowMicrophone: () -> Unit,
    onOpenScreenSettings: () -> Unit,
) {
    val chat = state.chat
    val messages = chat.messages.filter { it is ChatMessage.User || it is ChatMessage.Assistant }
    if (messages.isNotEmpty()) {
        val scroll = rememberScrollState()
        LaunchedEffect(messages.lastOrNull()?.let { (it as? ChatMessage.Assistant)?.text?.length ?: it.key }) {
            scroll.animateScrollTo(scroll.maxValue)
        }
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(scroll).padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            messages.forEach { message ->
                when (message) {
                    is ChatMessage.User -> Prompt(message)
                    is ChatMessage.Assistant -> if (message.text.isNotBlank()) Reply(message)
                    else -> Unit
                }
            }
        }
    }
    if (chat.running) {
        Row(
            Modifier.padding(start = 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The action beside it says it all; "Loading" on top would only be read twice.
            Spinner(Modifier.size(14.dp).clearAndSetSemantics {})
            Text(currentAction(chat), style = Theme[typography][caption], color = Theme[colors][textSecondary], maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    (chat.error ?: state.dictation.error)?.let {
        Text(it, style = Theme[typography][bodySmall], color = Theme[colors][danger], modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 10.dp))
    }

    if (chat.inputRequests.isNotEmpty()) {
        InputRequestPanel(chat.inputRequests, connected, onAnswer = actions::answer, onStop = actions::stop.takeIf { chat.running })
        return
    }
    Composer(state, actions, connected, microphoneAllowed, onAllowMicrophone, onOpenScreenSettings)
}

/** The user's prompt in the chat's bubble: the accent fill with its glow, the screen it took along above it. */
@Composable
private fun Prompt(message: ChatMessage.User) {
    val shape = userBubbleShape()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (message.attachments.isNotEmpty()) SentAttachments(message.attachments)
        Box(Modifier.fillMaxWidth(0.84f).wrapContentWidth(Alignment.End)) {
            Text(
                message.text,
                style = Theme[typography][body],
                color = Theme[colors][onUserBubble],
                modifier = Modifier
                    .glow(shape, bubbleGlow(Theme[colors][userBubble]))
                    .clip(shape)
                    .background(Theme[colors][userBubble], shape)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

/** A reply as the chat shows one: the mark and "Hermes" above the text. */
@Composable
private fun Reply(message: ChatMessage.Assistant) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ReplyHeader(message.timestamp.takeUnless { message.streaming })
        MarkdownText(message.text, streaming = message.streaming)
    }
}

/**
 * The chat's composer: what goes along on top (the screen or the part circled), the field, and a row of
 * buttons under it, circling on the left, the microphone and Send on the right.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(
    state: AssistantCardState,
    actions: AssistantActions,
    connected: Boolean,
    canDictate: Boolean,
    onAllowMicrophone: () -> Unit,
    onOpenScreenSettings: () -> Unit,
) {
    val chat = state.chat
    val screen = state.screen
    val circled = state.circled
    val dictation = state.dictation
    // The whole screen goes with the first question only.
    val includeScreen = state.includeScreen && !chat.hasConversation
    val hasText = actions.composer.text.isNotBlank()
    val shape = RoundedCornerShape(Theme[radii][radiusXLarge])
    var focused by remember { mutableStateOf(false) }
    // As in the chat: Back hides the keyboard but leaves the field focused, which lights the edge and brings the
    // keyboard back on the next relayout. Put away means done typing.
    val keyboardUp = imeVisible
    val focusManager = LocalFocusManager.current
    LaunchedEffect(keyboardUp) { if (!keyboardUp && focused) focusManager.clearFocus() }
    val tray: (@Composable () -> Unit)? = when {
        circled != null -> { { CircledChip(circled, onCircleAgain = actions::startCircling, onRemove = actions::dropCircled) } }
        !chat.hasConversation && screen.missed -> { { ScreenUnavailable(onOpenScreenSettings) } }
        !chat.hasConversation && !(screen.context.isEmpty && screen.pending == 0) -> {
            { ScreenChip(screen, included = state.includeScreen, onIncluded = actions::setIncludeScreen) }
        }
        else -> null
    }
    Column(
        Modifier
            .fillMaxWidth()
            .dropShadow(shape, DockShadow)
            .clip(shape)
            .background(Theme[colors][surface], shape)
            .border(1.dp, if (focused) Theme[colors][textTertiary] else Theme[colors][stroke], shape)
            .onFocusChanged { focused = it.hasFocus }
            .padding(start = 4.dp, end = 6.dp, top = if (tray == null) 14.dp else 8.dp, bottom = 6.dp),
    ) {
        tray?.let { Box(Modifier.padding(start = 4.dp, bottom = 10.dp)) { it() } }
        UnstyledTextField(
            state = actions.composer,
            textStyle = Theme[typography][body],
            textColor = Theme[colors][textColor],
            cursorBrush = SolidColor(Theme[colors][accent]),
            selectionColors = LocalTextSelectionColors.current,
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = 12.dp),
        ) {
            TextInput(
                placeholder = {
                    Text(
                        when {
                            dictation.recording -> "Listening…"
                            dictation.transcribing -> "Writing down what you said…"
                            !connected -> "Connecting to Hermes…"
                            circled != null -> "Ask about what you circled"
                            chat.hasConversation -> "Ask a follow-up"
                            includeScreen -> "Ask about your screen"
                            else -> "Ask Hermes"
                        },
                        style = Theme[typography][body],
                        color = Theme[colors][textTertiary],
                    )
                },
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Circling a part works from a screenshot, before the first question or after it.
            if (screen.screenshot != null && circled == null) {
                ComposerButton(Lucide.LassoSelect, "Circle part of the screen", onClick = actions::startCircling, enabled = true)
            }
            Spacer(Modifier.weight(1f))
            if (canDictate) {
                DictationButton(dictation, onClick = actions::toggleDictation, enabled = !chat.running || dictation.active)
            } else {
                DictationButton(dictation, onClick = onAllowMicrophone, enabled = true)
            }
            if (chat.running && !hasText) {
                SendButton(SendIcon.Stop, onClick = actions::stop, enabled = connected)
            } else {
                // Something shown is sendable alone: it asks "What's this?" or about the screen.
                SendButton(SendIcon.Send, onClick = actions::send, enabled = connected && (hasText || circled != null || includeScreen))
            }
        }
    }
}

/**
 * The screen, offered for the first question in the composer's tray: sent only once the user says yes.
 * Shows the screenshot's thumbnail and the app it came from either way.
 */
@Composable
private fun ScreenChip(screen: ScreenCapture, included: Boolean, onIncluded: (Boolean) -> Unit) {
    val context = screen.context
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Row(
        Modifier
            .clip(shape)
            .background(if (included) Theme[colors][accentSoft] else Theme[colors][surface2], shape)
            .border(if (included) 1.5.dp else 1.dp, if (included) Theme[colors][accentText] else Theme[colors][stroke], shape)
            .clickable(role = Role.Checkbox, onClickLabel = if (included) "Leave the screen out" else "Include the screen") { onIncluded(!included) }
            .padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = if (included) 0.dp else 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumb = screen.screenshot?.let { rememberImageBitmap(it, maxEdge = 160) }
        Box(
            Modifier.size(width = 28.dp, height = 44.dp).clip(RoundedCornerShape(Theme[radii][radiusMedium])).background(Theme[colors][surface]),
            contentAlignment = Alignment.Center,
        ) {
            when {
                thumb != null -> Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                screen.pending > 0 -> Spinner(Modifier.size(16.dp))
                else -> UnstyledIcon(Lucide.ScanText, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                if (included) "This screen" else "Include this screen?",
                style = Theme[typography][label].copy(fontWeight = FontWeight.Medium),
                color = Theme[colors][textColor],
            )
            Text(
                when {
                    screen.pending > 0 && context.isEmpty -> "Reading the screen…"
                    context.screenshot == null -> listOfNotNull(screen.app, "text only").joinToString(" · ")
                    else -> screen.app ?: "Screenshot"
                },
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (included) {
            IconButton(Lucide.X, contentDescription = "Leave the screen out", onClick = { onIncluded(false) })
        } else {
            Button("Add", onClick = { onIncluded(true) }, variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = Lucide.Plus)
        }
    }
}

/**
 * Android said the screen was coming and sent none of it: the assistant settings don't share the screen.
 * Says so and where to change it, instead of a chip that waits forever.
 */
@Composable
private fun ScreenUnavailable(onOpenSettings: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Row(
        Modifier
            .clip(shape)
            .background(Theme[colors][surface2], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 28.dp, height = 44.dp), contentAlignment = Alignment.Center) {
            UnstyledIcon(Lucide.ScanText, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text("Herald can't see the screen", style = Theme[typography][label].copy(fontWeight = FontWeight.Medium), color = Theme[colors][textColor])
            Text(
                "Turn on \"Use text from screen\"",
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button("Settings", onClick = onOpenSettings, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
    }
}

/** The part the user circled, held for their question: the crop as it is, to circle again or drop. */
@Composable
private fun CircledChip(part: ScreenContext, onCircleAgain: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Row(
        Modifier
            .clip(shape)
            .background(Theme[colors][accentSoft], shape)
            .border(1.5.dp, Theme[colors][accentText], shape)
            .padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val crop = part.screenshot?.let { rememberImageBitmap(it, maxEdge = 320) }
        Box(Modifier.heightIn(max = 56.dp).width(96.dp), contentAlignment = Alignment.Center) {
            // Whole, not cropped again: a wide strip should still read as the strip that was circled.
            if (crop != null) {
                Image(crop, contentDescription = "What you circled", contentScale = ContentScale.Fit, modifier = Modifier.clip(RoundedCornerShape(Theme[radii][radiusMedium])))
            } else {
                Spinner(Modifier.size(16.dp))
            }
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text("Circled", style = Theme[typography][label].copy(fontWeight = FontWeight.Medium), color = Theme[colors][textColor])
            part.app?.let {
                Text(it, style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(Lucide.LassoSelect, contentDescription = "Circle again", onClick = onCircleAgain)
        IconButton(Lucide.X, contentDescription = "Remove what you circled", onClick = onRemove)
    }
}
