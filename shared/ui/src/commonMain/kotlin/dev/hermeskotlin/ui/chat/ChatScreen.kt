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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.hermeskotlin.designsystem.input
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.userBubble
import dev.hermeskotlin.designsystem.userBubbleStroke
import dev.hermeskotlin.designsystem.wordmark
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
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.composables.icons.lucide.Plus
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
import dev.hermeskotlin.core.chat.OutgoingAttachment
import dev.hermeskotlin.core.chat.extractReplyMedia
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
import kotlinx.coroutines.delay
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
    val attachments = viewModel.attachments.collectAsStateWithLifecycle().value
    val attachmentError = viewModel.attachmentError.collectAsStateWithLifecycle().value
    var modelsOpen by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<ViewerImage?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2_500)
            notice = null
        }
    }
    val attachmentPicker = rememberAttachmentPicker(onPicked = viewModel::addAttachments, onError = viewModel::showAttachmentError)

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
                    else -> CompositionLocalProvider(
                        LocalMediaLoader provides viewModel::loadMedia,
                        LocalOpenImage provides { viewing = it },
                        LocalNotice provides { notice = it },
                    ) {
                        Messages(state.messages, state.thinkingFrame)
                    }
                }
            }

            (state.attachment as? Attachment.Failed)?.let {
                Banner(it.message, actionLabel = "Retry", onAction = viewModel::retry)
            }
            state.error?.let { Banner(it, actionLabel = null, onAction = viewModel::dismissError) }
            attachmentError?.let { Banner(it, actionLabel = null, onAction = viewModel::dismissAttachmentError) }
            AnimatedVisibility(visible = state.running && state.status != null) { StatusLine(state.status.orEmpty()) }
            AnimatedVisibility(visible = notice != null) { NoticeLine(notice.orEmpty()) }

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
                    attachments = attachments,
                    onOpenModels = { modelsOpen = true },
                    onAttach = { attachOpen = true },
                )
            }
        }
    }

    AttachSheet(visible = attachOpen, onDismiss = { attachOpen = false }, picker = attachmentPicker)
    viewing?.let { image ->
        CompositionLocalProvider(LocalMediaLoader provides viewModel::loadMedia) {
            ImageViewer(image, onDismiss = { viewing = null })
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

/** Desktop's tab strip on a phone: the title in small spaced capitals over an accent rule, plain icons either side. */
@Composable
private fun TopBar(title: String, subtitle: String?, onOpenSidebar: () -> Unit, onNewChat: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BarButton(Lucide.PanelLeft, "Sessions", onClick = onOpenSidebar)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        title.uppercase(),
                        style = Theme[typography][label].copy(fontWeight = FontWeight.Bold, letterSpacing = 0.06.em),
                        color = Theme[colors][textColor],
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                    Box(Modifier.fillMaxWidth().height(2.dp).background(Theme[colors][accent]))
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = Theme[typography][caption], color = Theme[colors][warning], maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
            }
            BarButton(Lucide.SquarePen, "New chat", onClick = { onNewChat?.invoke() }, enabled = onNewChat != null)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Theme[colors][stroke]))
    }
}

/** A 44dp square icon button with no fill, the top bar's style. */
@Composable
private fun BarButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, enabled: Boolean = true) {
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(Theme[radii][radiusMedium])).alpha(if (enabled) 1f else 0.35f),
        indication = rememberColoredIndication(Theme[colors][textColor]),
    ) {
        UnstyledIcon(icon, contentDescription = contentDescription, tint = Theme[colors][textSecondary], modifier = Modifier.size(20.dp))
    }
}

/**
 * An empty chat is titled in heavy spaced capitals stretched to the column, Desktop's splash lettering
 * set in the system face rather than its bundled font.
 */
