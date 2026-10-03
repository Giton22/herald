package dev.hermeskotlin.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PanelLeft
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.Wrench
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Zap
import com.composeunstyled.Text
import com.composeunstyled.TextInput
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.UnstyledTextField
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.core.chat.Attachment
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.models.displayModelName
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.CopyButton
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MarkdownText
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.heading
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surfaceElevated
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.display
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.ui.LocalAppSettings
import dev.hermeskotlin.ui.components.EmptyState
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ChatScreen(
    target: ChatTarget,
    onOpenSidebar: () -> Unit,
    onNewChat: () -> Unit,
    viewModel: ChatViewModel = koinViewModel(),
) {
    LaunchedEffect(target) { viewModel.open(target) }
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val picker = viewModel.picker.collectAsStateWithLifecycle().value
    val connection = viewModel.connectionState.collectAsStateWithLifecycle().value
    val connected = connection is ConnectionState.Connected
    var modelsOpen by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxSize().imePadding()) {
            TopBar(
                title = state.title?.takeIf { it.isNotBlank() } ?: target.title ?: "New chat",
                subtitle = when {
                    !connected -> "Offline · reconnecting"
                    state.attachment is Attachment.Attaching -> "Opening…"
                    else -> null
                },
                onOpenSidebar = onOpenSidebar,
                // Already on an untouched new chat: nothing to start over from.
                onNewChat = onNewChat.takeIf { target.storedSessionId != null || state.messages.isNotEmpty() },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !state.historyLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                    state.messages.isEmpty() && state.historyError != null ->
                        EmptyState(Lucide.CloudOff, "Couldn't load the conversation", state.historyError.orEmpty()) {
                            Button("Try again", onClick = viewModel::retry, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
                        }
                    state.messages.isEmpty() -> Greeting()
                    else -> Messages(state.messages)
                }
            }

            (state.attachment as? Attachment.Failed)?.let {
                Banner(it.message, actionLabel = "Retry", onAction = viewModel::retry)
            }
            state.error?.let { Banner(it, actionLabel = null, onAction = viewModel::dismissError) }
            AnimatedVisibility(visible = state.running && state.status != null) { StatusLine(state.status.orEmpty()) }

            if (state.inputRequests.isNotEmpty()) {
                InputRequestPanel(state.inputRequests, connected, onAnswer = viewModel::answer)
            } else {
                Composer(
                    viewModel = viewModel,
                    // Re-rolled per conversation, kept while a new chat gets its stored id.
                    placeholder = remember(target) {
                        (if (target.storedSessionId == null) NEW_CHAT_PROMPTS else FOLLOW_UP_PROMPTS).random()
                    },
                    state = state,
                    picker = picker,
                    connected = connected,
                    onOpenModels = { modelsOpen = true },
                )
            }
        }
    }

    ModelSheet(
        visible = modelsOpen,
        onDismiss = { modelsOpen = false },
        state = state,
        picker = picker,
        onRefresh = viewModel::loadModels,
        onSelectModel = {
            viewModel.selectModel(it)
            modelsOpen = false
        },
        onSelectEffort = { viewModel.setReasoningEffort(it.wire) },
        onFast = viewModel::setFast,
    )
    ModelConfirmDialog(
        pending = picker.confirm,
        onConfirm = { viewModel.selectModel(it, confirmed = true) },
        onDismiss = viewModel::dismissConfirm,
    )
}

@Composable
private fun TopBar(title: String, subtitle: String?, onOpenSidebar: () -> Unit, onNewChat: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoundButton(Lucide.PanelLeft, "Sessions", onClick = onOpenSidebar)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                style = Theme[typography][label],
                color = Theme[colors][textColor],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = Theme[typography][caption], color = Theme[colors][warning], maxLines = 1)
            }
        }
        RoundButton(Lucide.SquarePen, "New chat", onClick = { onNewChat?.invoke() }, enabled = onNewChat != null)
    }
}

/** A 44dp filled circle, the top bar's button style. */
@Composable
private fun RoundButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, enabled: Boolean = true) {
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp).clip(CircleShape).background(Theme[colors][surfaceElevated]).alpha(if (enabled) 1f else 0.4f),
        indication = rememberColoredIndication(Theme[colors][textColor]),
    ) {
        UnstyledIcon(icon, contentDescription = contentDescription, tint = Theme[colors][textColor], modifier = Modifier.size(20.dp))
    }
}

/** An empty chat shows the name, set in type: Hermes Desktop's splash idea without its wordmark asset. */
@Composable
private fun Greeting() {
    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Text(
            "Hermes",
            style = Theme[typography][display],
            color = Theme[colors][textTertiary],
            textAlign = TextAlign.Center,
        )
    }
}

/** Hermes Desktop's composer lines (`composer.newSessionPlaceholders` / `followUpPlaceholders`). */
private val NEW_CHAT_PROMPTS = listOf(
    "What are we building?",
    "Give Hermes a task",
    "What's on your mind?",
    "Describe what you need",
    "What should we tackle?",
    "Ask anything",
    "Start with a goal",
)

