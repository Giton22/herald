package dev.hermeskotlin.ui.assistant

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
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
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.ThemeMode
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.PureBlack
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.accentSoft
import androidx.compose.ui.semantics.Role
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.LassoSelect
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MarkdownText
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surfaceElevated
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.userBubble
import dev.hermeskotlin.designsystem.userBubbleStroke
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.ui.LocalAppSettings
import dev.hermeskotlin.ui.chat.DictationButton
import dev.hermeskotlin.ui.chat.InputRequestPanel
import dev.hermeskotlin.ui.chat.SendButton
import dev.hermeskotlin.ui.chat.SendIcon
import dev.hermeskotlin.ui.chat.SentAttachments
import dev.hermeskotlin.ui.chat.currentAction
import dev.hermeskotlin.ui.chat.rememberImageBitmap
import dev.hermeskotlin.ui.voice.DictationState
import kotlinx.coroutines.flow.emptyFlow
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
    HermesTheme(scheme) {
        CompositionLocalProvider(
            LocalAppSettings provides settings,
            LocalDensity provides Density(density.density, density.fontScale * settings.textSize.scale),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val circling by model.circling.collectAsState()
                if (circling) {
                    val screen by model.screen.collectAsState()
                    CircleOverlay(screen, onCircled = model::circled, onCancel = model::cancelCircling)
                    MessengerEdge(model, Modifier.fillMaxSize())
                } else {
                    MessengerEdge(model, Modifier.fillMaxSize())
                    Card(model, microphoneAllowed, onOpenHerald, onAllowMicrophone, onClose)
                }
            }
        }
    }
}

@Composable
private fun Card(
    model: AssistantPanelModel,
    microphoneAllowed: Boolean,
    onOpenHerald: (String?) -> Unit,
    onAllowMicrophone: () -> Unit,
    onClose: () -> Unit,
) {
    val phase by model.phase.collectAsState()
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxCard = maxHeight * 0.75f
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxCard)
                .clip(shape)
                .background(Theme[colors][surfaceElevated], shape)
                .border(1.dp, Theme[colors][stroke], shape)
                // The card itself doesn't close the panel.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            // Before the first question the screen chip offers circling; after it, the header does.
            val session by model.session.collectAsState()
            val talking by (session?.state ?: remember { emptyFlow() }).collectAsState(ChatState())
            val screen by model.screen.collectAsState()
            Header(
                onCircle = model::startCircling.takeIf { screen.screenshot != null && talking.hasConversation },
                onOpenHerald = { onOpenHerald(model.storedSessionId()) },
                onClose = onClose,
            )
            when (phase) {
                AssistantPhase.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner() }
                AssistantPhase.SignedOut -> SignedOut(onOpenHerald = { onOpenHerald(null) })
                AssistantPhase.Ready -> Ready(model, microphoneAllowed, onAllowMicrophone)
            }
        }
    }
}

@Composable
private fun Header(onCircle: (() -> Unit)?, onOpenHerald: () -> Unit, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Herald", style = Theme[typography][label], color = Theme[colors][textSecondary], modifier = Modifier.weight(1f))
        onCircle?.let { IconButton(Lucide.LassoSelect, contentDescription = "Circle part of the screen", onClick = it) }
        IconButton(Lucide.Maximize2, contentDescription = "Open in Herald", onClick = onOpenHerald)
        IconButton(Lucide.X, contentDescription = "Close", onClick = onClose)
    }
}

@Composable
private fun SignedOut(onOpenHerald: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Sign in to a Hermes gateway in Herald to ask about your screen.",
            style = Theme[typography][body],
            color = Theme[colors][textColor],
        )
        Button("Open Herald", onClick = onOpenHerald)
    }
}

@Composable
private fun ColumnScope.Ready(model: AssistantPanelModel, microphoneAllowed: Boolean, onAllowMicrophone: () -> Unit) {
    val session by model.session.collectAsState()
    val state by (session?.state ?: remember { emptyFlow() }).collectAsState(ChatState())
    val connection by model.connectionState.collectAsState()
    val screen by model.screen.collectAsState()
    val includeScreen by model.includeScreen.collectAsState()
    val dictation by model.voice.dictation.collectAsState()
    val connected = connection is ConnectionState.Connected
    val shows by model.shows.collectAsState()

    // Calling the assistant up is asking to talk: listen straight away, on every call-up, when the app may.
    LaunchedEffect(model, shows, microphoneAllowed) {
        if (microphoneAllowed && !state.hasConversation && !dictation.active && model.claimListening(shows)) model.toggleDictation()
    }

    val messages = state.messages.filter { it is ChatMessage.User || it is ChatMessage.Assistant }
    if (messages.isNotEmpty()) {
        val scroll = rememberScrollState()
        LaunchedEffect(messages.lastOrNull()?.let { (it as? ChatMessage.Assistant)?.text?.length ?: it.key }) {
            scroll.animateScrollTo(scroll.maxValue)
        }
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(scroll).padding(end = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            messages.forEach { message ->
                when (message) {
                    is ChatMessage.User -> Prompt(message)
                    is ChatMessage.Assistant -> if (message.text.isNotBlank()) MarkdownText(message.text, streaming = message.streaming)
                    else -> Unit
                }
            }
        }
    }
    if (state.running) {
        Text(currentAction(state), style = Theme[typography][caption], color = Theme[colors][textTertiary], modifier = Modifier.padding(bottom = 6.dp))
    }
    (state.error ?: dictation.error)?.let {
        Text(it, style = Theme[typography][bodySmall], color = Theme[colors][danger], modifier = Modifier.padding(end = 8.dp, bottom = 6.dp))
    }

    if (state.inputRequests.isNotEmpty()) {
        InputRequestPanel(state.inputRequests, connected, onAnswer = model::answer, onStop = model::stop.takeIf { state.running })
        return
    }
    val circled by model.circledPart.collectAsState()
    when {
        circled != null -> CircledChip(circled!!, onCircleAgain = model::startCircling, onRemove = model::dropCircled)
        !state.hasConversation -> ScreenChip(screen, included = includeScreen, onIncluded = model::setIncludeScreen, onCircle = model::startCircling)
    }
    Composer(
        model = model,
        state = state,
        connected = connected,
        includeScreen = includeScreen && !state.hasConversation,
        circled = circled != null,
        canDictate = microphoneAllowed,
        dictation = dictation,
        onAllowMicrophone = onAllowMicrophone,
    )
}

