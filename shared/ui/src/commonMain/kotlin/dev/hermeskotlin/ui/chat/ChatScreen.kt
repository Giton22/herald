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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Ellipsis
import dev.hermeskotlin.designsystem.HeraldBrandBlue
import dev.hermeskotlin.designsystem.HeraldMark
import dev.hermeskotlin.designsystem.components.glow
import dev.hermeskotlin.designsystem.components.halo
import dev.hermeskotlin.designsystem.dangerSoft
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.radiusXSmall
import dev.hermeskotlin.designsystem.successSoft
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.warningSoft
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import dev.hermeskotlin.core.bots.botLook
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.History
import dev.hermeskotlin.ui.components.savedCopyLabel
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.role
import dev.hermeskotlin.designsystem.radiusXLarge
import androidx.compose.ui.semantics.Role
import dev.hermeskotlin.designsystem.surface2
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.ListEnd
import com.composables.icons.lucide.Info
import dev.hermeskotlin.core.chat.MESSAGE_AGENT_TOOL
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.handle
import dev.hermeskotlin.core.bots.mentionQuery
import dev.hermeskotlin.core.bots.mentionable
import dev.hermeskotlin.ui.bots.BotAvatar
import dev.hermeskotlin.ui.bots.LocalBotFaces
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PanelLeft
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.SearchCheck
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.FileDiff
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.ListTodo
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
import dev.hermeskotlin.core.chat.GatewayNotice
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.OutgoingAttachment
import dev.hermeskotlin.core.chat.SendCheck
import dev.hermeskotlin.core.chat.SessionRefusal
import dev.hermeskotlin.core.chat.SubagentStatus
import dev.hermeskotlin.core.chat.subagentRows
import com.composables.icons.lucide.Hourglass
import com.composables.icons.lucide.MonitorSmartphone
import dev.hermeskotlin.core.chat.GeneratedImage
import dev.hermeskotlin.core.chat.asMedia
import dev.hermeskotlin.core.chat.extractReplyMedia
import dev.hermeskotlin.core.chat.unservableEchoes
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.chat.TodoList
import dev.hermeskotlin.core.chat.TodoStatus
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.core.chat.compactCount
import dev.hermeskotlin.core.chat.canEdit
import dev.hermeskotlin.core.chat.discardedAfter
import dev.hermeskotlin.core.chat.regenerateTarget
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
import dev.hermeskotlin.ui.voice.VoiceChatState
import dev.hermeskotlin.ui.voice.VoicePanel
import dev.hermeskotlin.ui.voice.VoicePhase
import dev.hermeskotlin.ui.voice.VoiceScreen
import dev.hermeskotlin.core.voice.speakableText
import androidx.compose.runtime.saveable.rememberSaveable
import dev.hermeskotlin.ui.voice.rememberMicrophonePermission
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.composables.icons.lucide.AudioLines
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Paperclip
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
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.CopyButton
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.DropdownMenu
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MenuAction
import dev.hermeskotlin.core.settings.RunningSend
import dev.hermeskotlin.designsystem.components.plainTextClipEntry
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.GitBranch
import com.composables.icons.lucide.Pencil
import dev.hermeskotlin.designsystem.components.LocalTextHighlights
import dev.hermeskotlin.designsystem.components.MarkdownText
import dev.hermeskotlin.designsystem.components.highlightColor
import dev.hermeskotlin.designsystem.components.withHighlights
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.heading
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onAccent
import dev.hermeskotlin.designsystem.onUserBubble
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
import dev.hermeskotlin.ui.components.messageTime
import dev.hermeskotlin.ui.components.uses24HourClock
import kotlinx.coroutines.delay
import androidx.compose.runtime.produceState
import kotlin.time.Clock
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
    var checkpointsOpen by remember { mutableStateOf(false) }
    var controlOpen by remember { mutableStateOf(false) }
    // Cleared (here or on another client) while open, there is nothing left to show.
    val hasControl = state.control != null
    LaunchedEffect(hasControl) { if (!hasControl) controlOpen = false }
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
    // Dictation stops when the app leaves the screen; a voice chat goes on like a call (VoiceController.onBackground).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.voice.onBackground() }
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
                ChatRequest.OpenCheckpoints -> checkpointsOpen = true
                ChatRequest.OpenControl -> controlOpen = true
                ChatRequest.StartVoice -> startVoiceChat()
                ChatRequest.StartDictation -> toggleDictation()
            }
        }
    }

    // The `@word` at the cursor, read in a derived state so typing only redraws the chat when the list changes.
    val faces = LocalBotFaces.current
    val mention by remember(viewModel) {
        derivedStateOf { viewModel.composer.let { mentionQuery(it.text.toString(), it.selection.end) } }
    }
    val mentions = remember(mention, faces, target.bot) {
        mention?.let { faces.bots.mentionable(it.query, self = target.bot?.name) }.orEmpty()
    }
    ChatView(
        // A bot's chat is titled "Bot Chat" on the gateway; the bot's name says more.
        title = target.bot?.label ?: state.title?.takeIf { it.isNotBlank() } ?: target.title ?: "New chat",
        titleFace = LocalBotFaces.current.let { faces ->
            faces.find(target.bot?.name)?.let { bot -> { BotAvatar(bot, faces.picture(bot), size = 22.dp) } }
        },
        state = state,
        picker = picker,
        connected = connected,
        connectionLabel = connectionLabel(connection),
        linkStatus = linkStatus(connection),
        onRetryConnection = viewModel::retryConnection,
        attachments = attachments,
        attachmentError = attachmentError,
        comments = viewModel.comments.collectAsStateWithLifecycle().value,
        voiceChat = viewModel.voice.chat.collectAsStateWithLifecycle().value,
        dictation = viewModel.voice.dictation.collectAsStateWithLifecycle().value,
        suggestions = viewModel.suggestions.collectAsStateWithLifecycle().value,
        mentions = mentions,
        onMention = { bot ->
            val typed = mention ?: return@ChatView
            viewModel.composer.edit { replace(typed.start, typed.end, "@${bot.handle} ") }
        },
        sprite = viewModel.pets.sprite.collectAsStateWithLifecycle().value,
        // Re-rolled per conversation, kept while a new chat gets its stored id.
        placeholder = remember(target) { (if (target.storedSessionId == null) NEW_CHAT_PROMPTS else FOLLOW_UP_PROMPTS).random() },
        notice = notice,
        actions = viewModel,
        editing = viewModel.editing.collectAsStateWithLifecycle().value,
        onOpenSidebar = onOpenSidebar,
        // Already on an untouched new chat: nothing to start over from.
        onNewChat = onNewChat.takeIf { target.storedSessionId != null || state.messages.isNotEmpty() },
        onOpenMenu = onOpenMenu,
        onOpenModels = { modelsOpen = true },
        onAttach = { attachOpen = true },
        onDictate = toggleDictation,
        onVoiceChat = startVoiceChat,
        onOpenPets = { petsOpen = true },
        onOpenControl = {
            viewModel.control.clearError()
            controlOpen = true
        },
        onViewImage = { viewing = it },
        onNotice = { notice = it },
        wallpaper = rememberChatWallpaper(),
        place = target.gateway.label,
        profile = target.profile,
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
    CheckpointsSheet(
        visible = checkpointsOpen,
        controller = viewModel.checkpoints,
        onLoad = viewModel::loadCheckpoints,
        onDiff = viewModel::checkpointDiff,
        onRestore = viewModel::restoreCheckpoint,
        running = state.running,
        onDismiss = { checkpointsOpen = false },
    )
    SessionControlSheet(
        visible = controlOpen,
        control = state.control,
        controller = viewModel.control,
        onAction = viewModel::runControl,
        onDismiss = { controlOpen = false },
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
        onRefresh = { viewModel.loadModels(withPrices = true) },
        onSelectModel = {
            viewModel.selectModel(it)
            modelsOpen = false
        },
        onSelectEffort = { viewModel.setReasoningEffort(it.wire) },
        onFast = viewModel::setFast,
        // A Bot Chat's model is its bot's (selectModel sets it there), so say what a pick changes.
        note = target.bot?.let { "A model picked here becomes ${it.label}'s own, for all its chats and routines." },
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
    /** Drawn beside the title, e.g. the face of the bot whose chat this is. */
    titleFace: (@Composable () -> Unit)? = null,
    state: ChatState,
    picker: ModelPickerState,
    connected: Boolean,
    /** Which way the link is down, shown under the title while not [connected]. */
    connectionLabel: String = "No connection",
    /** Shown in the chat while the link is being made again, so a reply that stopped streaming says why. */
    linkStatus: String? = null,
    /** Tries the gateway again now, from the offline banner. */
    onRetryConnection: () -> Unit = {},
    attachments: List<OutgoingAttachment>,
    attachmentError: String?,
    /** Comments on parts of the chat, waiting for the next send. */
    comments: List<PendingComment> = emptyList(),
    voiceChat: VoiceChatState,
    dictation: DictationState,
    suggestions: List<SlashSuggestion>,
    /** Bots matching the `@word` being typed, to pick one to mention. */
    mentions: List<Bot> = emptyList(),
    onMention: (Bot) -> Unit = {},
    sprite: PetSprite?,
    placeholder: String,
    notice: String?,
    actions: ChatActions,
    /** The prompt being edited in the composer, whose send replaces it. */
    editing: String? = null,
    onOpenSidebar: () -> Unit,
    onNewChat: (() -> Unit)?,
    onOpenMenu: (() -> Unit)?,
    onOpenModels: () -> Unit,
    onAttach: () -> Unit,
    onDictate: () -> Unit,
    onVoiceChat: () -> Unit,
    onOpenPets: () -> Unit,
    /** Opens the goal and loops sheet from the strip above the composer. */
    onOpenControl: () -> Unit = {},
    onViewImage: (ViewerImage) -> Unit,
    onNotice: (String) -> Unit,
    /** The chat background from Settings, drawn behind the conversation (and frosted under the composer). */
    wallpaper: ImageBitmap? = null,
    /** The gateway's name, said where Hermes runs: under the title and on the empty chat. */
    place: String? = null,
    /** The profile the chat runs in; null is the launch profile, shown as "default". */
    profile: String? = null,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .then(if (wallpaper == null) Modifier.halo() else Modifier),
        contentAlignment = Alignment.TopCenter,
    ) {
        // A voice chat fills the screen until folded to the panel over the chat; each new one opens full.
        var voiceFolded by rememberSaveable { mutableStateOf(false) }
        var voiceCaptions by rememberSaveable { mutableStateOf(true) }
        var typeAfterVoice by remember { mutableStateOf(false) }
        val voiceOn = voiceChat.phase != VoicePhase.Off
        val voiceFull = voiceOn && !voiceFolded
        LaunchedEffect(voiceOn) { if (!voiceOn) voiceFolded = false }
        Column(
            Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                // The insets pad the chat only, so the voice screen can reach under the status bar.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .imePadding()
                // Under the voice screen the chat is out of reach, TalkBack included.
                .then(if (voiceFull) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            TopBar(
                title = title,
                titleFace = titleFace,
                status = chatStatus(state, connected, connectionLabel, place),
                onOpenSidebar = onOpenSidebar,
                onNewChat = onNewChat,
                onOpenMenu = onOpenMenu,
            )
            // The composer floats over the conversation, which scrolls on beneath it; its height pads the list.
            var dockHeight by remember { mutableIntStateOf(0) }
            val dockInset = with(LocalDensity.current) { dockHeight.toDp() }
            // Reading back up the chat folds the composer to one line; nearing the end opens it again.
            val composerFold = remember { ComposerFold() }
            // The list keeps the open composer's height below the last message, so folding it doesn't move the end
            // and undo the fold; only the jump button and the pet follow the composer's real height.
            var composerShrink by remember { mutableIntStateOf(0) }
            val listInset = with(LocalDensity.current) { (dockHeight + composerShrink).toDp() }
            // What scrolls under the composer is captured here and frosted behind it.
            val hazeState = rememberHazeState()
            // Comments wait in the composer, so the selection menus offer them only while it shows.
            val composerFocus = remember { FocusRequester() }
            var focusComment by remember { mutableStateOf<Long?>(null) }
            val composerShown = state.inputRequests.isEmpty() && voiceChat.phase == VoicePhase.Off
            // Type on the voice screen ends the call; the composer takes the keyboard once it's back.
            // Once the call is over the wish is spent either way: a question waiting in the composer's place
            // mustn't hand the keyboard over minutes later when it's answered.
            LaunchedEffect(composerShown, typeAfterVoice, voiceOn) {
                if (!typeAfterVoice || voiceOn) return@LaunchedEffect
                typeAfterVoice = false
                if (composerShown) composerFocus.requestFocus()
            }
            val commentHost = remember(comments, actions, composerShown) {
                if (!composerShown) return@remember null
                object : CommentHost {
                    override fun onSelection(action: SelectionAction, source: CommentSource, anchor: SelectionAnchor) {
                        when (action) {
                            SelectionAction.Comment -> focusComment = actions.addComment(source, anchor)
                            SelectionAction.Explain -> actions.explain(source, anchor)
                            SelectionAction.AskAside -> {
                                actions.askAside(source, anchor)
                                composerFocus.requestFocus()
                            }
                        }
                    }

                    override fun highlights(messageKey: String) =
                        comments.filter { it.source.messageKey == messageKey }.flatMap { it.highlights }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                    if (wallpaper != null) ChatWallpaper(wallpaper, LocalAppSettings.current.wallpaperStrength, Modifier.fillMaxSize())
                    if (state.historyLoaded && state.messages.isNotEmpty()) {
                        CompositionLocalProvider(
                            LocalMediaLoader provides actions::loadMedia,
                            LocalOpenImage provides onViewImage,
                            LocalNotice provides onNotice,
                            LocalSubagents provides SubagentContext(state.subagents, actions::stopSubagent),
                            LocalCommentHost provides commentHost,
                        ) {
                            Messages(
                                state.messages,
                                olderMessages = state.olderMessages,
                                loadingOlder = state.loadingOlder,
                                bottomInset = listInset,
                                controlsInset = dockInset,
                                fold = composerFold,
                                actions = actions,
                                connected = connected,
                                // A saved copy may lack what changed since; edits wait for the gateway's transcript.
                                canChange = state.canChangeChat(connected && state.savedCopyAt == null),
                                editing = editing,
                            )
                        }
                    } else Box(Modifier.fillMaxSize().padding(bottom = dockInset)) {
                        when {
                            !state.historyLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                            state.historyError != null ->
                                EmptyState(Lucide.CloudOff, "Couldn't load the conversation", state.historyError.orEmpty(), error = true) {
                                    Button("Try again", onClick = actions::retry, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
                                }
                            else -> Greeting(
                                onAttach = onAttach,
                                onDictate = onDictate,
                                onVoiceChat = onVoiceChat,
                                connected = connected,
                                dictation = dictation,
                                canAttach = attachments.size < OutgoingAttachment.MAX_COUNT,
                                place = place,
                                profile = profile,
                            )
                        }
                    }
                }
                // Over the top of the conversation: the link being made again, and an older page on its way.
                var lastLinkStatus by remember { mutableStateOf("") }
                if (linkStatus != null) lastLinkStatus = linkStatus
                Column(
                    Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AnimatedVisibility(visible = linkStatus != null, enter = fadeIn(), exit = fadeOut()) {
                        OfflineBanner(place, lastLinkStatus, onRetry = onRetryConnection)
                    }
                    // A chat read from the device's copy says so, and how old it is, once the gateway is known to be away;
                    // while it's only being asked, the copy is replaced without a word.
                    var lastSavedAt by remember { mutableStateOf(0L) }
                    state.savedCopyAt?.let { lastSavedAt = it }
                    val showSaved = state.savedCopyAt != null && state.messages.isNotEmpty() && (state.historyError != null || !connected)
                    AnimatedVisibility(visible = showSaved, enter = fadeIn(), exit = fadeOut()) {
                        // Offline, the banner above offers the retry; connected, the transcript itself failed to load.
                        SavedCopyPill(lastSavedAt, onRetry = if (connected) actions::retry else null)
                    }
                    AnimatedVisibility(visible = state.loadingOlder, enter = fadeIn(), exit = fadeOut()) {
                        StatusPill("Loading earlier messages…")
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
                        mentions = mentions,
                        onMention = onMention,
                        notice = notice,
                        editing = editing != null,
                        comments = comments,
                        focusComment = focusComment,
                        onCommentFocused = { focusComment = null },
                        composerFocus = composerFocus,
                        composerFold = composerFold,
                        onComposerShrink = { composerShrink = it },
                        onOpenModels = onOpenModels,
                        onAttach = onAttach,
                        onDictate = onDictate,
                        onVoiceChat = onVoiceChat,
                        onOpenControl = onOpenControl,
                        voiceFull = voiceFull,
                        onExpandVoice = { voiceFolded = false },
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
        AnimatedVisibility(visible = voiceFull, enter = fadeIn(), exit = fadeOut()) {
            val replyText = (state.messages.lastOrNull { it is ChatMessage.Assistant && it.text.isNotBlank() } as? ChatMessage.Assistant)?.text
            // Keyed on the text, so a streaming reply elsewhere in the list doesn't redo it every delta.
            val lastReply = remember(replyText) { replyText?.let(::speakableText)?.takeIf { it.isNotBlank() } }
            VoiceScreen(
                // Not while it fades out after the call ends, or a Back then would fold the next call.
                active = voiceFull,
                chatTitle = title,
                state = voiceChat,
                lastReply = lastReply,
                captions = voiceCaptions,
                onToggleCaptions = { voiceCaptions = !voiceCaptions },
                onMinimize = { voiceFolded = true },
                onSkip = actions::skipSpeech,
                onMute = actions::toggleVoiceMute,
                onEnd = actions::stopVoiceChat,
                onType = {
                    typeAfterVoice = true
                    actions.stopVoiceChat()
                },
            )
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
    mentions: List<Bot>,
    onMention: (Bot) -> Unit,
    notice: String?,
    editing: Boolean,
    comments: List<PendingComment>,
    focusComment: Long?,
    onCommentFocused: () -> Unit,
    composerFocus: FocusRequester,
    composerFold: ComposerFold,
    onComposerShrink: (Int) -> Unit,
    onOpenModels: () -> Unit,
    onAttach: () -> Unit,
    onDictate: () -> Unit,
    onVoiceChat: () -> Unit,
    /** Opens the goal and loops sheet from the strip above the composer. */
    onOpenControl: () -> Unit,
    /** The voice chat is open over the whole screen. */
    voiceFull: Boolean,
    onExpandVoice: () -> Unit,
) {
    (state.attachment as? Attachment.Failed)?.let {
        Banner(it.message, actionLabel = "Retry", onAction = actions::retry)
    }
    state.error?.let { Banner(it, actionLabel = null, onAction = actions::dismissError) }
    state.notices.forEach { notice -> key(notice.key) { NoticeBanner(notice, actions::dismissNotice) } }
    // Turned away, not failed: the prompt is back in the composer, ready to go again. Once the composer is
    // emptied there's nothing to send, so the banner can only be closed.
    state.refused?.let { refused ->
        val canResend = actions.composer.text.isNotBlank()
        Banner(
            "${refused.title}. ${refused.detail}",
            actionLabel = "Send again".takeIf { canResend },
            onAction = { if (canResend) actions.send(null) else actions.dismissError() },
            icon = if (refused == SessionRefusal.OpenElsewhere) Lucide.MonitorSmartphone else Lucide.Hourglass,
        )
    }
    attachmentError?.let { Banner(it, actionLabel = null, onAction = actions::dismissAttachmentError) }
    voiceChat.error?.let { Banner(it, actionLabel = null, onAction = actions::dismissVoiceChatError) }
    dictation.error?.let { Banner(it, actionLabel = null, onAction = actions::dismissDictationError) }
    AnimatedVisibility(visible = notice != null) { NoticeLine(notice.orEmpty()) }
    // One place says what's happening: the current action, with the status text and plan folded beneath.
    AnimatedVisibility(visible = state.running, enter = fadeIn(), exit = fadeOut()) {
        ProgressPanel(
            step = currentStep(state),
            status = state.status,
            tool = state.runningTool(),
            todos = state.livePlan(),
            turnKey = runningTurnKey(state),
            onStop = actions::interrupt,
            // Not once it's over: the card fades out with the turn already ended.
            canStop = connected && state.running,
        )
    }
    if (!state.running) TodoPanel(state.todos, live = state.todosLive, hazeState = hazeState)
    // The chat's automation sits here whether or not a turn runs: the goal works on its own between turns.
    SessionControlStrip(state.control, hazeState, onOpen = onOpenControl)

    if (state.inputRequests.isNotEmpty()) {
        // The task's Stop is on the live task card above.
        InputRequestPanel(state.inputRequests, connected, onAnswer = actions::answer, onStop = null)
    } else if (voiceChat.phase != VoicePhase.Off) {
        // Full screen, the voice screen has these; the panel would only animate unseen beneath it.
        if (!voiceFull) {
            VoicePanel(
                hazeState = hazeState,
                state = voiceChat,
                onSkip = actions::skipSpeech,
                onMute = actions::toggleVoiceMute,
                onEnd = actions::stopVoiceChat,
                onExpand = onExpandVoice,
            )
        }
    } else {
        AnimatedVisibility(visible = suggestions.isNotEmpty() && connected, enter = fadeIn(), exit = fadeOut()) {
            // Kept through the fade-out, so the list doesn't empty before it leaves.
            var shown by remember { mutableStateOf(suggestions) }
            if (suggestions.isNotEmpty()) shown = suggestions
            SlashSuggestions(shown, hazeState, onPick = actions::pickSuggestion)
        }
        AnimatedVisibility(visible = mentions.isNotEmpty() && suggestions.isEmpty(), enter = fadeIn(), exit = fadeOut()) {
            var shown by remember { mutableStateOf(mentions) }
            if (mentions.isNotEmpty()) shown = mentions
            MentionSuggestions(
                remember(shown) { shown.map { MentionChoice(it.label, it.handle, it) } },
                hazeState,
                onPick = { choice -> choice.bot?.let(onMention) },
            )
        }
        if (editing) {
            Banner(
                "Editing a message. Sending replaces it and everything after it.",
                actionLabel = "Cancel",
                onAction = actions::cancelEdit,
                icon = Lucide.Pencil,
            )
        }
        Composer(
            editing = editing,
            hazeState = hazeState,
            actions = actions,
            placeholder = placeholder,
            state = state,
            picker = picker,
            connected = connected,
            attachments = attachments,
            dictation = dictation,
            comments = comments,
            focusComment = focusComment,
            onCommentFocused = onCommentFocused,
            focus = composerFocus,
            fold = composerFold,
            onShrink = onComposerShrink,
            onOpenModels = onOpenModels,
            onAttach = onAttach,
            onDictate = onDictate,
            onVoiceChat = onVoiceChat,
        )
    }
}

/** How things stand, by the color of the dot beside a status line. */
internal enum class StatusTone { Ok, Busy, Waiting, Trouble, Neutral }

/** The line under the top bar's title, with its dot. */
internal data class BarStatus(val text: String, val tone: StatusTone)

/** The status line for a chat: the link first, then a question waiting, the agent at work, else where it runs. */
internal fun chatStatus(state: ChatState, connected: Boolean, connectionLabel: String, place: String?): BarStatus = when {
    !connected -> BarStatus(connectionLabel, StatusTone.Trouble)
    state.attachment is Attachment.Attaching -> BarStatus("Opening…", StatusTone.Busy)
    state.inputRequests.isNotEmpty() -> BarStatus("Needs your answer", StatusTone.Waiting)
    state.running -> {
        // Once every step is done the agent is wrapping up, not on a step.
        val plan = state.livePlan()
        val step = planStep(plan)
        // Only the delegation running now: a subagent whose end never arrived mustn't haunt later turns.
        val working = currentStep(state).team.count { it == SubagentStatus.Running }
        BarStatus(
            when {
                plan != null && step != null -> "Working · step $step of ${plan.total}"
                working == 1 -> "Working · 1 subagent"
                working > 1 -> "Working · $working subagents"
                else -> "Working"
            },
            StatusTone.Busy,
        )
    }
    else -> BarStatus(listOfNotNull("Hermes", place).joinToString(" · "), StatusTone.Ok)
}

/** Where the empty chat says Hermes runs: "Hermes on homelab · default profile". */
internal fun greetingPlace(place: String?, profile: String?): String =
    "${place?.let { "Hermes on $it" } ?: "Hermes"} · ${profile ?: "default"} profile"

/**
 * The sessions button, the title with a status line under it, and new chat with the chat's options
 * grouped in a pill on the right.
 */
@Composable
internal fun TopBar(
    title: String,
    titleFace: (@Composable () -> Unit)?,
    status: BarStatus?,
    onOpenSidebar: () -> Unit,
    onNewChat: (() -> Unit)?,
    onOpenMenu: (() -> Unit)?,
    /** In place of New chat and the menu, e.g. a room's own buttons. */
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BarButton(Lucide.PanelLeft, "Sessions", onClick = onOpenSidebar, tint = Theme[colors][textTertiary])
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                titleFace?.invoke()
                Text(
                    title,
                    style = Theme[typography][body].copy(fontWeight = FontWeight.SemiBold, lineHeight = 19.sp),
                    color = Theme[colors][textColor],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (status != null && status.text.isNotBlank()) StatusLine(status)
        }
        // The buttons fill the pill edge to edge, so each keeps its whole 44dp to tap.
        val shape = RoundedCornerShape(Theme[radii][radiusMedium])
        Row(
            Modifier
                .clip(shape)
                .background(Theme[colors][surface], shape)
                .border(1.dp, Theme[colors][stroke], shape),
        ) {
            if (trailing != null) {
                trailing()
            } else {
                BarButton(Lucide.SquarePen, "New chat", onClick = { onNewChat?.invoke() }, enabled = onNewChat != null)
                BarButton(Lucide.Ellipsis, "Chat options", onClick = { onOpenMenu?.invoke() }, enabled = onOpenMenu != null)
            }
        }
    }
}

/** A dot in [BarStatus.tone] with a soft ring, then the status in small grey type. */
@Composable
internal fun StatusLine(status: BarStatus) {
    val (dot, ring) = when (status.tone) {
        StatusTone.Ok -> Theme[colors][success] to Theme[colors][successSoft]
        StatusTone.Busy -> Theme[colors][accentText] to Theme[colors][accentSoft]
        StatusTone.Waiting -> Theme[colors][warning] to Theme[colors][warningSoft]
        StatusTone.Trouble -> Theme[colors][danger] to Theme[colors][dangerSoft]
        StatusTone.Neutral -> Theme[colors][textMuted] to Theme[colors][surface3]
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(12.dp).background(ring, CircleShape).padding(3.dp).background(dot, CircleShape))
        Text(
            status.text,
            style = Theme[typography][caption],
            // Trouble is said in its own color, so a lost link reads at a glance.
            color = if (status.tone == StatusTone.Trouble) Theme[colors][danger] else Theme[colors][textTertiary],
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A 44dp square icon button with no fill, the top bar's style. */
@Composable
internal fun BarButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Theme[colors][textSecondary],
) {
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(MinTouchTarget).clip(RoundedCornerShape(Theme[radii][radiusSmall])).alpha(if (enabled) 1f else 0.35f),
        indication = rememberColoredIndication(Theme[colors][textColor]),
    ) {
        UnstyledIcon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(19.dp))
    }
}

/**
 * An empty chat: the mark, a question in display type with one word in the accent, where Hermes runs, and
 * pills for the other ways to begin than typing.
 */
@Composable
private fun Greeting(
    onAttach: () -> Unit,
    onDictate: () -> Unit,
    onVoiceChat: () -> Unit,
    connected: Boolean,
    dictation: DictationState,
    canAttach: Boolean,
    place: String?,
    profile: String?,
) {
    val accentWord = Theme[colors][accentText]
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.CenterStart) {
        Column(Modifier.fillMaxWidth().padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            AppMark(52.dp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    remember(accentWord) {
                        buildAnnotatedString {
                            append("What are we\n")
                            withStyle(SpanStyle(color = accentWord)) { append("building") }
                            append(" today?")
                        }
                    },
                    style = Theme[typography][display],
                    color = Theme[colors][textColor],
                )
                Text(
                    greetingPlace(place, profile),
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textTertiary],
                )
            }
            // Other ways to begin than typing, named rather than left to the composer's icons.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StartPill(Lucide.Paperclip, "Attach", onClick = onAttach, enabled = canAttach)
                // Tracks the composer's mic: while recording the same tap finishes it.
                if (showMic(dictation)) {
                    StartPill(
                        if (dictation.recording) Lucide.Square else Lucide.Mic,
                        dictateLabel(dictation),
                        onClick = onDictate,
                        enabled = connected && !dictation.transcribing,
                    )
                }
                StartPill(Lucide.AudioLines, "Voice chat", onClick = onVoiceChat, enabled = connected)
            }
        }
    }
}

/** The app's mark: the white H on a Herald blue rounded square, with its glow under it unless it's small. */
@Composable
internal fun AppMark(size: Dp, glow: Boolean = true) {
    val shape = RoundedCornerShape(size * 0.29f)
    Box(
        Modifier
            .size(size)
            .then(if (glow) Modifier.glow(shape, bubbleGlow(HeraldBrandBlue)) else Modifier)
            .background(HeraldBrandBlue, shape),
        contentAlignment = Alignment.Center,
    ) {
        UnstyledIcon(HeraldMark, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

/** A pill on the empty chat: an accent icon and a label on a ringed surface. */
@Composable
private fun StartPill(icon: ImageVector, text: String, onClick: () -> Unit, enabled: Boolean) {
    val shape = RoundedCornerShape(percent = 50)
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .heightIn(min = MinTouchTarget)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape),
        indication = rememberColoredIndication(Theme[colors][textColor]),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(16.dp))
            Text(text, style = Theme[typography][label], color = Theme[colors][textColor], singleLine = true)
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
    /** The conversation goes back further than [messages]; nearing the top loads the page before. */
    olderMessages: Boolean,
    loadingOlder: Boolean,
    bottomInset: Dp,
    /** How high the dock really stands, for what floats just above it. */
    controlsInset: Dp,
    /** Folds the composer by how near the reader is to the end. */
    fold: ComposerFold,
    actions: ChatActions,
    connected: Boolean,
    canChange: Boolean,
    editing: String?,
) {
    var confirm by remember { mutableStateOf<Pair<MessageChange, String>?>(null) }
    val lastPrompt = messages.lastOrNull { it is ChatMessage.User }?.key
    val lastReply = messages.lastOrNull { it is ChatMessage.Assistant && it.text.isNotBlank() }?.key
    val faces = LocalBotFaces.current
    val partners = remember(messages, faces) { messages.map { exchangePartner(it, faces) } }
    // The rewind rules read only the messages.
    val chat = remember(messages) { ChatState(messages = messages) }
    ConfirmChange(confirm, canChange, lastPrompt, chat, actions, onDismiss = { confirm = null })
    // Edit and branch only show while the chat can change; each asks before it does anything.
    fun ask(change: MessageChange, key: String): (() -> Unit)? = if (canChange) ({ confirm = change to key }) else null
    // A rewind asks only when it throws away more than the exchange it redoes.
    fun rewind(change: MessageChange, promptKey: String, key: String, run: () -> Unit): (() -> Unit)? = when {
        !canChange -> null
        chat.discardedAfter(promptKey) > 0 -> ({ confirm = change to key })
        else -> run
    }
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
    // The composer folds by how near the end the list stands; scrolling back up lets go of one opened by a tap.
    DisposableEffect(listState, fold) {
        fold.list = listState
        onDispose {
            fold.list = null
            fold.heldOpen = false
        }
    }
    LaunchedEffect(fold) { snapshotFlow { pinned }.collect { fold.following = it } }
    // Nearing the top loads the page before. It goes in above, keyed, so the list keeps what's on screen in place.
    val nearTop by remember { derivedStateOf { listState.firstVisibleItemIndex <= OLDER_PAGE_LEAD } }
    LaunchedEffect(nearTop, olderMessages, loadingOlder, messages.size) {
        if (nearTop && olderMessages && !loadingOlder) actions.loadOlder()
    }
    val readBackTravel = with(LocalDensity.current) { READ_BACK_TRAVEL.toPx() }
    val readBack = remember(readBackTravel, fold) { ReadBackDetector(readBackTravel) { fold.heldOpen = false } }
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
    val unservable = remember(messages) { unservableEchoes(messages) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(readBack),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.Bottom),
        ) {
            itemsIndexed(messages, key = { _, it -> it.key }) { index, message ->
                // Rows of one exchange with another bot are tied together by a line in that bot's colour.
                val partner = partners.getOrNull(index)
                val linkedAbove = partner != null && partners.getOrNull(index - 1) == partner
                val linkedBelow = partner != null && partners.getOrNull(index + 1) == partner
                val rail = partner?.let { name -> faces.find(name)?.let { Color(0xFF000000 or botLook(it).color.toLong()) } }
                Box(
                    Modifier.drawBehind {
                        if (rail != null && (linkedAbove || linkedBelow)) {
                            // In the list's side margin, clear of avatars and text.
                            val x = -9.dp.toPx()
                            val gap = 20.dp.toPx()
                            drawLine(
                                rail.copy(alpha = 0.55f),
                                start = Offset(x, if (linkedAbove) -gap else 6.dp.toPx()),
                                end = Offset(x, if (linkedBelow) size.height + gap else size.height - 6.dp.toPx()),
                                strokeWidth = 3.dp.toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            )
                        }
                    },
                ) {
                when (message) {
                    is ChatMessage.User -> UserBubble(
                        message,
                        actions,
                        connected,
                        // Any prompt with a stored row is edited in place; the last one without can still go through /undo.
                        // Not while the prompt is still on its way or its delivery is in doubt.
                        editLabel = if (chat.canEdit(message.key)) "Edit" else "Edit last prompt",
                        onEdit = if (chat.canEdit(message.key)) {
                            rewind(MessageChange.Edit, message.key, message.key) { actions.startEdit(message.key) }
                        } else {
                            ask(MessageChange.EditLastPrompt, message.key)
                                .takeIf { message.key == lastPrompt && !message.pending && message.check == null }
                        }.takeIf { editing != message.key },
                        onBranch = ask(MessageChange.Branch, message.key).takeIf { !message.pending && message.check == null },
                        editing = editing == message.key,
                    )
                    is ChatMessage.Assistant -> {
                        val reply = @Composable {
                            AssistantReply(
                                message,
                                onBranch = ask(MessageChange.Branch, message.key),
                                onRegenerate = chat.regenerateTarget(message.key)?.let { prompt ->
                                    rewind(MessageChange.Regenerate, prompt.key, message.key) { actions.regenerate(message.key) }
                                },
                                last = message.key == lastReply,
                                unservable = unservable,
                            )
                        }
                        // An answer to another bot's message folds under it; never while it's still being written.
                        val to = message.repliedTo
                        if (to != null && !message.streaming) RepliedToFold(to, message.text, reply) else reply()
                    }
                    is ChatMessage.Event -> TranscriptEventRow(message.event)
                    is ChatMessage.Command -> CommandOutput(message)
                    is ChatMessage.Notice -> NoticeLine(message)
                }
                }
            }
        }
        AnimatedVisibility(
            visible = awayFromBottom,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp + controlsInset),
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

/** How far the reader scrolls back up through the chat before a composer opened by a tap folds again. */
private val READ_BACK_TRAVEL = 48.dp

/** Over how much of the chat's end the composer goes from folded to open. */
private val FOLD_RANGE = 160.dp

/**
 * How far the composer is open. Scroll-linked: fully open with the end of the chat in view, folding over the
 * [FOLD_RANGE] above it and folded beyond; a tap on the folded line holds it open until the reader scrolls back up.
 */
@Stable
internal class ComposerFold {
    /** The conversation while it's on screen; none (an empty chat) leaves the composer open. */
    var list: LazyListState? by mutableStateOf(null)

    /** The list is following the end; a reply growing there never folds the composer between two frames. */
    var following: Boolean by mutableStateOf(true)

    var heldOpen: Boolean by mutableStateOf(false)

    /** 1 with the end in view, down to 0 once it lies [range] px or more below. */
    fun nearEnd(range: Float): Float {
        val list = list ?: return 1f
        if (following) return 1f
        return foldFor(list.layoutInfo.hiddenBelow(), range)
    }
}

/** How open the composer stands with the chat's end [hiddenBelow] px out of view: 1 at the end, 0 past [range]. */
internal fun foldFor(hiddenBelow: Int, range: Float): Float =
    if (hiddenBelow == Int.MAX_VALUE) 0f else (1f - hiddenBelow / range).coerceIn(0f, 1f)

/**
 * Tells when the reader scrolls back up through the chat: [travel] px of the list actually moving towards older
 * messages, by a drag or the fling after it, calls [onReadBack]. Any move towards the end starts the count over,
 * and the list's own following never counts, as it doesn't pass through nested scrolling.
 */
internal class ReadBackDetector(private val travel: Float, private val onReadBack: () -> Unit) : NestedScrollConnection {
    private var moved = 0f

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // A positive delta pulls the content down, bringing older messages into view.
        moved = if (consumed.y > 0f) moved + consumed.y else 0f
        if (moved >= travel) {
            moved = 0f
            onReadBack()
        }
        return Offset.Zero
    }
}

/** A scroll offset past any message: the list stops it at the end of the last one. */
private const val LIST_END = 1_000_000

/** How many messages from the top the page before starts loading, so it's usually in before the reader gets there. */
private const val OLDER_PAGE_LEAD = 3

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
    is ChatMessage.Event -> event.hashCode()
}

/** Tells apart the ways the link can be down: none, being re-made, or needing a new sign-in. */
internal fun connectionLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Connecting, is ConnectionState.Reconnecting -> "No connection · connecting again…"
    ConnectionState.SessionExpired -> "Signed out · sign in again"
    is ConnectionState.Failed, ConnectionState.Idle, is ConnectionState.Connected -> "No connection"
}