private val FOLLOW_UP_PROMPTS = listOf(
    "Send a follow-up",
    "Add more context",
    "Refine the request",
    "What's next?",
    "Keep it going",
    "Push it further",
    "Adjust or continue",
)

@Composable
private fun Messages(messages: List<ChatMessage>) {
    // Reversed layout keeps the newest message pinned to the bottom while a reply streams in.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val newest = messages.lastOrNull()?.key
    LaunchedEffect(newest) { if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0) }
    val awayFromBottom by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 600 } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.Bottom),
        ) {
            items(messages.asReversed(), key = { it.key }) { message ->
                when (message) {
                    is ChatMessage.User -> UserBubble(message)
                    is ChatMessage.Assistant -> AssistantReply(message)
                }
            }
        }
        AnimatedVisibility(
            visible = awayFromBottom,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        ) {
            UnstyledButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Theme[colors][surfaceElevated])
                    .border(1.dp, Theme[colors][stroke], CircleShape),
                indication = rememberColoredIndication(Theme[colors][textColor]),
            ) {
                UnstyledIcon(Lucide.ArrowDown, contentDescription = "Jump to latest", tint = Theme[colors][textColor], modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun UserBubble(message: ChatMessage.User) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SelectionContainer {
            Text(
                message.text,
                style = Theme[typography][body],
                color = Theme[colors][textColor],
                modifier = Modifier
                    .padding(start = 56.dp)
                    .widthIn(max = 560.dp)
                    .alpha(if (message.pending) 0.6f else 1f)
                    .background(Theme[colors][surfaceElevated], RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        if (message.queued) {
            Text("Queued · runs after the current turn", style = Theme[typography][caption], color = Theme[colors][textTertiary])
        }
    }
}

@Composable
private fun AssistantReply(message: ChatMessage.Assistant) {
    val settings = LocalAppSettings.current
    val showReasoning = settings.showReasoning && message.reasoning.isNotBlank()
    val showTools = settings.showToolActivity && message.tools.isNotEmpty()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showReasoning) Reasoning(message.reasoning, live = message.streaming && message.text.isEmpty())
        if (showTools) Tools(message.tools)
        when {
            message.text.isNotBlank() -> SelectionContainer { MarkdownText(message.text, streaming = message.streaming) }
            // One activity cue at a time: live reasoning and running tools already show their own.
            message.streaming && !showReasoning && !(showTools && message.tools.any { it.running }) -> Thinking()
        }
        when (message.outcome) {
            TurnOutcome.Error -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                UnstyledIcon(Lucide.CircleAlert, contentDescription = null, tint = Theme[colors][danger], modifier = Modifier.size(16.dp))
                Text(message.error ?: "The turn failed.", style = Theme[typography][bodySmall], color = Theme[colors][danger])
            }
            TurnOutcome.Interrupted -> Text("Stopped", style = Theme[typography][caption], color = Theme[colors][textTertiary])
            else -> Unit
        }
        if (!message.streaming && message.text.isNotBlank()) CopyButton(message.text)
    }
}

/** A tappable one-line header that opens to show more, shared by reasoning and tool activity. */
@Composable
private fun Disclosure(
    icon: @Composable () -> Unit,
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle).padding(vertical = 4.dp, horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Text(label, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary], maxLines = 1, overflow = TextOverflow.Ellipsis)
            UnstyledIcon(
                if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = Theme[colors][textTertiary],
                modifier = Modifier.size(14.dp),
            )
        }
        if (expanded) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Theme[colors][stroke], RoundedCornerShape(12.dp))
                    .background(Theme[colors][surface], RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) { content() }
        }
    }
}

@Composable
private fun Reasoning(text: String, live: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Disclosure(
        icon = {
            if (live) {
                Spinner(Modifier.size(14.dp))
            } else {
                UnstyledIcon(Lucide.Brain, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp))
            }
        },
        label = if (live) "Thinking…" else "Thought it through",
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Text(text.trim(), style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
    }
}

@Composable
private fun Tools(tools: List<ToolActivity>) {
    var expanded by remember { mutableStateOf(false) }
    val running = tools.lastOrNull { it.running }
    val names = tools.map { it.name }.distinct()
    val label = when {
        running != null -> running.detail?.lineSequence()?.firstOrNull()?.let { "${running.name} · $it" } ?: "Running ${running.name}"
        names.size <= 2 -> "Used ${names.joinToString(" and ")}"
        else -> "Used ${tools.size} tools"
    }
    Disclosure(
        icon = {
            if (running != null) {
                Spinner(Modifier.size(14.dp))
            } else {
                UnstyledIcon(Lucide.Wrench, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp))
            }
        },
        label = label,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tools.forEach { ToolRow(it) }
        }
    }
}

