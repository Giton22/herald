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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.EllipsisVertical
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.strokeStrong
import dev.hermeskotlin.designsystem.userBubble
import dev.hermeskotlin.designsystem.userBubbleStroke
import dev.hermeskotlin.designsystem.wordmark
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
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
import com.composables.icons.lucide.ListEnd
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PanelLeft
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.SearchCheck
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.SquareTerminal
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
import dev.hermeskotlin.core.chat.SendCheck
import dev.hermeskotlin.core.chat.extractReplyMedia
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.chat.TodoList
import dev.hermeskotlin.core.chat.TodoStatus
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.core.chat.compactCount
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.models.displayModelName
import dev.hermeskotlin.core.slash.SlashCommand
import dev.hermeskotlin.core.slash.SlashKind
import dev.hermeskotlin.core.slash.SlashSuggestion
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.ui.journey.JourneySheet
import dev.hermeskotlin.core.pet.PetSprite
import dev.hermeskotlin.ui.pet.PetSheet
import dev.hermeskotlin.ui.pet.PetView
import dev.hermeskotlin.ui.pet.rememberPetState
import dev.hermeskotlin.ui.voice.DictationState
import kotlin.math.sqrt
import dev.hermeskotlin.ui.voice.VoiceChatState
import dev.hermeskotlin.ui.voice.VoicePhase
import dev.hermeskotlin.ui.voice.rememberMicrophonePermission
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.composables.icons.lucide.AudioLines
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Paperclip
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.CopyButton
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.DropdownMenu
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MenuAction
import dev.hermeskotlin.designsystem.components.plainTextClipEntry
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.GitBranch
import com.composables.icons.lucide.Pencil
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
    /** The open chat's options; null until it exists on the gateway. */
    onOpenMenu: (() -> Unit)?,
    /** Opens another stored chat by id and title (`/resume`, `/branch`). */
    onOpenChat: (String, String?) -> Unit,
    /** Switches profile (`/profile`); null is the gateway's launch profile. */
    onSwitchProfile: (String?) -> Unit,
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
    var petsOpen by remember { mutableStateOf(false) }
    var journeyOpen by remember { mutableStateOf(false) }
    var usageOpen by remember { mutableStateOf(false) }
    var processesOpen by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<ViewerImage?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2_500)
            notice = null
        }
    }
    val attachmentPicker = rememberAttachmentPicker(onPicked = viewModel::addAttachments, onError = viewModel::showAttachmentError)
    val microphone = rememberMicrophonePermission()
    val startVoiceChat = {
        microphone.withMicrophone(onDenied = { notice = "Voice needs the microphone. Allow it in Android's settings." }) {
            viewModel.startVoiceChat()
        }
    }
    val toggleDictation = {
        microphone.withMicrophone(onDenied = { notice = "Dictation needs the microphone. Allow it in Android's settings." }) {
            viewModel.toggleDictation()
        }
    }
    // Recording stops when the app leaves the screen; Android doesn't let it listen from the background.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.voice.stopAll() }
    LaunchedEffect(viewModel) {
        viewModel.requests.collect { request ->
            when (request) {
                ChatRequest.NewChat -> onNewChat()
                ChatRequest.PickModel -> modelsOpen = true
                ChatRequest.BrowseSessions -> onOpenSidebar()
                is ChatRequest.OpenChat -> onOpenChat(request.storedSessionId, request.title)
                is ChatRequest.SwitchProfile -> onSwitchProfile(request.profile)
                ChatRequest.OpenPets -> petsOpen = true
                ChatRequest.OpenJourney -> journeyOpen = true
                ChatRequest.OpenUsage -> usageOpen = true
                ChatRequest.OpenProcesses -> processesOpen = true
                ChatRequest.StartVoice -> startVoiceChat()
            }
        }
    }

    ChatView(
        title = state.title?.takeIf { it.isNotBlank() } ?: target.title ?: "New chat",
        state = state,
        picker = picker,
        connected = connected,
        connectionLabel = connectionLabel(connection),
        attachments = attachments,
        attachmentError = attachmentError,
        voiceChat = viewModel.voice.chat.collectAsStateWithLifecycle().value,
        dictation = viewModel.voice.dictation.collectAsStateWithLifecycle().value,
        suggestions = viewModel.suggestions.collectAsStateWithLifecycle().value,
        sprite = viewModel.pets.sprite.collectAsStateWithLifecycle().value,
        // Re-rolled per conversation, kept while a new chat gets its stored id.
        placeholder = remember(target) { (if (target.storedSessionId == null) NEW_CHAT_PROMPTS else FOLLOW_UP_PROMPTS).random() },
        notice = notice,
        actions = viewModel,
        onOpenSidebar = onOpenSidebar,
        // Already on an untouched new chat: nothing to start over from.
        onNewChat = onNewChat.takeIf { target.storedSessionId != null || state.messages.isNotEmpty() },
        onOpenMenu = onOpenMenu,
        onOpenModels = { modelsOpen = true },
        onAttach = { attachOpen = true },
        onDictate = toggleDictation,
        onVoiceChat = startVoiceChat,
        onOpenPets = { petsOpen = true },
        onViewImage = { viewing = it },
        onNotice = { notice = it },
    )

    AttachSheet(visible = attachOpen, onDismiss = { attachOpen = false }, picker = attachmentPicker)
    PetSheet(
        visible = petsOpen,
        controller = viewModel.pets,
        onDismiss = { petsOpen = false },
        onAdopted = { name ->
            viewModel.showPet()
            petsOpen = false
            notice = "Adopted $name"
        },
    )
    JourneySheet(visible = journeyOpen, controller = viewModel.journey, onLoad = viewModel::loadJourney, onDismiss = { journeyOpen = false })
    UsageSheet(
        visible = usageOpen,
        controller = viewModel.usage,
        live = state.usage,
        onLoad = viewModel::loadUsage,
        onDismiss = { usageOpen = false },
    )
    ProcessesSheet(
        visible = processesOpen,
        controller = viewModel.processes,
        onStart = viewModel::watchProcesses,
        onKill = viewModel::killProcess,
        onDismiss = { processesOpen = false },
    )
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