/**
 * What the chat says while the link is being made again: waiting for the network when the gateway can't be reached
 * at all, else reconnecting. Null when it's up, or down for good (the title says so then).
 */
internal fun linkStatus(state: ConnectionState): String? = when (state) {
    is ConnectionState.Reconnecting -> if (state.reason.startsWith(UNREACHABLE)) "Waiting for network…" else "Reconnecting…"
    is ConnectionState.Connecting -> if (state.attempt > 1) "Reconnecting…" else null
    is ConnectionState.Connected, ConnectionState.Idle, ConnectionState.SessionExpired, is ConnectionState.Failed -> null
}

/** GatewayConnection's reason when the ticket request never reached the gateway (no network, or it's down). */
private const val UNREACHABLE = "Can't reach gateway"

/** The banner over the chat while the gateway can't be reached: where, what's being done about it, and Retry now. */
@Composable
private fun OfflineBanner(place: String?, status: String, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .background(Theme[colors][dangerSoft], RoundedCornerShape(Theme[radii][radiusMedium]))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(Lucide.WifiOff, contentDescription = null, tint = Theme[colors][danger], modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            // Announced once as it appears: the line under it changes with every attempt, and isn't.
            Text(
                offlineTitle(place),
                style = Theme[typography][bodySmall].copy(fontWeight = FontWeight.Medium),
                color = Theme[colors][textColor],
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text("$status Your draft is kept.", style = Theme[typography][caption], color = Theme[colors][textSecondary])
        }
        RetryChip("Retry now", onClick = onRetry)
    }
}

