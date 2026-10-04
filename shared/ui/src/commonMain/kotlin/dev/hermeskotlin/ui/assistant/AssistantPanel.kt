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
    /** Whether the app may record; without it the microphone sends the user to Herald to allow it. */
    microphoneAllowed: Boolean,
    onOpenHerald: (storedSessionId: String?) -> Unit,
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
                Card(model, microphoneAllowed, onOpenHerald, onClose)
            }
        }
    }
}

@Composable
private fun Card(
    model: AssistantPanelModel,
    microphoneAllowed: Boolean,
    onOpenHerald: (String?) -> Unit,
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
            Header(onOpenHerald = { onOpenHerald(model.storedSessionId()) }, onClose = onClose)
            when (phase) {
                AssistantPhase.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner() }
                AssistantPhase.SignedOut -> SignedOut(onOpenHerald = { onOpenHerald(null) })
                AssistantPhase.Ready -> Ready(model, microphoneAllowed, onOpenHerald)
            }
        }
    }
}

@Composable
private fun Header(onOpenHerald: () -> Unit, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Herald", style = Theme[typography][label], color = Theme[colors][textSecondary], modifier = Modifier.weight(1f))
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
private fun ColumnScope.Ready(model: AssistantPanelModel, microphoneAllowed: Boolean, onOpenHerald: (String?) -> Unit) {
    val session by model.session.collectAsState()
    val state by (session?.state ?: remember { emptyFlow() }).collectAsState(ChatState())
    val connection by model.connectionState.collectAsState()
    val screen by model.screen.collectAsState()
    val includeScreen by model.includeScreen.collectAsState()
    val dictation by model.voice.dictation.collectAsState()
    val connected = connection is ConnectionState.Connected

    // Calling the assistant up is asking to talk: listen straight away when the app may.
    LaunchedEffect(model, microphoneAllowed) {
        if (microphoneAllowed && !state.hasConversation && !dictation.active) model.toggleDictation()
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
                    is ChatMessage.User -> Prompt(message.text)
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
    if (includeScreen && !state.hasConversation) ScreenChip(screen, onRemove = { model.setIncludeScreen(false) })
    Composer(
        model = model,
        state = state,
        connected = connected,
        includeScreen = includeScreen,
        canDictate = microphoneAllowed,
        dictation = dictation,
        onAllowMicrophone = { onOpenHerald(null) },
    )
}

/** The user's prompt, small and boxed like the app's bubbles. */
@Composable
private fun Prompt(text: String) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
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

/** What goes along with the first question: the screenshot's thumbnail and the app it came from. */
@Composable
private fun ScreenChip(screen: ScreenCapture, onRemove: () -> Unit) {
    val context = screen.context
    if (context.isEmpty && screen.pending == 0) return
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Row(
        Modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .clip(shape)
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
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
            Text("This screen", style = Theme[typography][label], color = Theme[colors][textColor])
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
        IconButton(Lucide.X, contentDescription = "Don't include the screen", onClick = onRemove)
    }
}

@Composable
private fun Composer(
    model: AssistantPanelModel,
    state: ChatState,
    connected: Boolean,
    includeScreen: Boolean,
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
                            state.hasConversation -> "Ask a follow-up"
                            else -> "Ask about your screen"
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
            SendButton(SendIcon.Send, onClick = model::send, enabled = connected && (hasText || (includeScreen && !state.hasConversation)))
        }
    }
}