/**
 * The chat as it looks: top bar, conversation, and the dock with the composer or the agent's question.
 * Stateless, so previews can draw it from sample data; [ChatScreen] feeds it the live chat.
 */
@Composable
internal fun ChatView(
    title: String,
    state: ChatState,
    picker: ModelPickerState,
    connected: Boolean,
    /** Which way the link is down, shown under the title while not [connected]. */
    connectionLabel: String = "No connection",
    attachments: List<OutgoingAttachment>,
    attachmentError: String?,
    voiceChat: VoiceChatState,
    dictation: DictationState,
    suggestions: List<SlashSuggestion>,
    sprite: PetSprite?,
    placeholder: String,
    notice: String?,
    actions: ChatActions,
    onOpenSidebar: () -> Unit,
    onNewChat: (() -> Unit)?,
    onOpenMenu: (() -> Unit)?,
    onOpenModels: () -> Unit,
    onAttach: () -> Unit,
    onDictate: () -> Unit,
    onVoiceChat: () -> Unit,
    onOpenPets: () -> Unit,
    onViewImage: (ViewerImage) -> Unit,
    onNotice: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxSize().imePadding()) {
            TopBar(
                title = title,
                subtitle = when {
                    !connected -> connectionLabel
                    state.attachment is Attachment.Attaching -> "Opening…"
                    else -> null
                },
                onOpenSidebar = onOpenSidebar,
                onNewChat = onNewChat,
                onOpenMenu = onOpenMenu,
            )
            // The composer floats over the conversation, which scrolls on beneath it; its height pads the list.
            var dockHeight by remember { mutableIntStateOf(0) }
            val dockInset = with(LocalDensity.current) { dockHeight.toDp() }
            // What scrolls under the composer is captured here and frosted behind it.
            val hazeState = rememberHazeState()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                    if (state.historyLoaded && state.messages.isNotEmpty()) {
                        CompositionLocalProvider(
                            LocalMediaLoader provides actions::loadMedia,
                            LocalOpenImage provides onViewImage,
                            LocalNotice provides onNotice,
                            LocalSubagents provides SubagentContext(state.subagents, actions::stopSubagent),
                        ) {
                            Messages(
                                state.messages,
                                bottomInset = dockInset,
                                actions = actions,
                                connected = connected,
                                canChange = state.canChangeChat(connected),
                            )
                        }
                    } else Box(Modifier.fillMaxSize().padding(bottom = dockInset)) {
                        when {
                            !state.historyLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                            state.historyError != null ->
                                EmptyState(Lucide.CloudOff, "Couldn't load the conversation", state.historyError.orEmpty()) {
                                    Button("Try again", onClick = actions::retry, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
                                }
                            else -> Greeting(onAttach = onAttach, onDictate = onDictate, connected = connected, dictation = dictation)
                        }
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { dockHeight = it.height }) {
                    Dock(
                        hazeState = hazeState,
                        actions = actions,
                        placeholder = placeholder,
                        state = state,
                        picker = picker,
                        connected = connected,
                        attachments = attachments,
                        attachmentError = attachmentError,
                        voiceChat = voiceChat,
                        dictation = dictation,
                        suggestions = suggestions,
                        notice = notice,
                        onOpenModels = onOpenModels,
                        onAttach = onAttach,
                        onDictate = onDictate,
                        onVoiceChat = onVoiceChat,
                    )
                }
                // The pet sits on the composer's top edge, over the conversation rather than in the dock's height.
                if (sprite != null && LocalAppSettings.current.showPet && state.inputRequests.isEmpty()) {
                    PetView(
                        sprite = sprite,
                        state = rememberPetState(state),
                        onClick = onOpenPets,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = (dockInset - 6.dp).coerceAtLeast(0.dp)),
                    )
                }
            }
        }
    }
}

/** What floats at the bottom of the chat: banners, the live status, and the composer or the agent's question. */
@Composable
private fun ColumnScope.Dock(
    hazeState: HazeState,
    actions: ChatActions,
    placeholder: String,
    state: ChatState,
    picker: ModelPickerState,
    connected: Boolean,
    attachments: List<OutgoingAttachment>,
    attachmentError: String?,
    voiceChat: VoiceChatState,
    dictation: DictationState,
    suggestions: List<SlashSuggestion>,
    notice: String?,
    onOpenModels: () -> Unit,
    onAttach: () -> Unit,
    onDictate: () -> Unit,
    onVoiceChat: () -> Unit,
) {
    (state.attachment as? Attachment.Failed)?.let {
        Banner(it.message, actionLabel = "Retry", onAction = actions::retry)
    }
    state.error?.let { Banner(it, actionLabel = null, onAction = actions::dismissError) }
    attachmentError?.let { Banner(it, actionLabel = null, onAction = actions::dismissAttachmentError) }
    voiceChat.error?.let { Banner(it, actionLabel = null, onAction = actions::dismissVoiceChatError) }
    dictation.error?.let { Banner(it, actionLabel = null, onAction = actions::dismissDictationError) }
    AnimatedVisibility(visible = notice != null) { NoticeLine(notice.orEmpty()) }
    // One place says what's happening: the current action, with the status text and plan folded beneath.
    AnimatedVisibility(visible = state.running, enter = fadeIn(), exit = fadeOut()) {
        ProgressPanel(currentAction(state), state.status, state.runningTool(), state.livePlan(), hazeState)
    }
    if (!state.running) TodoPanel(state.todos, live = state.todosLive, hazeState = hazeState)

    if (state.inputRequests.isNotEmpty()) {
        InputRequestPanel(state.inputRequests, connected, onAnswer = actions::answer, onStop = actions::interrupt.takeIf { state.running })
    } else if (voiceChat.phase != VoicePhase.Off) {
        VoicePanel(
            hazeState = hazeState,
            state = voiceChat,
            onSkip = actions::skipSpeech,
            onEnd = actions::stopVoiceChat,
        )
    } else {
        AnimatedVisibility(visible = suggestions.isNotEmpty() && connected, enter = fadeIn(), exit = fadeOut()) {
            // Kept through the fade-out, so the list doesn't empty before it leaves.
            var shown by remember { mutableStateOf(suggestions) }
            if (suggestions.isNotEmpty()) shown = suggestions
            SlashSuggestions(shown, hazeState, onPick = actions::pickSuggestion)
        }
        Composer(
            hazeState = hazeState,
            actions = actions,
            placeholder = placeholder,
            state = state,
            picker = picker,
            connected = connected,
            attachments = attachments,
            dictation = dictation,
            onOpenModels = onOpenModels,
            onAttach = onAttach,
            onDictate = onDictate,
            onVoiceChat = onVoiceChat,
        )
    }
}