/** What the offline banner leads with. */
internal fun offlineTitle(place: String?): String = "Can't reach ${place?.takeIf { it.isNotBlank() } ?: "Hermes"}"

/** A small pill button, drawn 30dp tall with the full touch height around it. */
@Composable
private fun RetryChip(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .heightIn(min = MinTouchTarget)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = Theme[typography][caption].copy(fontWeight = FontWeight.Medium),
            color = Theme[colors][textColor],
            maxLines = 1,
            modifier = Modifier
                .heightIn(min = 30.dp)
                .clip(CircleShape)
                .background(Theme[colors][surface3], CircleShape)
                .indication(interaction, rememberColoredIndication(Theme[colors][textColor]))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * A small floating line saying the chat shown is the copy saved on this device at [savedAt] (epoch ms), its age kept
 * current. With [onRetry], the gateway answered but its transcript didn't load, and the pill offers to try again.
 */
@Composable
private fun SavedCopyPill(savedAt: Long, onRetry: (() -> Unit)?) {
    val now by produceState(Clock.System.now().toEpochMilliseconds()) {
        while (true) {
            delay(30_000)
            value = Clock.System.now().toEpochMilliseconds()
        }
    }
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Row(
        Modifier
            .background(Theme[colors][surfaceElevated], shape)
            .border(1.dp, Theme[colors][strokeStrong], shape)
            .padding(start = 12.dp, end = if (onRetry != null) 4.dp else 12.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(Lucide.History, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(12.dp))
        Text(
            savedCopyLabel(savedAt, now) + if (onRetry != null) " · couldn't load the latest" else "",
            style = Theme[typography][caption],
            color = Theme[colors][textSecondary],
            modifier = Modifier.padding(vertical = 4.dp),
        )
        if (onRetry != null) RetryChip("Try again", onClick = onRetry)
    }
}

/** A small floating line with a spinner, for something under way. */
@Composable
private fun StatusPill(text: String) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Row(
        Modifier
            .background(Theme[colors][surfaceElevated], shape)
            .border(1.dp, Theme[colors][strokeStrong], shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spinner(Modifier.size(12.dp))
        Text(text, style = Theme[typography][caption], color = Theme[colors][textSecondary])
    }
}

/** Desktop's turn marker: the prompt in a full-width box with a tinted fill and outline; replies run bare beneath. */
@Composable
private fun UserBubble(
    message: ChatMessage.User,
    actions: ChatActions,
    connected: Boolean,
    editLabel: String,
    onEdit: (() -> Unit)?,
    onBranch: (() -> Unit)?,
    /** It's in the composer being edited. */
    editing: Boolean,
) {
    val shape = userBubbleShape()
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    // A prompt in doubt always has one: its Edit is there.
    val hasMenu = message.text.isNotBlank() || onEdit != null || onBranch != null || message.check == SendCheck.Unknown
    val review = remember(message.text) { parseReview(message.text) }
    val commentHost = LocalCommentHost.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
        // A long press opens the prompt's actions just under it; the text itself isn't selectable, Copy is in there.
        // The bubble hugs its text, up to most of the row, against the end edge.
        Box(Modifier.fillMaxWidth(USER_BUBBLE_WIDTH), contentAlignment = Alignment.TopEnd) {
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
                    // The prompt isn't selectable, so its comment is on all of it.
                    if (commentHost != null && review == null && message.text.isNotBlank() && !message.pending) {
                        MenuAction("Comment", Lucide.MessageSquare, onClick = {
                            menuOpen = false
                            commentHost.onSelection(
                                SelectionAction.Comment,
                                CommentSource(message.key, "my message that starts “${openingWords(message.text)}”"),
                                SelectionAnchor.whole(message.text),
                            )
                        })
                    }
                    onEdit?.let { MenuAction(editLabel, Lucide.Pencil, onClick = { menuOpen = false; it() }) }
                    // A prompt in doubt has only Check and Resend under it; taking it back to edit is here.
                    if (onEdit == null && message.check == SendCheck.Unknown) {
                        MenuAction("Edit", Lucide.Pencil, onClick = { menuOpen = false; actions.editMessage(message.key) })
                    }
                    onBranch?.let { MenuAction("Branch from here", Lucide.GitBranch, onClick = { menuOpen = false; it() }) }
                },
            ) {
                // A prompt still on its way, or whose delivery is in doubt, is a soft tint with plain text:
                // fading the accent fill would fade the white text with it.
                val waiting = message.pending || message.check != null
                val onBubble = Theme[colors][if (waiting) textSecondary else onUserBubble]
                // A prompt whose delivery is in doubt is marked beside it, on the side away from the edge.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    message.check?.let { DeliveryMark(it) }
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            // A sent prompt glows faintly in its own color; one still on its way doesn't.
                            .then(if (waiting) Modifier else Modifier.glow(shape, bubbleGlow(Theme[colors][userBubble])))
                            .clip(shape)
                            .background(Theme[colors][if (waiting) accentSoft else userBubble], shape)
                            // The prompt being edited is outlined in the text color, which shows on the accent fill.
                            .then(if (editing) Modifier.border(2.dp, Theme[colors][textColor], shape) else Modifier)
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
                                        indication = rememberColoredIndication(onBubble),
                                    )
                                } else Modifier,
                            )
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (message.attachments.isNotEmpty()) SentAttachments(message.attachments)
                        when {
                            review != null -> SentReviewContent(review, onBubble)
                            message.text.isNotEmpty() -> Text(message.text, style = Theme[typography][body], color = onBubble)
                        }
                    }
                }
            }
        }
        if (!message.pending) MessageTimeLabel(message.timestamp)
        if (message.queued) {
            Text("Queued · sends after this task", style = Theme[typography][caption], color = Theme[colors][textTertiary])
        }
        if (editing) {
            Text("Editing in the composer", style = Theme[typography][caption], color = Theme[colors][accentText])
        }
        message.check?.let { UnsettledActions(it, message.key, actions, connected) }
    }
}