@Composable
private fun Greeting() {
    // Blue on light; near-white on dark, where the blue at this size glares.
    val color = if (Theme[colors][background].luminance() < 0.5f) Theme[colors][textColor].copy(alpha = 0.9f) else Theme[colors][accent]
    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        BasicText(
            "HERMES AGENT",
            style = Theme[typography][wordmark].copy(textAlign = TextAlign.Center),
            color = { color },
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 72.sp, stepSize = 1.sp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp),
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
private fun Messages(messages: List<ChatMessage>, thinkingFrame: String?) {
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
                    is ChatMessage.Assistant -> AssistantReply(message, thinkingFrame.takeIf { message.streaming })
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
                    .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
                    .background(Theme[colors][surfaceElevated])
                    .border(1.dp, Theme[colors][strokeStrong], RoundedCornerShape(Theme[radii][radiusMedium])),
                indication = rememberColoredIndication(Theme[colors][textColor]),
            ) {
                UnstyledIcon(Lucide.ArrowDown, contentDescription = "Jump to latest", tint = Theme[colors][textColor], modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Desktop's turn marker: the prompt in a full-width box with a tinted fill and outline; replies run bare beneath. */
@Composable
private fun UserBubble(message: ChatMessage.User) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .alpha(if (message.pending) 0.6f else 1f)
                .background(Theme[colors][userBubble], shape)
                .border(1.dp, Theme[colors][userBubbleStroke], shape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (message.attachments.isNotEmpty()) SentAttachments(message.attachments)
            if (message.text.isNotEmpty()) SelectionContainer {
                Text(message.text, style = Theme[typography][body], color = Theme[colors][textColor])
            }
        }
        if (message.queued) {
            Text("Queued · runs after the current turn", style = Theme[typography][caption], color = Theme[colors][textTertiary])
        }
    }
}

@Composable
private fun AssistantReply(message: ChatMessage.Assistant, thinkingFrame: String?) {
    val settings = LocalAppSettings.current
    val showReasoning = settings.showReasoning && message.reasoning.isNotBlank()
    val showTools = settings.showToolActivity && message.tools.isNotEmpty()
    // Pictures and files the reply delivered show as themselves, not as Markdown a renderer can't load.
    val (text, media) = remember(message.text) { extractReplyMedia(message.text) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showReasoning) Reasoning(message.reasoning, live = message.streaming && message.text.isEmpty(), thinkingFrame)
        if (showTools) Tools(message.tools)
        when {
            text.isNotBlank() -> SelectionContainer { MarkdownText(text, streaming = message.streaming) }
            // One activity cue at a time: live reasoning and running tools already show their own.
            message.streaming && media.isEmpty() && !showReasoning && !(showTools && message.tools.any { it.running }) -> Thinking(thinkingFrame)
        }
        if (media.isNotEmpty()) ReplyMediaList(media)
        when (message.outcome) {
            TurnOutcome.Error -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                UnstyledIcon(Lucide.CircleAlert, contentDescription = null, tint = Theme[colors][danger], modifier = Modifier.size(16.dp))
                Text(message.error ?: "The turn failed.", style = Theme[typography][bodySmall], color = Theme[colors][danger])
            }
            TurnOutcome.Interrupted -> Text("Stopped", style = Theme[typography][caption], color = Theme[colors][textTertiary])
            else -> Unit
        }
        if (!message.streaming && text.isNotBlank()) CopyButton(text)
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
            Modifier.clip(RoundedCornerShape(Theme[radii][radiusMedium])).clickable(onClick = onToggle).padding(vertical = 4.dp, horizontal = 2.dp),
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
                    .border(1.dp, Theme[colors][stroke], RoundedCornerShape(Theme[radii][radiusMedium]))
                    .background(Theme[colors][surface], RoundedCornerShape(Theme[radii][radiusMedium]))
                    .padding(12.dp),
            ) { content() }
        }
    }
}