/**
 * Desktop's tab strip on a phone: the title in small spaced capitals over an accent rule, the sessions
 * button on the left and new chat with the chat's options grouped on the right.
 */
@Composable
private fun TopBar(
    title: String,
    subtitle: String?,
    onOpenSidebar: () -> Unit,
    onNewChat: (() -> Unit)?,
    onOpenMenu: (() -> Unit)?,
) {
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
            val shape = RoundedCornerShape(Theme[radii][radiusMedium])
            Row(Modifier.clip(shape).border(1.dp, Theme[colors][stroke], shape)) {
                BarButton(Lucide.SquarePen, "New chat", onClick = { onNewChat?.invoke() }, enabled = onNewChat != null)
                BarButton(Lucide.EllipsisVertical, "Chat options", onClick = { onOpenMenu?.invoke() }, enabled = onOpenMenu != null)
            }
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
        modifier = Modifier.size(MinTouchTarget).clip(RoundedCornerShape(Theme[radii][radiusMedium])).alpha(if (enabled) 1f else 0.35f),
        indication = rememberColoredIndication(Theme[colors][textColor]),
    ) {
        UnstyledIcon(icon, contentDescription = contentDescription, tint = Theme[colors][textSecondary], modifier = Modifier.size(20.dp))
    }
}

/** An empty chat is titled with the app's name in heavy spaced capitals stretched to the column. */
@Composable
private fun Greeting(onAttach: () -> Unit, onDictate: () -> Unit, connected: Boolean, dictation: DictationState) {
    // Blue on light; near-white on dark, where the blue at this size glares.
    val color = if (Theme[colors][background].luminance() < 0.5f) Theme[colors][textColor].copy(alpha = 0.9f) else Theme[colors][accent]
    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            BasicText(
                "HERALD",
                style = Theme[typography][wordmark].copy(textAlign = TextAlign.Center),
                color = { color },
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 72.sp, stepSize = 1.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            // Other ways to begin than typing, named rather than left to the composer's icons.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button("Attach a file", onClick = onAttach, variant = ButtonVariant.Outline, leadingIcon = Lucide.Paperclip, pill = true)
                // Tracks the composer's mic: while recording the same tap finishes it.
                Button(
                    dictateLabel(dictation),
                    onClick = onDictate,
                    variant = ButtonVariant.Outline,
                    leadingIcon = if (dictation.recording) Lucide.Square else Lucide.Mic,
                    enabled = connected,
                    loading = dictation.transcribing,
                    pill = true,
                )
            }
        }
    }
}