/** A prompt's bubble: round all over but the corner by the sender, which tucks in. */
@Composable
internal fun userBubbleShape(): RoundedCornerShape {
    val round = Theme[radii][radiusLarge]
    return RoundedCornerShape(topStart = round, topEnd = round, bottomEnd = Theme[radii][radiusXSmall], bottomStart = round)
}

/** How much of the row a prompt's bubble may take. */
private const val USER_BUBBLE_WIDTH = 0.84f

/** The design's glow: a soft shadow in the color of what casts it, a little below it. */
internal fun bubbleGlow(color: Color) = Shadow(radius = 18.dp, color = color.copy(alpha = 0.4f), offset = DpOffset(0.dp, 6.dp))

/** The mark beside a prompt whose delivery is in doubt: an alert when Hermes didn't get it, a clock while it's unknown. */
@Composable
private fun DeliveryMark(check: SendCheck) {
    val (icon, tint, soft) = when (check) {
        SendCheck.NotReceived -> Triple(Lucide.CircleAlert, danger, dangerSoft)
        SendCheck.Unknown, SendCheck.Checking -> Triple(Lucide.Clock, warning, warningSoft)
    }
    Box(Modifier.size(22.dp).background(Theme[colors][soft], CircleShape), contentAlignment = Alignment.Center) {
        UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][tint], modifier = Modifier.size(13.dp))
    }
}

