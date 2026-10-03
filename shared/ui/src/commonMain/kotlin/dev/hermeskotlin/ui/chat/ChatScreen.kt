package dev.hermeskotlin.ui.chat

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Wrench
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.Attachment
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
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
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.heading
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusFull
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.components.EmptyState
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ChatScreen(
    target: ChatTarget,
    onBack: () -> Unit,
    viewModel: ChatViewModel = koinViewModel(),
) {
    LaunchedEffect(target) { viewModel.open(target) }
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val connection = viewModel.connectionState.collectAsStateWithLifecycle().value
    val connected = connection is ConnectionState.Connected

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
                    else -> state.model
                },
                onBack = onBack,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !state.historyLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                    state.messages.isEmpty() && state.historyError != null ->
                        EmptyState(Lucide.CloudOff, "Couldn't load the conversation", state.historyError.orEmpty()) {
                            Button("Try again", onClick = viewModel::retry, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
                        }
                    state.messages.isEmpty() ->
                        EmptyState(Lucide.Sparkles, "Start a conversation", "Ask anything. The agent runs on your gateway with its own tools.")
                    else -> Messages(state.messages)
                }
            }

            (state.attachment as? Attachment.Failed)?.let {
                Banner(it.message, actionLabel = "Retry", onAction = viewModel::retry)
            }
            state.error?.let { Banner(it, actionLabel = null, onAction = viewModel::dismissError) }
            AnimatedVisibility(visible = state.running && state.status != null) { StatusLine(state.status.orEmpty()) }

            Composer(
                viewModel = viewModel,
                connected = connected,
                running = state.running,
            )
        }
    }
}

@Composable
private fun TopBar(title: String, subtitle: String?, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(Lucide.ArrowLeft, contentDescription = "Back", onClick = onBack)
        Column(Modifier.weight(1f).padding(start = 4.dp, end = 12.dp)) {
            Text(title, style = Theme[typography][heading], color = Theme[colors][textColor], maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Messages(messages: List<ChatMessage>) {
    // Reversed layout keeps the newest message pinned to the bottom while a reply streams in.
    val listState = rememberLazyListState()
    val newest = messages.lastOrNull()?.key
    LaunchedEffect(newest) { if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0) }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.Bottom),
    ) {
        items(messages.asReversed(), key = { it.key }) { message ->
            when (message) {
                is ChatMessage.User -> UserBubble(message)
                is ChatMessage.Assistant -> AssistantReply(message)
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
                    .padding(start = 48.dp)
                    .widthIn(max = 560.dp)
                    .alpha(if (message.pending) 0.6f else 1f)
                    .background(Theme[colors][accentSoft], RoundedCornerShape(Theme[radii][radiusLarge]))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        if (message.queued) {
            Text("Queued · runs after the current turn", style = Theme[typography][caption], color = Theme[colors][textTertiary])
        }
    }
}

@Composable
private fun AssistantReply(message: ChatMessage.Assistant) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (message.reasoning.isNotBlank()) Reasoning(message.reasoning, live = message.streaming && message.text.isEmpty())
        if (message.tools.isNotEmpty()) Tools(message.tools)
        when {
            message.text.isNotBlank() -> SelectionContainer { MarkdownText(message.text, streaming = message.streaming) }
            // One activity cue at a time: live reasoning and running tools already show their own.
            message.streaming && message.reasoning.isBlank() && message.tools.none { it.running } -> Thinking()
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

@Composable
private fun Reasoning(text: String, live: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.clickable { expanded = !expanded }.padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(Lucide.Brain, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
            Text(if (live) "Thinking…" else "Reasoning", style = Theme[typography][caption], color = Theme[colors][textTertiary])
            UnstyledIcon(
                if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                contentDescription = if (expanded) "Hide reasoning" else "Show reasoning",
                tint = Theme[colors][textTertiary],
                modifier = Modifier.size(14.dp),
            )
        }
        if (expanded) {
            Text(
                text.trim(),
                style = Theme[typography][bodySmall],
                color = Theme[colors][textSecondary],
                modifier = Modifier.padding(start = 20.dp),
            )
        }
    }
}

@Composable
private fun Tools(tools: List<ToolActivity>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tools.forEach { tool -> ToolRow(tool) }
    }
}

@Composable
private fun ToolRow(tool: ToolActivity) {
    val shape = RoundedCornerShape(Theme[radii][radiusFull])
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .border(1.dp, Theme[colors][stroke], shape)
                .background(Theme[colors][surface], shape)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                tool.running -> Spinner(Modifier.size(12.dp))
                tool.summary != null || tool.durationSeconds != null ->
                    UnstyledIcon(Lucide.Check, contentDescription = null, tint = Theme[colors][success], modifier = Modifier.size(12.dp))
                else -> UnstyledIcon(Lucide.Wrench, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(12.dp))
            }
            Text(tool.name, style = Theme[typography][caption], color = Theme[colors][textSecondary])
        }
        val detail = tool.summary ?: tool.detail
        if (!detail.isNullOrBlank()) {
            Text(
                detail.lineSequence().first(),
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
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
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
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

@Composable
private fun Composer(viewModel: ChatViewModel, connected: Boolean, running: Boolean) {
    val hasText = viewModel.composer.text.isNotBlank()
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            state = viewModel.composer,
            placeholder = if (connected) "Message Hermes" else "Offline · reconnecting…",
            singleLine = false,
            maxLines = 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
            modifier = Modifier.weight(1f),
        )
        val stop = running && !hasText
        val action: Pair<ImageVector, String> = if (stop) Lucide.Square to "Stop" else Lucide.ArrowUp to "Send"
        IconButton(
            icon = action.first,
            contentDescription = action.second,
            onClick = if (stop) viewModel::interrupt else viewModel::send,
            enabled = connected && (stop || hasText),
            tint = Theme[colors][onAccent],
            containerColor = Theme[colors][accent],
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}