/** What the empty chat's dictation pill says for [state]. */
internal fun dictateLabel(state: DictationState): String = when {
    state.transcribing -> "Transcribing…"
    state.recording -> "Finish dictating"
    else -> "Dictate"
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
private fun Messages(
    messages: List<ChatMessage>,
    bottomInset: Dp,
    actions: ChatActions,
    connected: Boolean,
    canChange: Boolean,
) {
    var confirm by remember { mutableStateOf<Pair<MessageChange, String>?>(null) }
    val lastPrompt = messages.lastOrNull { it is ChatMessage.User }?.key
    ConfirmChange(confirm, canChange, lastPrompt, actions, onDismiss = { confirm = null })
    // Edit and branch only show while the chat can change; each asks before it does anything.
    fun ask(change: MessageChange, key: String): (() -> Unit)? = if (canChange) ({ confirm = change to key }) else null
    // Laid out top-down and opened at the end. A list holds its place by the top of what's on screen, so a reply
    // growing below the lines being read leaves them where they are; following the bottom is done here instead.
    val listState = rememberLazyListState(messages.lastIndex.coerceAtLeast(0), LIST_END)
    val scope = rememberCoroutineScope()
    val newest = messages.lastOrNull()
    val awayFromBottom by remember { derivedStateOf { listState.layoutInfo.hiddenBelow() > 600 } }
    // Following while the reader sits right at the end; where they drag to decides whether it carries on.
    var pinned by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }.collect { info ->
            when (followStep(pinned, info.hiddenBelow(), listState.isScrollInProgress)) {
                FollowStep.Unpin -> pinned = false
                FollowStep.Pin -> pinned = true
                FollowStep.ScrollToEnd -> listState.scrollToItem(info.totalItemsCount - 1, LIST_END)
                FollowStep.None -> Unit
            }
        }
    }
    // A prompt just sent is always shown, wherever the reader was.
    LaunchedEffect(newest?.key) {
        if (newest is ChatMessage.User) {
            pinned = true
            listState.scrollToItem(messages.lastIndex, LIST_END)
        }
    }
    // What the bottom held when the reader last saw it; anything since is new to them.
    val tail = messages.lastOrNull()?.let { it.key to it.contentSize() }
    var seenTail by remember { mutableStateOf(tail) }
    LaunchedEffect(awayFromBottom, tail) { if (!awayFromBottom) seenTail = tail }
    val newBelow = awayFromBottom && tail != seenTail
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.Bottom),
        ) {
            items(messages, key = { it.key }) { message ->
                when (message) {
                    is ChatMessage.User -> UserBubble(
                        message,
                        actions,
                        connected,
                        // Not while the prompt is still on its way or its delivery is in doubt.
                        onEdit = ask(MessageChange.EditLastPrompt, message.key)
                            .takeIf { message.key == lastPrompt && !message.pending && message.check == null },
                        onBranch = ask(MessageChange.Branch, message.key).takeIf { !message.pending && message.check == null },
                    )
                    is ChatMessage.Assistant -> AssistantReply(message, onBranch = ask(MessageChange.Branch, message.key))
                    is ChatMessage.Command -> CommandOutput(message)
                    is ChatMessage.Notice -> NoticeLine(message)
                }
            }
        }
        AnimatedVisibility(
            visible = awayFromBottom,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp + bottomInset),
        ) {
            val shape = RoundedCornerShape(Theme[radii][radiusMedium])
            UnstyledButton(
                onClick = {
                    scope.launch {
                        listState.animateToEnd()
                        pinned = true
                    }
                },
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .widthIn(min = 40.dp)
                    .clip(shape)
                    .background(if (newBelow) Theme[colors][accent] else Theme[colors][surfaceElevated])
                    .border(1.dp, if (newBelow) Theme[colors][accent] else Theme[colors][strokeStrong], shape)
                    .animateContentSize(),
                indication = rememberColoredIndication(Theme[colors][textColor]),
            ) {
                // Says "New reply" when something arrived below, not only by the arrow.
                val tint = if (newBelow) Theme[colors][onAccent] else Theme[colors][textColor]
                Row(
                    Modifier.padding(horizontal = if (newBelow) 14.dp else 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UnstyledIcon(
                        Lucide.ArrowDown,
                        contentDescription = if (newBelow) null else "Jump to latest",
                        tint = tint,
                        modifier = Modifier.size(18.dp),
                    )
                    if (newBelow) Text("New reply", style = Theme[typography][label], color = tint)
                }
            }
        }
    }
}

/** A scroll offset past any message: the list stops it at the end of the last one. */
private const val LIST_END = 1_000_000

/** How far the end of the conversation lies below the visible area, in pixels (0 when it's in view). */
private fun LazyListLayoutInfo.hiddenBelow(): Int {
    val last = visibleItemsInfo.lastOrNull() ?: return 0
    if (last.index < totalItemsCount - 1) return Int.MAX_VALUE
    return (last.offset + last.size - (viewportEndOffset - afterContentPadding)).coerceAtLeast(0)
}

internal enum class FollowStep { None, Pin, Unpin, ScrollToEnd }

/**
 * What to do after the list was laid out again. While the reader scrolls, ending up at the end pins the list
 * and leaving it unpins; otherwise a pinned list whose end moved out of view (a reply grew, the composer
 * grew) is scrolled back to it.
 */
internal fun followStep(pinned: Boolean, hiddenBelow: Int, readerScrolling: Boolean): FollowStep = when {
    readerScrolling -> when {
        hiddenBelow <= 1 && !pinned -> FollowStep.Pin
        hiddenBelow > 1 && pinned -> FollowStep.Unpin
        else -> FollowStep.None
    }
    pinned && hiddenBelow > 0 -> FollowStep.ScrollToEnd
    else -> FollowStep.None
}

/** Glides to the end of the conversation, then settles exactly there in case the reply grew on the way. */
private suspend fun LazyListState.animateToEnd() {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    if (layoutInfo.visibleItemsInfo.none { it.index == last }) animateScrollToItem(last)
    layoutInfo.hiddenBelow().takeIf { it in 1..<Int.MAX_VALUE }?.let { animateScrollBy(it.toFloat()) }
    scrollToItem(last, LIST_END)
}

/** How much a message holds, so a reply growing at the bottom counts as new content. */
private fun ChatMessage.contentSize(): Int = when (this) {
    is ChatMessage.User -> text.length
    is ChatMessage.Assistant -> text.length + reasoning.length + tools.size
    is ChatMessage.Command -> output.length
    is ChatMessage.Notice -> text.length
}

/** Tells apart the ways the link can be down: none, being re-made, or needing a new sign-in. */
private fun connectionLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Connecting, is ConnectionState.Reconnecting -> "No connection · connecting again…"
    ConnectionState.SessionExpired -> "Signed out · sign in again"
    is ConnectionState.Failed, ConnectionState.Idle, is ConnectionState.Connected -> "No connection"
}