/** What's said under a prompt whose delivery is in doubt. */
internal fun deliveryLabel(check: SendCheck): String = when (check) {
    SendCheck.Checking -> "Checking whether Hermes got this…"
    SendCheck.Unknown -> "May not have arrived"
    SendCheck.NotReceived -> "Not delivered"
}

/**
 * What to do with a prompt that lost its reply: when it may have arrived, check the transcript again or resend it;
 * when it didn't, resend it or take it back to edit. Nothing is resent on its own; when it may have arrived,
 * Resend first warns it could run twice.
 */
@Composable
private fun UnsettledActions(check: SendCheck, key: String, actions: ChatActions, connected: Boolean) {
    var confirmResend by remember(key) { mutableStateOf(false) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            deliveryLabel(check),
            style = Theme[typography][caption],
            color = Theme[colors][textTertiary],
            modifier = Modifier.padding(end = 4.dp),
        )
        when (check) {
            SendCheck.Checking -> {}
            SendCheck.Unknown -> {
                DeliveryChip("Check", Lucide.SearchCheck, description = "Check delivery", onClick = { actions.checkDelivery(key) })
                DeliveryChip("Resend", Lucide.RotateCw, enabled = connected, onClick = { confirmResend = true })
            }
            SendCheck.NotReceived -> {
                DeliveryChip("Resend", Lucide.RotateCw, enabled = connected, onClick = { actions.resend(key) })
                DeliveryChip("Edit", Lucide.Pencil, onClick = { actions.editMessage(key) })
            }
        }
    }
    Dialog(
        visible = confirmResend,
        onDismissRequest = { confirmResend = false },
        title = "Resend this message?",
        message = "Hermes may already have it. If it does, resending makes Hermes get the same request twice and run it again. " +
            "Tap Check first to be sure.",
        actions = {
            Button("Cancel", onClick = { confirmResend = false }, variant = ButtonVariant.Ghost)
            Button("Resend", onClick = { confirmResend = false; actions.resend(key) })
        },
    )
}

/** A small action under an undelivered prompt: drawn 28dp tall, but the full touch height around it takes the tap. */
@Composable
private fun DeliveryChip(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    description: String = text,
) {
    val interaction = remember { MutableInteractionSource() }
    val pill = CircleShape
    Box(
        Modifier
            .heightIn(min = MinTouchTarget)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clearAndSetSemantics {}
                .alpha(if (enabled) 1f else 0.45f)
                // Grows with large text instead of cutting it off.
                .heightIn(min = 28.dp)
                .clip(pill)
                .background(Theme[colors][surface2], pill)
                .indication(interaction, rememberColoredIndication(Theme[colors][textColor]))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][textColor], modifier = Modifier.size(12.dp))
            Text(text, style = Theme[typography][caption].copy(fontWeight = FontWeight.Medium), color = Theme[colors][textColor])
        }
    }
}

/**
 * The message actions that change the conversation, so they ask first. A regenerate or an edit asks only when it
 * throws away later messages; their key is the reply or the prompt.
 */
private enum class MessageChange { EditLastPrompt, Branch, Regenerate, Edit }

/**
 * Says what a change does before doing it: whether a task starts, and what an edit or a regenerate throws away.
 * Closes itself if the chat stops [allowing][canChange] it while open, or another prompt becomes the last one to edit.
 */
@Composable
private fun ConfirmChange(
    pending: Pair<MessageChange, String>?,
    canChange: Boolean,
    lastPrompt: String?,
    chat: ChatState,
    actions: ChatActions,
    onDismiss: () -> Unit,
) {
    val stale = pending != null &&
        (!canChange || (pending.first == MessageChange.EditLastPrompt && pending.second != lastPrompt))
    LaunchedEffect(stale) { if (stale) onDismiss() }
    // Keeps its words while it fades out, after [pending] has already gone.
    var shown by remember { mutableStateOf(pending) }
    if (pending != null) shown = pending
    // Counted from the prompt a regenerate sends again; kept while the dialog fades out.
    val promptKey = shown?.let { (change, key) -> if (change == MessageChange.Regenerate) chat.regenerateTarget(key)?.key else key }
    var discarded by remember { mutableIntStateOf(0) }
    if (pending != null && promptKey != null) discarded = chat.discardedAfter(promptKey)
    val later = if (discarded == 1) "1 later message" else "$discarded later messages"
    Dialog(
        visible = pending != null,
        onDismissRequest = onDismiss,
        title = when (shown?.first) {
            MessageChange.Branch -> "Branch from here?"
            MessageChange.Regenerate -> "Regenerate this reply?"
            MessageChange.Edit -> "Edit this message?"
            else -> "Edit your last prompt?"
        },
        message = when (shown?.first) {
            MessageChange.Branch ->
                "Copies this chat up to this message into a new chat and opens it. This chat stays as it is. " +
                    "No task starts until you send something in the new chat."
            MessageChange.Regenerate ->
                "This will discard $later, here and on Hermes, and send the prompt again."
            MessageChange.Edit ->
                "Sending the edit will discard $later, here and on Hermes. Nothing changes until you send it."
            else ->
                "Takes your last message and Hermes's reply to it off this chat and puts the message back in the composer. " +
                    "No task starts until you send it again."
        },
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost)
            Button(
                when (shown?.first) {
                    MessageChange.Branch -> "Branch"
                    MessageChange.Regenerate -> "Regenerate"
                    else -> "Edit"
                },
                onClick = {
                    onDismiss()
                    when (pending?.first) {
                        MessageChange.Branch -> actions.branchFrom(pending.second)
                        MessageChange.EditLastPrompt -> actions.editLastPrompt(pending.second)
                        MessageChange.Regenerate -> actions.regenerate(pending.second)
                        MessageChange.Edit -> actions.startEdit(pending.second)
                        null -> {}
                    }
                },
            )
        },
    )
}

@Composable
private fun AssistantReply(
    message: ChatMessage.Assistant,
    onBranch: (() -> Unit)?,
    /** Drops this reply and what follows and sends its prompt again; null when it can't. */
    onRegenerate: (() -> Unit)?,
    /** The newest reply, which comments call "your last reply". */
    last: Boolean,
    /** The chat's generated pictures' paths that can't be loaded, taken out wherever they're repeated. */
    unservable: List<GeneratedImage> = emptyList(),
) {
    val settings = LocalAppSettings.current
    val showReasoning = settings.showReasoning && message.reasoning.isNotBlank()
    // Messages to other bots get their own line below, so they aren't listed again among the tools.
    val listedTools = message.tools.filter { it.name != MESSAGE_AGENT_TOOL }
    val showTools = settings.showToolActivity && listedTools.isNotEmpty()
    // Pictures and files the reply delivered show as themselves, not as Markdown a renderer can't load.
    // Pictures image_generate made show where it ran, as on Desktop: the model may never name them.
    val generated = remember(message.tools) { message.tools.mapNotNull { it.generatedImage }.distinctBy { it.source } }
    val (text, media) = remember(message.text, generated, unservable) { extractReplyMedia(message.text, generated + unservable) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ReplyHeader(message.timestamp.takeUnless { message.streaming })
        // What's happening now is said once, above the composer; the reply keeps only what it's made of.
        if (showReasoning) Reasoning(message.reasoning)
        // While the turn runs its finished steps are listed as they land; once it ends they fold into the pill.
        if (showTools) if (message.streaming) LiveSteps(listedTools, message.key) else Tools(listedTools, message.key)
        if (generated.isNotEmpty()) ReplyMediaList(remember(generated) { generated.map { it.asMedia() } })
        // Shown whatever the tool-activity setting: the work happens out of sight, in other agents.
        message.tools.filter { it.name == "delegate_task" }.forEach { DelegationCard(it) }
        // Messages to other bots, said whatever the tool-activity setting: they're part of the conversation.
        message.tools.filter { it.name == MESSAGE_AGENT_TOOL }.forEach { MessagedLine(it) }
        if (text.isNotBlank()) {
            // Named the way the agent will know it: the newest reply, or an older one by its first words.
            val source = remember(message.key, text, last) {
                CommentSource(
                    messageKey = message.key,
                    label = if (last) LAST_REPLY else "your earlier reply that starts “${openingWords(text)}”",
                    markdown = text,
                )
            }
            // Not while it streams: the text and its blocks are still changing under the selection.
            CommentableSelection(source.takeUnless { message.streaming }) { MarkdownText(text, streaming = message.streaming) }
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
        message.warning?.let { warningText ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                UnstyledIcon(Lucide.TriangleAlert, contentDescription = null, tint = Theme[colors][warning], modifier = Modifier.padding(top = 2.dp).size(14.dp))
                Text(warningText, style = Theme[typography][bodySmall], color = Theme[colors][warning])
            }
        }
        val usage = message.usage?.takeIf { settings.showUsage && !message.streaming }
        val actionable = !message.streaming && text.isNotBlank()
        if (actionable || usage != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                usage?.let {
                    Text(
                        replyUsage(it.input, it.output),
                        style = Theme[typography][caption].copy(fontFamily = Theme[typography][code].fontFamily),
                        color = Theme[colors][textMuted],
                        modifier = Modifier.semantics { contentDescription = "${compactCount(it.input)} tokens in, ${compactCount(it.output)} out" },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (actionable) {
                    // On the end, under the thumb of the hand holding the phone; Copy, the one used most, sits last.
                    // Copy's padding trimmed off the end lines its icon up with the reply's edge; all three lose 4dp on top to sit close under it.
                    val action = Modifier.trimEndTop(end = 0.dp, top = 4.dp)
                    onBranch?.let { ReplyAction(Lucide.GitBranch, "Branch from here", it, action) }
                    onRegenerate?.let { ReplyAction(Lucide.RefreshCw, "Regenerate", it, action) }
                    CopyButton(text, Modifier.trimEndTop(end = 16.dp, top = 4.dp).size(MinTouchTarget))
                }
            }
        }
    }
}

/** Who's speaking above a reply: the small mark, "Hermes", and when it finished. */
@Composable
internal fun ReplyHeader(epochSeconds: Double?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppMark(20.dp, glow = false)
        Text("Hermes", style = Theme[typography][label], color = Theme[colors][textSecondary])
        MessageTimeLabel(epochSeconds)
    }
}

/** One of the quiet icons under a finished reply, at the full touch size. */
@Composable
private fun ReplyAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier) {
    IconButton(icon, contentDescription = contentDescription, onClick = onClick, modifier = modifier, tint = Theme[colors][textTertiary], iconSize = 16.dp)
}

/** A reply's tokens, read in and written out: "18.4k ↓ · 612 ↑". */
internal fun replyUsage(input: Long, output: Long): String = "${compactCount(input)} ↓ · ${compactCount(output)} ↑"