@Composable
private fun ToolRow(tool: ToolActivity) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 3.dp)) {
            when {
                tool.running -> Spinner(Modifier.size(14.dp))
                tool.summary != null || tool.durationSeconds != null ->
                    UnstyledIcon(Lucide.Check, contentDescription = null, tint = Theme[colors][success], modifier = Modifier.size(14.dp))
                else -> UnstyledIcon(Lucide.Wrench, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tool.name, style = Theme[typography][label], color = Theme[colors][textColor])
                tool.durationSeconds?.let {
                    Text(formatDuration(it), style = Theme[typography][caption], color = Theme[colors][textTertiary], modifier = Modifier.padding(top = 2.dp))
                }
            }
            val detail = tool.summary ?: tool.detail
            if (!detail.isNullOrBlank()) {
                Text(
                    detail.trim(),
                    style = Theme[typography][caption],
                    color = Theme[colors][textTertiary],
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatDuration(seconds: Double): String = when {
    seconds < 1 -> "<1s"
    seconds < 60 -> "${seconds.toInt()}s"
    else -> "${(seconds / 60).toInt()}m ${(seconds % 60).toInt()}s"
}

@Composable
private fun Thinking() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Spinner(Modifier.size(14.dp))
        Text("Thinking…", style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
    }
}

@Composable
private fun StatusLine(status: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spinner(Modifier.size(12.dp))
        Text(
            status,
            style = Theme[typography][caption],
            color = Theme[colors][textTertiary],
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Banner(message: String, actionLabel: String?, onAction: () -> Unit) {
    Surface(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            UnstyledIcon(Lucide.CircleAlert, contentDescription = null, tint = Theme[colors][danger], modifier = Modifier.size(16.dp))
            Text(
                message,
                style = Theme[typography][bodySmall],
                color = Theme[colors][textColor],
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            if (actionLabel != null) {
                Button(actionLabel, onClick = onAction, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            } else {
                IconButton(Lucide.X, contentDescription = "Dismiss", onClick = onAction)
            }
        }
    }
}

/**
 * One rounded card, Claude-app style: the text on top; beneath it the model pill on the left and a
 * round send (or stop) button on the right.
 */
@Composable
private fun Composer(
    viewModel: ChatViewModel,
    placeholder: String,
    state: ChatState,
    picker: ModelPickerState,
    connected: Boolean,
    onOpenModels: () -> Unit,
) {
    val hasText = viewModel.composer.text.isNotBlank()
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
            .clip(shape)
            .background(Theme[colors][surfaceElevated])
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
    ) {
        UnstyledTextField(
            state = viewModel.composer,
            textStyle = Theme[typography][body],
            textColor = Theme[colors][textColor],
            cursorBrush = SolidColor(Theme[colors][accent]),
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 8),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = 12.dp),
        ) {
            TextInput(
                placeholder = {
                    Text(
                        if (connected) placeholder else "Reconnecting to Hermes…",
                        style = Theme[typography][body],
                        color = Theme[colors][textTertiary],
                    )
                },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f)) { ModelPill(state, picker, onClick = onOpenModels) }
            val stop = state.running && !hasText
            val send = hasText && connected
            ComposerButton(
                icon = if (stop) Lucide.Square else Lucide.ArrowUp,
                contentDescription = if (stop) "Stop" else "Send",
                onClick = if (stop) viewModel::interrupt else viewModel::send,
                enabled = connected && (stop || hasText),
                highlighted = send,
                iconSize = if (stop) 18.dp else 22.dp,
            )
        }
    }
}

/** The composer's 44dp round buttons: a soft neutral fill, or the accent for a ready Send. */
@Composable
private fun ComposerButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean,
    highlighted: Boolean = false,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
) {
    val fill = if (highlighted) Theme[colors][accent] else Theme[colors][textColor].copy(alpha = 0.08f)
    val tint = when {
        highlighted -> Theme[colors][onAccent]
        enabled -> Theme[colors][textColor]
        else -> Theme[colors][textTertiary]
    }
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp).clip(CircleShape).background(fill),
        indication = rememberColoredIndication(tint),
    ) {
        UnstyledIcon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** "Opus 5.5 Medium" in a pill: the chat's model and thinking level; opens the model sheet. */
@Composable
private fun ModelPill(state: ChatState, picker: ModelPickerState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val selection = ModelSelection.of(state, picker.catalog)
    val model = selection.model ?: return
    val effort = state.effort(selection.option)
    val primary = Theme[colors][textColor]
    val secondary = Theme[colors][textTertiary]
    Row(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(Theme[colors][textColor].copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.fast == true) {
            UnstyledIcon(Lucide.Zap, contentDescription = "Fast mode", tint = Theme[colors][warning], modifier = Modifier.size(14.dp))
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = primary)) { append(displayModelName(model)) }
                if (effort != null) withStyle(SpanStyle(color = secondary)) { append("  ${effort.label}") }
            },
            style = Theme[typography][body],
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