/** Desktop's turn marker: the prompt in a full-width box with a tinted fill and outline; replies run bare beneath. */
@Composable
private fun UserBubble(
    message: ChatMessage.User,
    actions: ChatActions,
    connected: Boolean,
    onEdit: (() -> Unit)?,
    onBranch: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = message.text.isNotBlank() || onEdit != null || onBranch != null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // A long press opens the prompt's actions just under it; the text itself isn't selectable, Copy is in there.
        DropdownMenu(
            expanded = menuOpen,
            onExpandedChange = { menuOpen = it },
            items = {
                if (message.text.isNotBlank()) {
                    MenuAction("Copy", Lucide.Copy, onClick = {
                        menuOpen = false
                        scope.launch { clipboard.setClipEntry(plainTextClipEntry(message.text)) }
                    })
                }
                onEdit?.let { MenuAction("Edit last prompt", Lucide.Pencil, onClick = { menuOpen = false; it() }) }
                onBranch?.let { MenuAction("Branch from here", Lucide.GitBranch, onClick = { menuOpen = false; it() }) }
            },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (message.pending || message.check == SendCheck.Checking) 0.6f else 1f)
                    .clip(shape)
                    .background(Theme[colors][userBubble], shape)
                    .border(1.dp, Theme[colors][userBubbleStroke], shape)
                    .then(
                        if (hasMenu) {
                            Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuOpen = true
                                },
                                onLongClickLabel = "Message actions",
                                interactionSource = null,
                                indication = rememberColoredIndication(Theme[colors][textColor]),
                            )
                        } else Modifier,
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (message.attachments.isNotEmpty()) SentAttachments(message.attachments)
                if (message.text.isNotEmpty()) Text(message.text, style = Theme[typography][body], color = Theme[colors][textColor])
            }
        }
        if (message.queued) {
            Text("Queued · sends after this task", style = Theme[typography][caption], color = Theme[colors][textTertiary])
        }
        when (message.check) {
            SendCheck.Checking -> Text("Checking whether Hermes got this…", style = Theme[typography][caption], color = Theme[colors][textTertiary])
            SendCheck.Unknown -> UnsettledActions("May not have reached Hermes", message.key, mayHaveArrived = true, actions, connected)
            SendCheck.NotReceived -> UnsettledActions("Hermes didn't get this", message.key, mayHaveArrived = false, actions, connected)
            null -> {}
        }
    }
}

/**
 * What to do with a prompt that lost its reply: check the transcript again, resend it, or take it back to
 * edit. Nothing is resent on its own; when it [mayHaveArrived], Resend first warns it could run twice.
 */
@Composable
private fun UnsettledActions(label: String, key: String, mayHaveArrived: Boolean, actions: ChatActions, connected: Boolean) {
    var confirmResend by remember(key) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = Theme[typography][caption], color = Theme[colors][textTertiary])
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (mayHaveArrived) {
                Button("Check delivery", onClick = { actions.checkDelivery(key) }, variant = ButtonVariant.Ghost, size = ButtonSize.Small, leadingIcon = Lucide.SearchCheck)
            }
            Button(
                "Resend",
                onClick = { if (mayHaveArrived) confirmResend = true else actions.resend(key) },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Small,
                leadingIcon = Lucide.RotateCw,
                enabled = connected,
            )
            Button("Edit", onClick = { actions.editMessage(key) }, variant = ButtonVariant.Ghost, size = ButtonSize.Small, leadingIcon = Lucide.Pencil)
        }
    }
    Dialog(
        visible = confirmResend,
        onDismissRequest = { confirmResend = false },
        title = "Resend this message?",
        message = "Hermes may already have it. If it does, resending makes Hermes get the same request twice and run it again. " +
            "Check delivery first to be sure.",
        actions = {
            Button("Cancel", onClick = { confirmResend = false }, variant = ButtonVariant.Ghost)
            Button("Resend", onClick = { confirmResend = false; actions.resend(key) })
        },
    )
}

/** The two message actions that change the conversation, so they ask first. */
private enum class MessageChange { EditLastPrompt, Branch }

/**
 * Says what an edit or a branch does, and that no task starts, before doing it. Closes itself if the chat stops
 * [allowing][canChange] it while open, or another prompt becomes the last one to edit.
 */
@Composable
private fun ConfirmChange(
    pending: Pair<MessageChange, String>?,
    canChange: Boolean,
    lastPrompt: String?,
    actions: ChatActions,
    onDismiss: () -> Unit,
) {
    val stale = pending != null &&
        (!canChange || (pending.first == MessageChange.EditLastPrompt && pending.second != lastPrompt))
    LaunchedEffect(stale) { if (stale) onDismiss() }
    // Keeps its words while it fades out, after [pending] has already gone.
    var shown by remember { mutableStateOf(pending) }
    if (pending != null) shown = pending
    Dialog(
        visible = pending != null,
        onDismissRequest = onDismiss,
        title = if (shown?.first == MessageChange.Branch) "Branch from here?" else "Edit your last prompt?",
        message = if (shown?.first == MessageChange.Branch) {
            "Copies this chat up to this message into a new chat and opens it. This chat stays as it is. " +
                "No task starts until you send something in the new chat."
        } else {
            "Takes your last message and Hermes's reply to it off this chat and puts the message back in the composer. " +
                "No task starts until you send it again."
        },
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost)
            Button(if (shown?.first == MessageChange.Branch) "Branch" else "Edit", onClick = {
                onDismiss()
                when (pending?.first) {
                    MessageChange.Branch -> actions.branchFrom(pending.second)
                    MessageChange.EditLastPrompt -> actions.editLastPrompt(pending.second)
                    null -> {}
                }
            })
        },
    )
}