/** When a prompt was sent or a reply finished, in small type; nothing when the setting is off or there's no time. */
@Composable
internal fun MessageTimeLabel(epochSeconds: Double?, modifier: Modifier = Modifier) {
    if (!LocalAppSettings.current.showTimestamps || epochSeconds == null) return
    val use24Hour = uses24HourClock()
    val label = remember(epochSeconds, use24Hour) { messageTime(epochSeconds, use24Hour) }
    if (label.isNotEmpty()) Text(label, style = Theme[typography][caption], color = Theme[colors][textTertiary], modifier = modifier)
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
        if (message.output.isNotEmpty()) {
            val source = remember(message.key, message.command) {
                CommentSource(message.key, "the output of `${message.command}`", code = true)
            }
            CommentableSelection(source.takeUnless { message.running }) {
                Text(
                    AnnotatedString(message.output).withHighlights(LocalTextHighlights.current, highlightColor()),
                    style = Theme[typography][code].copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = if (message.failed) Theme[colors][danger] else Theme[colors][textColor],
                )
            }
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
                        style = Theme[typography][caption].copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em),
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
                        color = if (row.kind == SlashKind.Skill) Theme[colors][accentText] else Theme[colors][textColor],
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

/** One row of the `@` list: who, the handle a pick inserts, and the bot whose face it shows (none for everyone). */
internal data class MentionChoice(val label: String, val handle: String, val bot: Bot?)

/**
 * The `@` list over a composer: who to mention, with faces, names and handles. Frosted over [hazeState]'s
 * content when there is one, else on a plain elevated surface.
 */
@Composable
internal fun MentionSuggestions(choices: List<MentionChoice>, hazeState: HazeState?, onPick: (MentionChoice) -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    val page = Theme[colors][background]
    val faces = LocalBotFaces.current
    val frosted = remember(page) {
        HazeBlurStyle {
            blurEnabled(true)
            blurRadius(20.dp)
            backgroundColor(page)
        }
    }
    Column(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 6.dp)
            .fillMaxWidth()
            .clip(shape)
            .then(if (hazeState != null) Modifier.hazeBlur(input = HazeInput.Sources(hazeState), style = frosted) else Modifier)
            .background(Theme[colors][surfaceElevated].copy(alpha = if (hazeState != null) 0.85f else 1f))
            .border(1.dp, Theme[colors][strokeStrong], shape)
            .padding(vertical = 6.dp),
    ) {
        choices.forEach { choice ->
            Row(
                Modifier.fillMaxWidth().clickable(onClickLabel = "Mention ${choice.label}") { onPick(choice) }.padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (choice.bot != null) {
                    BotAvatar(choice.bot, faces.picture(choice.bot), size = 24.dp)
                } else {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        UnstyledIcon(Lucide.Users, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(18.dp))
                    }
                }
                Text(choice.label, style = Theme[typography][body].copy(fontWeight = FontWeight.SemiBold), color = Theme[colors][textColor], maxLines = 1)
                Text("@${choice.handle}", style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A tappable one-line header that opens to show more, as the reply's reasoning does. */
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
private fun Tools(tools: List<ToolActivity>, messageKey: String) {
    var expanded by remember { mutableStateOf(false) }
    val icons = remember(tools) { tools.map { toolIcon(it.name) }.distinct().take(3) }
    val (ran, took) = workedLabel(tools)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // A pill: up to three of the kinds of tool it used, overlapping, then how many ran and for how long.
        // It's drawn 36dp tall, but the full touch height around it takes the tap.
        val pill = CircleShape
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .heightIn(min = MinTouchTarget)
                .clickable(interaction, indication = null) { expanded = !expanded }
                .semantics {
                    contentDescription = listOfNotNull(ran, took).joinToString(", ")
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .heightIn(min = 36.dp)
                    .clip(pill)
                    .background(Theme[colors][surface], pill)
                    .border(1.dp, Theme[colors][stroke], pill)
                    .indication(interaction, rememberColoredIndication(Theme[colors][textSecondary]))
                    .padding(start = 6.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) { icons.forEach { ToolKindIcon(it) } }
                Text(ran, style = Theme[typography][caption], color = Theme[colors][textSecondary])
                took?.let { Text("· $it", style = Theme[typography][caption], color = Theme[colors][textMuted]) }
                UnstyledIcon(
                    if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                    contentDescription = null,
                    tint = Theme[colors][textMuted],
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        if (expanded) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Theme[colors][stroke], RoundedCornerShape(Theme[radii][radiusMedium]))
                    .background(Theme[colors][surface], RoundedCornerShape(Theme[radii][radiusMedium]))
                    .padding(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    tools.forEach { ToolRow(it, messageKey) }
                }
            }
        }
    }
}

/**
 * A running reply's finished steps, newest last: a check (or a cross) in a soft circle, what was done, and
 * how long it took. The step in hand is on the live task card instead. Only the last few show.
 */
@Composable
private fun LiveSteps(tools: List<ToolActivity>, messageKey: String) {
    val done = tools.filterNot { it.running }
    if (done.isEmpty()) return
    val shown = done.takeLast(LIVE_STEPS_SHOWN)
    Column(Modifier.padding(start = 2.dp)) {
        if (done.size > shown.size) {
            val earlier = done.size - shown.size
            Text(
                if (earlier == 1) "1 earlier step" else "$earlier earlier steps",
                style = Theme[typography][caption],
                color = Theme[colors][textMuted],
                modifier = Modifier.padding(start = 30.dp, bottom = 4.dp),
            )
        }
        shown.forEach { key(it.id) { LiveStepRow(it, messageKey) } }
    }
}

/** A finished step; tapped, it opens to what the tool was given and gave back, as in the pill's list. */
@Composable
private fun LiveStepRow(tool: ToolActivity, messageKey: String) {
    val step = remember(tool) { toolDone(tool) }
    var open by remember(tool.id) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(Theme[radii][radiusSmall]))
            .clickable(onClickLabel = if (open) "Hide details" else "Show details") { open = !open },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).background(Theme[colors][if (tool.failed) dangerSoft else successSoft], CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            UnstyledIcon(
                if (tool.failed) Lucide.X else Lucide.Check,
                contentDescription = if (tool.failed) "Failed" else null,
                tint = Theme[colors][if (tool.failed) danger else success],
                modifier = Modifier.size(11.dp),
            )
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(step.title, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary], maxLines = 1)
            step.detail?.let {
                Text(
                    it,
                    style = Theme[typography][caption].copy(fontFamily = Theme[typography][code].fontFamily),
                    color = Theme[colors][textSecondary],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .background(Theme[colors][surface], RoundedCornerShape(Theme[radii][radiusXSmall]))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        // A scan flagged its output: said on the row, so it's seen while Stop is still worth pressing.
        if (tool.risk != null) {
            UnstyledIcon(Lucide.ShieldAlert, contentDescription = "Suspicious output", tint = Theme[colors][warning], modifier = Modifier.size(14.dp))
        }
        tool.durationSeconds?.let { Text(formatDuration(it), style = Theme[typography][caption], color = Theme[colors][textMuted]) }
    }
    AnimatedVisibility(visible = open) {
        Box(
            Modifier
                .padding(start = 30.dp, top = 2.dp, bottom = 6.dp)
                .fillMaxWidth()
                .border(1.dp, Theme[colors][stroke], RoundedCornerShape(Theme[radii][radiusMedium]))
                .background(Theme[colors][surface], RoundedCornerShape(Theme[radii][radiusMedium]))
                .padding(12.dp),
        ) { ToolRow(tool, messageKey) }
    }
}

private const val LIVE_STEPS_SHOWN = 4

/** One kind of tool in the pill: its icon in a small circle, ringed in the pill's fill so the circles overlap cleanly. */
@Composable
private fun ToolKindIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(24.dp)
            .border(2.dp, Theme[colors][surface], CircleShape)
            .padding(1.dp)
            .background(Theme[colors][surface3], CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(11.dp))
    }
}

/**
 * "Ran 3 tools" and the time they took between them ("42s"), for the reply's tool pill; no time when none
 * was timed. It's the tools' own time added up, not the turn's: calls run side by side each count.
 */
internal fun workedLabel(tools: List<ToolActivity>): Pair<String, String?> {
    val timed = tools.mapNotNull { it.durationSeconds }
    val ran = if (tools.size == 1) "Ran 1 tool" else "Ran ${tools.size} tools"
    return ran to timed.takeIf { it.isNotEmpty() }?.let { formatDuration(it.sum()) }
}

/** A tool's kind, as a small icon. */
internal fun toolIcon(name: String): ImageVector = when {
    name == "terminal" || name == "process" || name == "execute_code" -> Lucide.SquareTerminal
    name == "patch" || name == "write_file" -> Lucide.FileDiff
    name == "read_file" || name == "search_files" -> Lucide.FileText
    name.startsWith("web_") || name.startsWith("browser_") -> Lucide.Globe
    name == "image_generate" || name == "vision_analyze" -> Lucide.Image
    name == "memory" || name == "session_search" -> Lucide.Brain
    name == "todo" -> Lucide.ListTodo
    name == "delegate_task" -> Lucide.Users
    else -> Lucide.Wrench
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
internal fun currentAction(state: ChatState): String = currentStep(state).let { step ->
    val title = step.teamProgress?.let { "${step.title} · $it" } ?: step.title
    step.detail?.let { "$title: $it" } ?: title
}

/**
 * What the agent is doing now, in words, and what it's doing it to (a path, a command) when a tool says.
 * [team] is how each subagent of the running delegation stands, in task order.
 */
internal data class LiveStep(val title: String, val detail: String? = null, val team: List<SubagentStatus> = emptyList()) {
    /** "1 of 3 done", while subagents work; "finished" once any of those ended without succeeding. */
    val teamProgress: String? get() = team.takeIf { it.isNotEmpty() }?.let { all ->
        val ended = all.filterNot { it.live }
        "${ended.size} of ${all.size} ${if (ended.all { it == SubagentStatus.Done }) "done" else "finished"}"
    }
}

/** [currentAction] in its two parts, for the live task card. */
internal fun currentStep(state: ChatState): LiveStep {
    if (state.inputRequests.isNotEmpty()) return LiveStep("Waiting for your answer")
    state.runningTool()?.let { tool ->
        if (tool.name == "delegate_task") {
            // The same rows the delegation's cards show, tasks not started yet included.
            val team = subagentRows(tool, state.subagents).map { it.status }
            if (team.isNotEmpty()) return LiveStep(tool.name.toolVerb(), team = team)
        }
        return LiveStep(tool.name.toolVerb(), tool.firstDetailLine())
    }
    state.livePlan()?.items?.firstOrNull { it.status == TodoStatus.InProgress }?.let { return LiveStep(it.content) }
    state.status?.takeIf { it.isNotBlank() }?.let { status ->
        // A turn woken by another bot's reply arriving says so, not the runner's command line.
        return LiveStep(if (BOT_DELIVERY_STATUS.containsMatchIn(status)) "Reading another bot's reply" else status)
    }
    return LiveStep(state.thinkingFrame ?: "Thinking…")
}

/** What the tool is working on, in one line: its description, else what it was given when that's plain (a command, a query) rather than JSON. */
private fun ToolActivity.firstDetailLine(): String? =
    (detail?.takeIf { it.isNotBlank() } ?: input?.takeUnless { it.trimStart().let { s -> s.startsWith("{") || s.startsWith("[") } })
        ?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }

/** The step of a live plan in hand, counted from 1; null once nothing is left to do. */
internal fun planStep(plan: TodoList?): Int? = plan?.takeIf { it.total > 0 && it.active }?.let { minOf(it.done + 1, it.total) }

/**
 * The running turn, by its streaming reply's key, for timing it from when it opened here. A prompt's own
 * time won't do: a queued one is stamped when it was queued, and a turn started elsewhere may have none.
 */