/** The user's prompt, boxed like the app's bubbles, with the screen it took along above it. */
@Composable
private fun Prompt(message: ChatMessage.User) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (message.attachments.isNotEmpty()) SentAttachments(message.attachments)
        Text(
            message.text,
            style = Theme[typography][body],
            color = Theme[colors][textColor],
            modifier = Modifier
                .clip(shape)
                .background(Theme[colors][userBubble], shape)
                .border(1.dp, Theme[colors][userBubbleStroke], shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * The screen, offered for the first question: the panel asks whether to include it and sends it only
 * once the user says yes. Shows the screenshot's thumbnail and the app it came from either way.
 */
@Composable
private fun ScreenChip(screen: ScreenCapture, included: Boolean, onIncluded: (Boolean) -> Unit, onCircle: () -> Unit) {
    val context = screen.context
    if (context.isEmpty && screen.pending == 0) return
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Row(
        Modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .clip(shape)
            .background(if (included) Theme[colors][accentSoft] else Theme[colors][surface], shape)
            .border(1.dp, if (included) Theme[colors][accent] else Theme[colors][stroke], shape)
            .clickable(role = Role.Checkbox, onClickLabel = if (included) "Leave the screen out" else "Include the screen") { onIncluded(!included) }
            .padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = if (included) 0.dp else 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumb = screen.screenshot?.let { rememberImageBitmap(it, maxEdge = 160) }
        Box(Modifier.size(width = 28.dp, height = 44.dp).clip(RoundedCornerShape(Theme[radii][radiusMedium])), contentAlignment = Alignment.Center) {
            when {
                thumb != null -> Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                screen.pending > 0 -> Spinner(Modifier.size(16.dp))
                else -> UnstyledIcon(Lucide.ScanText, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(if (included) "This screen" else "Include this screen?", style = Theme[typography][label], color = Theme[colors][textColor])
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
            // Or just a part of it: circle what to ask about.
            if (screen.screenshot != null) {
                Button("Circle", onClick = onCircle, variant = ButtonVariant.Ghost, size = ButtonSize.Small, leadingIcon = Lucide.LassoSelect)
            }
        }
    }
}

/** The part the user circled, held for their question: the crop as it is, to circle again or drop. */
@Composable
private fun CircledChip(part: ScreenContext, onCircleAgain: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Row(
        Modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .clip(shape)
            .background(Theme[colors][accentSoft], shape)
            .border(1.dp, Theme[colors][accent], shape)
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
            Text("Circled", style = Theme[typography][label], color = Theme[colors][textColor])
            part.app?.let {
                Text(it, style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(Lucide.LassoSelect, contentDescription = "Circle again", onClick = onCircleAgain)
        IconButton(Lucide.X, contentDescription = "Remove what you circled", onClick = onRemove)
    }
}

@Composable
private fun Composer(
    model: AssistantPanelModel,
    state: ChatState,
    connected: Boolean,
    /** The whole screen goes with the next question. */
    includeScreen: Boolean,
    /** A circled part goes with the next question. */
    circled: Boolean,
    canDictate: Boolean,
    dictation: DictationState,
    onAllowMicrophone: () -> Unit,
) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    val hasText = model.composer.text.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(end = 8.dp)
            .clip(shape)
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledTextField(
            state = model.composer,
            textStyle = Theme[typography][body],
            textColor = Theme[colors][textColor],
            cursorBrush = SolidColor(Theme[colors][accent]),
            selectionColors = LocalTextSelectionColors.current,
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.weight(1f).heightIn(min = 24.dp),
        ) {
            TextInput(
                placeholder = {
                    Text(
                        when {
                            dictation.recording -> "Listening…"
                            dictation.transcribing -> "Writing down what you said…"
                            !connected -> "Connecting to Hermes…"
                            circled -> "Ask about what you circled"
                            state.hasConversation -> "Ask a follow-up"
                            includeScreen -> "Ask about your screen"
                            else -> "Ask Hermes"
                        },
                        style = Theme[typography][body],
                        color = Theme[colors][textTertiary],
                    )
                },
            )
        }
        if (canDictate) {
            DictationButton(dictation, onClick = model::toggleDictation, enabled = !state.running || dictation.active)
        } else {
            DictationButton(dictation, onClick = onAllowMicrophone, enabled = true)
        }
        Spacer(Modifier.width(2.dp))
        if (state.running && !hasText) {
            SendButton(SendIcon.Stop, onClick = model::stop, enabled = connected)
        } else {
            // Something shown is sendable alone: it asks "What's this?" or about the screen.
            SendButton(SendIcon.Send, onClick = model::send, enabled = connected && (hasText || circled || includeScreen))
        }
    }
}