@Composable
private fun AssistantReply(message: ChatMessage.Assistant, onBranch: (() -> Unit)?) {
    val settings = LocalAppSettings.current
    val showReasoning = settings.showReasoning && message.reasoning.isNotBlank()
    val showTools = settings.showToolActivity && message.tools.isNotEmpty()
    // Pictures and files the reply delivered show as themselves, not as Markdown a renderer can't load.
    val (text, media) = remember(message.text) { extractReplyMedia(message.text) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // What's happening now is said once, above the composer; the reply keeps only what it's made of.
        if (showReasoning) Reasoning(message.reasoning)
        if (showTools) Tools(message.tools)
        // Shown whatever the tool-activity setting: the work happens out of sight, in other agents.
        message.tools.filter { it.name == "delegate_task" }.forEach { DelegationCard(it) }
        if (text.isNotBlank()) SelectionContainer { MarkdownText(text, streaming = message.streaming) }
        if (media.isNotEmpty()) ReplyMediaList(media)
        when (message.outcome) {
            TurnOutcome.Error -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                UnstyledIcon(Lucide.CircleAlert, contentDescription = null, tint = Theme[colors][danger], modifier = Modifier.size(16.dp))
                Text(message.error ?: "The turn failed.", style = Theme[typography][bodySmall], color = Theme[colors][danger])
            }
            TurnOutcome.Interrupted -> Text("Stopped", style = Theme[typography][caption], color = Theme[colors][textTertiary])
            else -> Unit
        }
        message.warning?.let { warningText ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                UnstyledIcon(Lucide.TriangleAlert, contentDescription = null, tint = Theme[colors][warning], modifier = Modifier.padding(top = 2.dp).size(14.dp))
                Text(warningText, style = Theme[typography][bodySmall], color = Theme[colors][warning])
            }
        }
        val usage = message.usage?.takeIf { settings.showUsage && !message.streaming }
        if ((!message.streaming && text.isNotBlank()) || usage != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!message.streaming && text.isNotBlank()) {
                    // Their padding trimmed off the start and top, the icons line up with the reply and sit close under it.
                    CopyButton(text, Modifier.trimStartTop(start = 8.dp, top = 4.dp))
                    onBranch?.let {
                        IconButton(Lucide.GitBranch, contentDescription = "Branch from here", onClick = it, modifier = Modifier.trimStartTop(start = 0.dp, top = 4.dp))
                    }
                }
                usage?.let {
                    Text(
                        "${compactCount(it.input)} in · ${compactCount(it.output)} out",
                        style = Theme[typography][caption],
                        color = Theme[colors][textTertiary],
                    )
                }
            }
        }
    }
}

/** A session notice: a centred quiet line between the messages. */
@Composable
private fun NoticeLine(message: ChatMessage.Notice) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top,
    ) {
        UnstyledIcon(Lucide.Info, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.padding(top = 2.dp).size(13.dp))
        Text(message.text, style = Theme[typography][caption], color = Theme[colors][textTertiary])
    }
}

/** A slash command and what it printed: a quiet outlined card in the terminal face, local to this device. */
@Composable
private fun CommandOutput(message: ChatMessage.Command) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            UnstyledIcon(Lucide.SquareTerminal, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(14.dp))
            Text(
                message.command,
                style = Theme[typography][code].copy(fontWeight = FontWeight.SemiBold),
                color = Theme[colors][textSecondary],
                modifier = Modifier.weight(1f),
            )
            if (message.running) Spinner(Modifier.size(12.dp))
        }
        if (message.output.isNotEmpty()) SelectionContainer {
            Text(
                message.output,
                style = Theme[typography][code].copy(fontSize = 12.sp, lineHeight = 18.sp),
                color = if (message.failed) Theme[colors][danger] else Theme[colors][textColor],
            )
        }
    }
}