internal fun runningTurnKey(state: ChatState): String? =
    if (!state.running) null else state.messages.lastOrNull { it is ChatMessage.Assistant && it.streaming }?.key

/** A finished step in a running reply, said in the past: "Ran" and the command, "Read" and the file. */
internal fun toolDone(tool: ToolActivity): LiveStep {
    val verb = when (tool.name) {
        "terminal", "shell", "bash", "execute_code" -> "Ran"
        "read_file", "file_read", "web_extract", "browser", "fetch" -> "Read"
        "write_file", "patch", "edit_file" -> "Edited"
        "web_search", "search", "search_files", "session_search" -> "Searched"
        "delegate_task" -> "Delegated"
        MESSAGE_AGENT_TOOL -> "Messaged"
        else -> return LiveStep("Used ${tool.name.replace('_', ' ')}", tool.firstDetailLine())
    }
    return tool.firstDetailLine()?.let { LiveStep(verb, it) } ?: LiveStep("$verb with ${tool.name.replace('_', ' ')}")
}

/** The status of a turn started by a bot-to-bot delivery finishing (tools/bot_mode_dm.py's runner). */
private val BOT_DELIVERY_STATUS = Regex("""Background Process Finished:.*(?:bot_mode_dm\.py|--run-delivery)""", RegexOption.IGNORE_CASE)

/** "terminal" reads as "Running a command", and so on; other tools as "Using <name>". */
private fun String.toolVerb(): String = when (this) {
    "terminal", "shell", "bash" -> "Running a command"
    "read_file", "file_read" -> "Reading a file"
    "write_file", "patch", "edit_file" -> "Editing a file"
    "web_search", "search" -> "Searching the web"
    "web_extract", "browser", "fetch" -> "Reading a web page"
    "delegate_task" -> "Working with subagents"
    MESSAGE_AGENT_TOOL -> "Messaging another bot"
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

/** A gateway notice, with its level's icon and color; × hides it. A timed one goes by itself (ChatSession). */
@Composable
private fun NoticeBanner(notice: GatewayNotice, onDismiss: (String) -> Unit) {
    val (icon, tint) = when (notice.level) {
        GatewayNotice.Level.Info -> Lucide.Info to Theme[colors][accentText]
        GatewayNotice.Level.Warning -> Lucide.TriangleAlert to Theme[colors][warning]
        GatewayNotice.Level.Error -> Lucide.CircleAlert to Theme[colors][danger]
        GatewayNotice.Level.Success -> Lucide.CircleCheck to Theme[colors][success]
    }
    Banner(notice.text, actionLabel = null, onAction = { onDismiss(notice.key) }, icon = icon, tint = tint)
}

@Composable
private fun Banner(message: String, actionLabel: String?, onAction: () -> Unit, icon: ImageVector? = null, tint: Color? = null) {
    Surface(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // A problem by default; [icon] says it's a state instead.
            UnstyledIcon(
                icon ?: Lucide.CircleAlert,
                contentDescription = null,
                tint = tint ?: if (icon == null) Theme[colors][danger] else Theme[colors][accentText],
                modifier = Modifier.size(16.dp),
            )
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
 * and voice chat, the model and thinking level as quiet text, and the round send button. While a task runs,
 * Stop is on the live task card above; a message typed mid-task gets a Send that steers, queues or stops and
 * sends as Settings says; holding it picks another way for that message.
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
    comments: List<PendingComment>,
    focusComment: Long?,
    onCommentFocused: () -> Unit,
    focus: FocusRequester,
    /** How near the end the reader is, which folds the composer to one line away from it. */
    fold: ComposerFold,
    /** How many px shorter than open the composer stands right now. */
    onShrink: (Int) -> Unit,
    onOpenModels: () -> Unit,
    onAttach: () -> Unit,
    onDictate: () -> Unit,
    onVoiceChat: () -> Unit,
    /** A prompt is being edited: it can't go out mid-turn, as the gateway won't cut the chat while a task runs. */
    editing: Boolean,
) {
    // Attachments or comments alone are sendable: the gateway gets Desktop's image prompt or the file references.
    val hasText = actions.composer.text.isNotBlank() || attachments.isNotEmpty() || comments.isNotEmpty()
    val runningSend by actions.runningSend.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(Theme[radii][radiusXLarge])
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
    // How open the composer stands, 1 open to 0 folded to a line. It tracks the scroll as it goes, and springs
    // across when a tap, the keyboard or a draft holds it open or lets go.
    val open = remember { Animatable(1f) }
    // Something being written keeps it open: worked out here, where the text is read anyway, not in ChatView,
    // which would then recompose on every keystroke.
    val busyNow by rememberUpdatedState(hasText || dictation.active)
    val range = with(LocalDensity.current) { FOLD_RANGE.toPx() }
    LaunchedEffect(fold, range) {
        snapshotFlow {
            val nearEnd = fold.nearEnd(range)
            nearEnd to if (fold.heldOpen || focused || busyNow) 1f else nearEnd
        }.collectLatest { (nearEnd, target) ->
            // At the end it's open anyway; scrolling up from there folds it again.
            if (nearEnd >= 1f) fold.heldOpen = false
            if (abs(target - open.value) > 0.2f) open.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow)) else open.snapTo(target)
        }
    }
    // The open height while folded, so the dock can report how much it gave back.
    val openHeight = remember { IntArray(1) }
    DisposableEffect(Unit) { onDispose { onShrink(0) } }
    Layout(
        contents = listOf(
            {
                Column(
                    Modifier.padding(start = 4.dp, end = 6.dp, top = if (attachments.isEmpty() && comments.isEmpty()) 14.dp else 8.dp, bottom = 6.dp),
                ) {
                    if (comments.isNotEmpty()) {
                        CommentTray(comments, focusComment = focusComment, onFocused = onCommentFocused, onRemove = actions::removeComment)
                    }
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
                        modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = 12.dp).focusRequester(focus),
                    ) {
                        TextInput(
                            placeholder = {
                                Text(
                                    when {
                                        !connected -> "Reconnecting to Hermes…"
                                        comments.isNotEmpty() -> "Anything else? (optional)"
                                        // Mid-task a message joins the running task, by Queue or Steer.
                                        state.running -> "Add to this task…"
                                        else -> placeholder
                                    },
                                    style = Theme[typography][body],
                                    color = Theme[colors][textTertiary],
                                )
                            },
                        )
                    }
                    // The same test send() makes: with attachments, slash text goes out as a prompt, not a command.
                    val command = attachments.isEmpty() && SlashCommand.parse(actions.composer.text.toString().trim()) != null
                    // A steer can't carry files; send() queues those instead, so say that.
                    val runningMode = sendModeFor(running = true, picked = null, setting = runningSend, withAttachments = attachments.isNotEmpty())!!
                    // Mid-turn, say what Send will do with the message.
                    if (state.running && editing) {
                        Text(
                            "Send the edit once this reply finishes.",
                            style = Theme[typography][caption],
                            color = Theme[colors][textTertiary],
                            modifier = Modifier.padding(start = 12.dp, top = 10.dp),
                        )
                    } else if (state.running && hasText && command) {
                        CommandHint()
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        // The buttons are full 48dp targets, which already space their icons apart.
                    ) {
                        AttachButton(onClick = onAttach, enabled = attachments.size < OutgoingAttachment.MAX_COUNT)
                        // Queue and Steer need the room mid-task, so the model pill folds to its sparkle meanwhile.
                        val segments = state.running && hasText && !editing && !command
                        Box(Modifier.weight(1f).padding(start = 2.dp), contentAlignment = Alignment.CenterStart) {
                            ModelPill(state, picker, onClick = onOpenModels, compact = segments)
                        }
                        if (showMic(dictation)) Snug { DictationButton(dictation, onClick = onDictate, enabled = connected) }
                        // Voice chat waits for the task to end, so while one runs its place goes to the ways to send.
                        if (!state.running) Snug { VoiceChatButton(onClick = onVoiceChat, enabled = connected && !dictation.active) }
                        // Stop is on the live task card above; a message typed meanwhile is queued or steers the task.
                        if (state.running && hasText && !editing) {
                            if (command) {
                                // A command runs at once; there's nothing to choose.
                                Snug { SendButton(SendIcon.Send, onClick = { actions.send() }, enabled = connected) }
                            } else {
                                RunningSendSegments(
                                    mode = runningMode,
                                    attachments = attachments.isNotEmpty(),
                                    enabled = connected,
                                    onSend = actions::send,
                                )
                            }
                        } else {
                            // While a task runs this only waits: nothing typed yet, or an edit, which goes once it ends.
                            Snug { SendButton(SendIcon.Send, onClick = { actions.send() }, enabled = connected && hasText && !state.running) }
                        }
                    }
                }
            },
            {
                FoldedComposer(
                    placeholder = when {
                        !connected -> "Reconnecting to Hermes…"
                        state.running -> "Add to this task…"
                        else -> placeholder
                    },
                    state = state,
                    connected = connected,
                    dictation = dictation,
                    canAttach = attachments.size < OutgoingAttachment.MAX_COUNT,
                    onOpen = {
                        // Tapping the line means writing: open, and the field (laid out all along) takes focus.
                        fold.heldOpen = true
                        focus.requestFocus()
                    },
                    onAttach = onAttach,
                    onDictate = onDictate,
                    onVoiceChat = onVoiceChat,
                )
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
            .onSizeChanged { onShrink((openHeight[0] - it.height).coerceAtLeast(0)) }
            // The dock floats over the chat on a soft shadow.
            .dropShadow(shape, DockShadow)
            .clip(shape)
            // Nearly solid: the conversation beneath only just shows through, blurred.
            .hazeBlur(input = HazeInput.Sources(hazeState), style = frosted)
            .background(Theme[colors][surface].copy(alpha = 0.92f))
            .border(1.dp, if (focused) Theme[colors][textTertiary] else Theme[colors][stroke], shape)
            .onFocusChanged { focused = it.hasFocus },
    ) { (openMeasurables, lineMeasurables), constraints ->
        val loose = constraints.copy(minHeight = 0)
        val full = openMeasurables.first().measure(loose)
        val line = lineMeasurables.first().measure(loose)
        openHeight[0] = full.height
        val progress = open.value
        val height = (line.height + (full.height - line.height) * progress).roundToInt()
        // Both sit on the bottom edge, so + stays put while the field folds away above it. One fades out before
        // the other fades in, so their buttons never show on top of each other.
        layout(constraints.maxWidth, height) {
            full.placeWithLayer(0, height - full.height) { alpha = ((progress - 0.5f) / 0.5f).coerceIn(0f, 1f) }
            // On top, taking the taps, until the composer is half open.
            if (progress < 0.5f) line.placeWithLayer(0, height - line.height) { alpha = (1f - progress / 0.4f).coerceIn(0f, 1f) }
        }
    }
}

/**
 * The composer folded to one line while reading back, as ChatGPT does: + on the left, the placeholder to tap
 * and start writing, dictation, and voice chat. The model and the field
 * come back with the rest when it opens.
 */
@Composable
private fun FoldedComposer(
    placeholder: String,
    state: ChatState,
    connected: Boolean,
    dictation: DictationState,
    canAttach: Boolean,
    onOpen: () -> Unit,
    onAttach: () -> Unit,
    onDictate: () -> Unit,
    onVoiceChat: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AttachButton(onClick = onAttach, enabled = canAttach)
        UnstyledButton(
            onClick = onOpen,
            modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).semantics { contentDescription = "Write a message" },
            indication = null,
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    placeholder,
                    style = Theme[typography][body],
                    color = Theme[colors][textTertiary],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showMic(dictation)) Snug { DictationButton(dictation, onClick = onDictate, enabled = connected) }
        // Stop is on the live task card above, so this stays voice chat, which waits for the task to end.
        Snug { VoiceChatButton(onClick = onVoiceChat, enabled = connected && !dictation.active && !state.running) }
    }
}