@Composable
private fun Reasoning(text: String, live: Boolean, thinkingFrame: String?) {
    var expanded by remember { mutableStateOf(false) }
    Disclosure(
        icon = {
            if (live) {
                Spinner(Modifier.size(14.dp))
            } else {
                UnstyledIcon(Lucide.Brain, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp))
            }
        },
        // While live, the agent's own spinner frame ("(⌐■_■) formulating...") rather than a fixed word.
        label = if (live) thinkingFrame ?: "Thinking…" else "Thought it through",
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
private fun Thinking(thinkingFrame: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Spinner(Modifier.size(14.dp))
        Text(
            thinkingFrame ?: "Thinking…",
            style = Theme[typography][bodySmall],
            color = Theme[colors][textTertiary],
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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

/** A passing message, like where a file was saved. */
@Composable
private fun NoticeLine(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = Theme[typography][bodySmall],
            color = Theme[colors][textColor],
            modifier = Modifier
                .background(Theme[colors][surfaceElevated], RoundedCornerShape(Theme[radii][radiusMedium]))
                .border(1.dp, Theme[colors][strokeStrong], RoundedCornerShape(Theme[radii][radiusMedium]))
                .padding(horizontal = 16.dp, vertical = 8.dp),
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
 * Desktop's composer stood up for a phone: a flat outlined box, the text on top; beneath it a plain +,
 * the model and thinking level as quiet text, and the round send (or stop) button.
 */
@Composable
private fun Composer(
    viewModel: ChatViewModel,
    placeholder: String,
    state: ChatState,
    picker: ModelPickerState,
    connected: Boolean,
    attachments: List<OutgoingAttachment>,
    onOpenModels: () -> Unit,
    onAttach: () -> Unit,
) {
    // Attachments alone are sendable: the gateway gets Desktop's image prompt or the file references.
    val hasText = viewModel.composer.text.isNotBlank() || attachments.isNotEmpty()
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
            .clip(shape)
            .background(Theme[colors][input])
            .border(1.dp, if (focused) Theme[colors][textTertiary] else Theme[colors][strokeStrong], shape)
            .onFocusChanged { focused = it.hasFocus }
            .padding(start = 4.dp, end = 6.dp, top = if (attachments.isEmpty()) 14.dp else 8.dp, bottom = 6.dp),
    ) {
        if (attachments.isNotEmpty()) ComposerTray(attachments, onRemove = viewModel::removeAttachment)
        UnstyledTextField(
            state = viewModel.composer,
            textStyle = Theme[typography][body],
            textColor = Theme[colors][textColor],
            cursorBrush = SolidColor(Theme[colors][accent]),
            // UnstyledTextField defaults to unspecified colors, which hides the selection and its handles.
            selectionColors = LocalTextSelectionColors.current,
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
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ComposerButton(
                icon = Lucide.Plus,
                contentDescription = "Add photos or files",
                onClick = onAttach,
                enabled = attachments.size < OutgoingAttachment.MAX_COUNT,
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { ModelPill(state, picker, onClick = onOpenModels) }
            val stop = state.running && !hasText
            SendButton(
                stop = stop,
                onClick = if (stop) viewModel::interrupt else viewModel::send,
                enabled = connected && (stop || hasText),
            )
        }
    }
}

/** A plain square icon button inside the composer, like Desktop's +. */
@Composable
private fun ComposerButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, enabled: Boolean) {
    val tint = if (enabled) Theme[colors][textSecondary] else Theme[colors][textTertiary].copy(alpha = 0.5f)
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(Theme[radii][radiusMedium])),
        indication = rememberColoredIndication(tint),
    ) {
        UnstyledIcon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Desktop's round send: a disc in the text colour (white on dark) with the icon cut in the page colour. */
@Composable
private fun SendButton(stop: Boolean, onClick: () -> Unit, enabled: Boolean) {
    val fill = if (enabled) Theme[colors][textColor] else Theme[colors][textColor].copy(alpha = 0.12f)
    val tint = if (enabled) Theme[colors][background] else Theme[colors][textTertiary]
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp).clip(CircleShape).background(fill),
        indication = rememberColoredIndication(tint),
    ) {
        UnstyledIcon(
            if (stop) Lucide.Square else Lucide.ArrowUp,
            contentDescription = if (stop) "Stop" else "Send",
            tint = tint,
            modifier = Modifier.size(if (stop) 16.dp else 20.dp),
        )
    }
}

/** "Opus 5.5 ⌄  Medium ⌄" as quiet text, Desktop's composer selectors; opens the model sheet. */
@Composable
private fun ModelPill(state: ChatState, picker: ModelPickerState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val selection = ModelSelection.of(state, picker.catalog)
    val model = selection.model ?: return
    val effort = state.effort(selection.option)
    val tint = Theme[colors][textSecondary]
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.fast == true) {
            UnstyledIcon(Lucide.Zap, contentDescription = "Fast mode", tint = Theme[colors][warning], modifier = Modifier.size(14.dp))
        }
        Text(
            displayModelName(model),
            style = Theme[typography][bodySmall],
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        UnstyledIcon(Lucide.ChevronDown, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
        if (effort != null) {
            Text(
                effort.label,
                style = Theme[typography][bodySmall],
                color = tint,
                maxLines = 1,
                modifier = Modifier.padding(start = 10.dp),
            )
            UnstyledIcon(Lucide.ChevronDown, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
        }
    }
}