/** The `/` list over the composer: commands, skills and argument choices, best match first. */
@Composable
private fun SlashSuggestions(suggestions: List<SlashSuggestion>, hazeState: HazeState, onPick: (SlashSuggestion) -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    val page = Theme[colors][background]
    val frosted = remember(page) {
        HazeBlurStyle {
            blurEnabled(true)
            blurRadius(20.dp)
            backgroundColor(page)
        }
    }
    LazyColumn(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 6.dp)
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .clip(shape)
            .hazeBlur(input = HazeInput.Sources(hazeState), style = frosted)
            .background(Theme[colors][surfaceElevated].copy(alpha = 0.85f))
            .border(1.dp, Theme[colors][strokeStrong], shape),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        suggestions.forEachIndexed { index, row ->
            val group = row.group
            if (group != null && group != suggestions.getOrNull(index - 1)?.group) {
                item(key = "group-$index-$group") {
                    Text(
                        group.uppercase(),
                        style = Theme[typography][caption].copy(fontWeight = FontWeight.Bold, letterSpacing = 0.06.em),
                        color = Theme[colors][textTertiary],
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = if (index == 0) 4.dp else 10.dp, bottom = 2.dp),
                    )
                }
            }
            item(key = "row-$index-${row.text}") {
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(row) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.label,
                        style = Theme[typography][code].copy(fontWeight = FontWeight.SemiBold),
                        color = if (row.kind == SlashKind.Skill) Theme[colors][accent] else Theme[colors][textColor],
                        maxLines = 1,
                    )
                    if (row.description.isNotBlank()) {
                        Text(
                            row.description,
                            style = Theme[typography][caption],
                            color = Theme[colors][textTertiary],
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
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

/** The reply's reasoning, folded; live progress is the dock's to show. */
@Composable
private fun Reasoning(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Disclosure(
        icon = { UnstyledIcon(Lucide.Brain, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp)) },
        label = "Reasoning",
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Text(text.trim(), style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
    }
}

/** The tools the reply used, folded; the one running now is named above the composer instead. */
@Composable
private fun Tools(tools: List<ToolActivity>) {
    var expanded by remember { mutableStateOf(false) }
    val names = tools.map { it.name }.distinct()
    val label = if (names.size <= 2) "Used ${names.joinToString(" and ")}" else "Used ${tools.size} tools"
    Disclosure(
        icon = { UnstyledIcon(Lucide.Wrench, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp)) },
        label = label,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tools.forEach { ToolRow(it) }
        }
    }
}

/** The tool the running turn is in the middle of, if any. Its reply isn't always last: a queued prompt sits after it. */
private fun ChatState.runningTool(): ToolActivity? =
    (messages.lastOrNull { it is ChatMessage.Assistant && it.streaming } as? ChatMessage.Assistant)?.tools?.lastOrNull { it.running }

/** This turn's plan; one left over from an earlier turn isn't what's happening now. */
private fun ChatState.livePlan(): TodoList? = todos?.takeIf { todosLive }

/**
 * The one line that says what the agent is doing now, most specific first: waiting on the user, a tool
 * at work, the plan's step in hand, the gateway's status text, else thinking.
 */
internal fun currentAction(state: ChatState): String {
    if (state.inputRequests.isNotEmpty()) return "Waiting for your answer"
    state.runningTool()?.let { tool ->
        val detail = tool.detail?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
        return if (detail != null) "${tool.name.toolVerb()}: $detail" else tool.name.toolVerb()
    }
    state.livePlan()?.items?.firstOrNull { it.status == TodoStatus.InProgress }?.let { return it.content }
    state.status?.takeIf { it.isNotBlank() }?.let { return it }
    return state.thinkingFrame ?: "Thinking…"
}

/** "terminal" reads as "Running a command", and so on; other tools as "Using <name>". */
private fun String.toolVerb(): String = when (this) {
    "terminal", "shell", "bash" -> "Running a command"
    "read_file", "file_read" -> "Reading a file"
    "write_file", "patch", "edit_file" -> "Editing a file"
    "web_search", "search" -> "Searching the web"
    "web_extract", "browser", "fetch" -> "Reading a web page"
    "delegate_task" -> "Working with subagents"
    else -> "Using ${replace('_', ' ')}"
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
 * Desktop's composer stood up for a phone: a flat outlined box, the text on top; beneath it +, dictation
 * and voice chat, the model and thinking level as quiet text, and the round send button, which is Stop
 * for as long as a task runs. A message typed mid-task gets its own "Send now" / "Send after" choices.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(
    hazeState: HazeState,
    actions: ChatActions,
    placeholder: String,
    state: ChatState,
    picker: ModelPickerState,
    connected: Boolean,
    attachments: List<OutgoingAttachment>,
    dictation: DictationState,
    onOpenModels: () -> Unit,
    onAttach: () -> Unit,
    onDictate: () -> Unit,
    onVoiceChat: () -> Unit,
) {
    // Attachments alone are sendable: the gateway gets Desktop's image prompt or the file references.
    val hasText = actions.composer.text.isNotBlank() || attachments.isNotEmpty()
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    val page = Theme[colors][background]
    val frosted = remember(page) {
        HazeBlurStyle {
            blurEnabled(true)
            blurRadius(20.dp)
            backgroundColor(page)
        }
    }
    var focused by remember { mutableStateOf(false) }
    // Back hides the keyboard but leaves the field focused, and a focused field brings the keyboard back on
    // the next relayout (opening a tool card, the menu). Put away means done typing, so let go of the focus.
    val imeVisible = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    LaunchedEffect(imeVisible) { if (!imeVisible && focused) focusManager.clearFocus() }
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
            .clip(shape)
            // Frosted: the conversation beneath is blurred, then tinted so the text on top stays clear.
            .hazeBlur(input = HazeInput.Sources(hazeState), style = frosted)
            .background(Theme[colors][surface].copy(alpha = 0.55f))
            .border(1.dp, if (focused) Theme[colors][textTertiary] else Theme[colors][strokeStrong], shape)
            .onFocusChanged { focused = it.hasFocus }
            .padding(start = 4.dp, end = 6.dp, top = if (attachments.isEmpty()) 14.dp else 8.dp, bottom = 6.dp),
    ) {
        if (attachments.isNotEmpty()) ComposerTray(attachments, onRemove = actions::removeAttachment)
        UnstyledTextField(
            state = actions.composer,
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
        // Mid-turn, a message either joins the running task or waits for it; both say which.
        if (state.running && hasText) {
            MidTaskSend(
                // The same test send() makes: with attachments, slash text goes out as a prompt, not a command.
                command = attachments.isEmpty() && SlashCommand.parse(actions.composer.text.toString().trim()) != null,
                enabled = connected,
                onSendNow = { actions.send() },
                onSendAfter = { actions.send(queue = true) },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            // The buttons are full 48dp targets, which already space their icons apart.
        ) {
            ComposerButton(
                icon = Lucide.Plus,
                contentDescription = "Add photos or files",
                onClick = onAttach,
                enabled = attachments.size < OutgoingAttachment.MAX_COUNT,
            )
            DictationButton(dictation, onClick = onDictate, enabled = connected)
            ComposerButton(
                icon = Lucide.AudioLines,
                contentDescription = "Start a voice chat",
                onClick = onVoiceChat,
                enabled = connected && !state.running && !dictation.active,
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { ModelPill(state, picker, onClick = onOpenModels) }
            // The round button keeps one job per state: Stop for the whole task, even while you type.
            if (state.running) {
                SendButton(SendIcon.Stop, onClick = actions::interrupt, enabled = connected)
            } else {
                SendButton(SendIcon.Send, onClick = { actions.send() }, enabled = connected && hasText)
            }
        }
    }
}

/**
 * The choices for a message typed while a task runs: "Send now" adds it to the task in progress, "Send after
 * this task" holds it for the next turn. A slash command runs at once, so it only gets "Send now".
 */
@Composable
private fun MidTaskSend(command: Boolean, enabled: Boolean, onSendNow: () -> Unit, onSendAfter: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                "Send now",
                onClick = onSendNow,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = Lucide.ArrowUp,
                enabled = enabled,
                pill = true,
            )
            if (!command) {
                Button(
                    "Send after this task",
                    onClick = onSendAfter,
                    variant = ButtonVariant.Outline,
                    size = ButtonSize.Small,
                    leadingIcon = Lucide.ListEnd,
                    enabled = enabled,
                    pill = true,
                )
            }
        }
        Text(
            if (command) "Commands run right away." else "Send now changes the task in progress.",
            style = Theme[typography][caption],
            color = Theme[colors][textTertiary],
        )
    }
}

/** A plain square icon button inside the composer, like Desktop's +. */
@Composable
private fun ComposerButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, enabled: Boolean) {
    val tint = if (enabled) Theme[colors][textSecondary] else Theme[colors][textTertiary].copy(alpha = 0.5f)
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(MinTouchTarget).clip(RoundedCornerShape(Theme[radii][radiusMedium])),
        indication = rememberColoredIndication(tint),
    ) {
        UnstyledIcon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

private enum class SendIcon { Send, Stop }

/**
 * Desktop's round send: a disc in the text colour (white on dark) with the icon cut in the page colour.
 * The disc stays 40dp; the button around it takes taps over the full [MinTouchTarget].
 */
@Composable
private fun SendButton(icon: SendIcon, onClick: () -> Unit, enabled: Boolean) {
    val fill = if (enabled) Theme[colors][textColor] else Theme[colors][textColor].copy(alpha = 0.12f)
    val tint = if (enabled) Theme[colors][background] else Theme[colors][textTertiary]
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(MinTouchTarget).clip(CircleShape),
        indication = rememberColoredIndication(tint),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
            UnstyledIcon(
                when (icon) {
                    SendIcon.Send -> Lucide.ArrowUp
                    SendIcon.Stop -> Lucide.Square
                },
                contentDescription = when (icon) {
                    SendIcon.Send -> "Send"
                    SendIcon.Stop -> "Stop the task"
                },
                tint = tint,
                modifier = Modifier.size(if (icon == SendIcon.Stop) 16.dp else 20.dp),
            )
        }
    }
}