/** The mic shows with the Dictation setting on, and while a dictation still runs, so it can be finished. */
@Composable
private fun showMic(dictation: DictationState): Boolean = LocalAppSettings.current.dictation || dictation.active

/** Says that a command typed while a task runs goes at once, not into the task. */
@Composable
private fun CommandHint() {
    Text(
        "Commands run right away.",
        style = Theme[typography][caption],
        color = Theme[colors][textTertiary],
        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp),
    )
}

/** The two ways a message typed mid-task is shown to go: Queue, and Steer or, when that's the setting, Stop & send. */
internal fun runningSegments(mode: RunningSend): List<RunningSend> =
    listOf(RunningSend.Queue, if (mode == RunningSend.StopAndSend) RunningSend.StopAndSend else RunningSend.Steer)

/**
 * Send while a task runs, as a segmented pill: each half sends the message its way, and the one [mode] picks
 * (the setting) is filled. A steer can't carry files, so with [attachments] it's off. Holding either half
 * offers the way that isn't shown.
 */
@Composable
private fun RunningSendSegments(mode: RunningSend, attachments: Boolean, enabled: Boolean, onSend: (RunningSend) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val shown = runningSegments(mode)
    val others = RunningSend.entries.filter { it !in shown && !(it == RunningSend.Steer && attachments) }
    DropdownMenu(
        expanded = menuOpen,
        onExpandedChange = { menuOpen = it },
        // The composer sits at the bottom of the screen; the choices open upward, over the chat.
        above = true,
        items = {
            others.forEach { choice ->
                MenuAction(choice.label, choice.icon, onClick = {
                    menuOpen = false
                    onSend(choice)
                })
            }
        },
    ) {
        // The pill is drawn 38dp tall behind the halves; each half takes taps over the full touch height.
        val track = Theme[colors][surface3]
        Row(
            Modifier
                .heightIn(min = MinTouchTarget)
                .height(IntrinsicSize.Min)
                .alpha(if (enabled) 1f else 0.45f)
                .drawBehind {
                    // Grows with large text so the filled half stays inside it.
                    val h = maxOf(38.dp.toPx(), size.height - 10.dp.toPx())
                    drawRoundRect(track, topLeft = Offset(0f, (size.height - h) / 2), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2))
                }
                .padding(horizontal = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            shown.forEach { way ->
                val on = way == mode
                val usable = enabled && !(way == RunningSend.Steer && attachments)
                val tint = Theme[colors][if (on) onUserBubble else textTertiary]
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .fillMaxHeight()
                        .combinedClickable(
                            enabled = usable,
                            onClick = { onSend(way) },
                            onLongClick = if (others.isEmpty()) null else {
                                {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuOpen = true
                                }
                            },
                            onClickLabel = "Send",
                            onLongClickLabel = "Other ways to send",
                            interactionSource = interaction,
                            indication = null,
                        )
                        .semantics {
                            contentDescription = "${way.label}: ${way.summary}"
                            role = Role.Button
                            // The filled half is the setting's way; a steer can't carry files.
                            selected = on
                            if (enabled && !usable) stateDescription = "Can't carry files"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        Modifier
                            .clearAndSetSemantics {}
                            .alpha(if (usable || !enabled) 1f else 0.45f)
                            .heightIn(min = 32.dp)
                            .clip(CircleShape)
                            .then(if (on) Modifier.background(Theme[colors][userBubble], CircleShape) else Modifier)
                            .indication(interaction, rememberColoredIndication(tint))
                            .padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UnstyledIcon(way.icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
                        Text(
                            way.label,
                            style = Theme[typography][label].copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                            color = tint,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** The dock's shadow: soft and wide, a little below it, so it floats over the chat. */
internal val DockShadow =Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.18f), offset = DpOffset(0.dp, 8.dp))

/** The composer's +. */
@Composable
private fun AttachButton(onClick: () -> Unit, enabled: Boolean) =
    DiscButton(Lucide.Plus, contentDescription = "Add photos or files", onClick = onClick, enabled = enabled, iconSize = 20.dp)

/** Voice chat, on a disc like + and Send so the row reads as three round buttons and the mic. */
@Composable
private fun VoiceChatButton(onClick: () -> Unit, enabled: Boolean) =
    DiscButton(Lucide.AudioLines, contentDescription = "Start a voice chat", onClick = onClick, enabled = enabled, iconSize = 19.dp)

/** A composer button drawn as a 36dp disc, with the full touch target around it. */
@Composable
private fun DiscButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, enabled: Boolean, iconSize: Dp) {
    val tint = if (enabled) Theme[colors][textSecondary] else Theme[colors][textTertiary].copy(alpha = 0.5f)
    DiscTarget(onClick = onClick, enabled = enabled, tint = tint) {
        UnstyledIcon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** A grey disc in the full touch target; a press shows on the disc itself, as on Send. [disc] draws over its fill. */
@Composable
private fun DiscTarget(onClick: () -> Unit, enabled: Boolean, tint: Color, disc: Modifier = Modifier, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(MinTouchTarget)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(ComposerDisc)
                .background(Theme[colors][surface3], CircleShape)
                .then(disc)
                .clip(CircleShape)
                .indication(interaction, rememberColoredIndication(tint)),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** The size the composer's round buttons are drawn at: +, the mic, voice chat and Send. */
private val ComposerDisc = 36.dp

/**
 * Sets a round button [SNUG_TRIM] narrower on each side, so neighbouring discs sit 8dp apart instead of 12dp;
 * each still takes taps over its full touch target, which only overlap at their edges.
 */
@Composable
private fun Snug(button: @Composable () -> Unit) {
    Layout(button) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints)
        val trim = SNUG_TRIM.roundToPx()
        // In a window too narrow for the button at all, there's nothing to trim.
        layout((placeable.width - 2 * trim).coerceAtLeast(0), placeable.height) { placeable.place(-trim, 0) }
    }
}

private val SNUG_TRIM = 2.dp

/** A round button for another composer, like the assistant's lasso, on the same disc as the chat's. */
@Composable
internal fun ComposerButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, enabled: Boolean) =
    DiscButton(icon, contentDescription = contentDescription, onClick = onClick, enabled = enabled, iconSize = 19.dp)

internal enum class SendIcon { Send, Stop }

/**
 * The round send: an accent disc that glows when there's something to send, a quiet grey one otherwise.
 * The disc is drawn at [ComposerDisc], like +; the button around it takes taps over the full [MinTouchTarget].
 */
@Composable
internal fun SendButton(icon: SendIcon, onClick: () -> Unit, enabled: Boolean) {
    val (fill, tint) = sendColors(enabled)
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(MinTouchTarget)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SendDisc(fill, tint, glow = enabled, interaction) {
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
                modifier = Modifier.size(if (icon == SendIcon.Stop) 15.dp else 19.dp),
            )
        }
    }
}

/** Send's disc and icon colors: the accent when it can send, a quiet grey when it can't. */
@Composable
private fun sendColors(enabled: Boolean): Pair<Color, Color> =
    if (enabled) Theme[colors][accent] to Theme[colors][onAccent] else Theme[colors][surface3] to Theme[colors][textMuted]

/** Send's disc, which glows in its own color when it can send; a press on the button around it shows on the disc. */
@Composable
private fun SendDisc(fill: Color, tint: Color, glow: Boolean, interaction: MutableInteractionSource, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(ComposerDisc)
            // Tighter than the bubble's glow: Send sits near the dock's edge, which would cut a wider one off.
            .then(if (glow) Modifier.glow(CircleShape, Shadow(radius = 10.dp, color = fill.copy(alpha = 0.45f), offset = DpOffset(0.dp, 3.dp))) else Modifier)
            .background(fill, CircleShape)
            .clip(CircleShape)
            .indication(interaction, rememberColoredIndication(tint)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The composer's microphone: tap to dictate, tap again to finish; the ring follows your voice. */
@Composable
internal fun DictationButton(state: DictationState, onClick: () -> Unit, enabled: Boolean) {
    if (state.transcribing) {
        // The spinner sits on the mic's disc while the words are written down.
        Box(Modifier.size(MinTouchTarget), contentAlignment = Alignment.Center) {
            Box(Modifier.size(ComposerDisc).background(Theme[colors][surface3], CircleShape), contentAlignment = Alignment.Center) {
                Spinner(Modifier.size(16.dp))
            }
        }
        return
    }
    val recording = state.recording
    val tint = when {
        recording -> Theme[colors][danger]
        enabled -> Theme[colors][textSecondary]
        else -> Theme[colors][textTertiary].copy(alpha = 0.5f)
    }
    DiscTarget(
        onClick = onClick,
        enabled = enabled,
        tint = tint,
        disc = if (recording) Modifier.border(2.dp, tint.copy(alpha = 0.25f + 0.75f * state.level), CircleShape) else Modifier,
    ) {
        UnstyledIcon(
            if (recording) Lucide.Square else Lucide.Mic,
            contentDescription = if (recording) "Finish dictating" else "Dictate",
            tint = tint,
            modifier = Modifier.size(if (recording) 15.dp else 19.dp),
        )
    }
}

/** "✦ Opus 5.5 | Medium ⌄" in a pill, the model and thinking level; opens the model sheet. */
@Composable
private fun ModelPill(
    state: ChatState,
    picker: ModelPickerState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Just the sparkle in a disc, when the row needs the room. */
    compact: Boolean = false,
) {
    val selection = ModelSelection.of(state, picker.catalog)
    val model = selection.model ?: return
    val effort = state.effort(selection.option)
    val fast = state.fast == true
    val name = displayModelName(model)
    val interaction = remember { MutableInteractionSource() }
    // Drawn 36dp tall; the full touch height around it takes the tap.
    Box(
        modifier
            .heightIn(min = MinTouchTarget)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = listOfNotNull(name, effort?.label, "fast mode".takeIf { fast }).joinToString(", ") + ". Change model"
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier
                .clearAndSetSemantics {}
                .heightIn(min = 36.dp)
                .then(if (compact) Modifier.widthIn(min = 36.dp) else Modifier)
                .clip(CircleShape)
                .background(Theme[colors][surface3], CircleShape)
                .indication(interaction, rememberColoredIndication(Theme[colors][textSecondary]))
                .padding(horizontal = if (compact) 0.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            // Fast mode takes the sparkle's place.
            UnstyledIcon(
                if (fast) Lucide.Zap else Lucide.Sparkles,
                contentDescription = null,
                tint = Theme[colors][if (fast) warning else accentText],
                modifier = Modifier.size(if (compact) 16.dp else 13.dp),
            )
            if (compact) return@Row
            Text(
                name,
                style = Theme[typography][caption],
                color = Theme[colors][textSecondary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (effort != null) {
                Text("|", style = Theme[typography][caption], color = Theme[colors][textMuted])
                Text(effort.label, style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            UnstyledIcon(Lucide.ChevronDown, contentDescription = null, tint = Theme[colors][textMuted], modifier = Modifier.size(12.dp))
        }
    }
}

/**
 * Lays the element out [end] and [top] smaller, drawn up and past its end by as much: empty padding inside
 * it stops pushing it away from the edge, while it still takes taps over its whole size.
 */
private fun Modifier.trimEndTop(end: Dp, top: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val dy = top.roundToPx()
    layout(placeable.width - end.roundToPx(), placeable.height - dy) { placeable.placeRelative(0, -dy) }
}