/** The composer's microphone: tap to dictate, tap again to finish; the ring follows your voice. */
@Composable
private fun DictationButton(state: DictationState, onClick: () -> Unit, enabled: Boolean) {
    if (state.transcribing) {
        Box(Modifier.size(MinTouchTarget), contentAlignment = Alignment.Center) { Spinner(Modifier.size(18.dp)) }
        return
    }
    val recording = state.recording
    val tint = when {
        recording -> Theme[colors][danger]
        enabled -> Theme[colors][textSecondary]
        else -> Theme[colors][textTertiary].copy(alpha = 0.5f)
    }
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(MinTouchTarget)
            .clip(CircleShape)
            .then(if (recording) Modifier.border(2.dp, tint.copy(alpha = 0.25f + 0.75f * state.level), CircleShape) else Modifier),
        indication = rememberColoredIndication(tint),
    ) {
        UnstyledIcon(
            if (recording) Lucide.Square else Lucide.Mic,
            contentDescription = if (recording) "Finish dictating" else "Dictate",
            tint = tint,
            modifier = Modifier.size(if (recording) 16.dp else 22.dp),
        )
    }
}

/**
 * Takes the composer's place during a voice chat: what's happening now, a circle that swells with
 * your voice, and buttons to skip the reply being read or end the chat. What you say sends itself.
 */
@Composable
private fun VoicePanel(hazeState: HazeState, state: VoiceChatState, onSkip: () -> Unit, onEnd: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    val page = Theme[colors][background]
    val frosted = remember(page) {
        HazeBlurStyle {
            blurEnabled(true)
            blurRadius(20.dp)
            backgroundColor(page)
        }
    }
    val label = when (state.phase) {
        VoicePhase.Listening -> if (state.hearing) "Hearing you…" else "Listening…"
        VoicePhase.Transcribing -> "Catching that…"
        VoicePhase.Thinking -> "Thinking…"
        VoicePhase.Speaking -> "Speaking…"
        VoicePhase.Off -> ""
    }
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
            .clip(shape)
            .hazeBlur(input = HazeInput.Sources(hazeState), style = frosted)
            .background(Theme[colors][surface].copy(alpha = 0.55f))
            .border(1.dp, Theme[colors][strokeStrong], shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            when (state.phase) {
                VoicePhase.Listening -> {
                    // Square root, so a phone mic's quiet range still visibly moves the circle.
                    val size = 16.dp + 24.dp * sqrt((state.level * 4f).coerceIn(0f, 1f))
                    Box(Modifier.size(size).clip(CircleShape).background(Theme[colors][accent]))
                }
                VoicePhase.Speaking -> UnstyledIcon(Lucide.AudioLines, contentDescription = null, tint = Theme[colors][accent], modifier = Modifier.size(24.dp))
                else -> Spinner(Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = Theme[typography][body], color = Theme[colors][textColor])
            Text(
                when (state.phase) {
                    VoicePhase.Listening -> "Just talk · say “stop” to end"
                    VoicePhase.Speaking -> "Tap skip to talk again."
                    else -> "Voice chat"
                },
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.phase == VoicePhase.Speaking) {
            Button("Skip", onClick = onSkip, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
        }
        IconButton(Lucide.X, contentDescription = "End voice chat", onClick = onEnd)
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

/**
 * Lays the element out [start] and [top] smaller, drawn up and to the left by as much: empty padding inside
 * it stops pushing it away from its neighbours, while it still takes taps over its whole size.
 */
private fun Modifier.trimStartTop(start: Dp, top: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val dx = start.roundToPx()
    val dy = top.roundToPx()
    layout(placeable.width - dx, placeable.height - dy) { placeable.place(-dx, -dy) }
}
