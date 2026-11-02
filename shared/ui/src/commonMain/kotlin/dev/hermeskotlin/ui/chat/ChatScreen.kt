package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.ui.imeVisible
import dev.hermeskotlin.ui.enterSends
import dev.hermeskotlin.ui.Shortcut
import dev.hermeskotlin.ui.ShortcutHandler
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import dev.hermeskotlin.ui.clearOfWindowControls
import dev.hermeskotlin.ui.hasVoice
import dev.hermeskotlin.ui.windowControlsHeight
import dev.hermeskotlin.ui.isDesktop
import dev.hermeskotlin.ui.frostedPanel
import dev.hermeskotlin.ui.tapVerb
import dev.hermeskotlin.ui.stepChat
import dev.hermeskotlin.core.bots.MentionQuery
import dev.hermeskotlin.core.chat.PathRefKind
import dev.hermeskotlin.core.chat.PathRefQuery
import dev.hermeskotlin.core.chat.completion
import dev.hermeskotlin.core.chat.pathRefQuery
import dev.hermeskotlin.core.chat.screenViews
import dev.hermeskotlin.core.projects.PathEntry
import dev.hermeskotlin.core.projects.ProjectsApi
import kotlin.coroutines.cancellation.CancellationException
import dev.hermeskotlin.core.media.GatewayFileKind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.layout.width
import org.koin.compose.koinInject
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Folder
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
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
import dev.hermeskotlin.core.chat.linkFileUris
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.core.media.LinkPreview
import dev.hermeskotlin.designsystem.components.LocalFileLinks
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import dev.hermeskotlin.designsystem.components.ScrollbarBox
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
import androidx.compose.ui.graphics.compositeOver
import dev.hermeskotlin.core.bots.botLook
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.lerp
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
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
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.ListEnd
import com.composables.icons.lucide.Info
import dev.hermeskotlin.core.chat.MESSAGE_AGENT_TOOL
import dev.hermeskotlin.core.chat.toolInProgress
import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.handle
import dev.hermeskotlin.core.bots.mentionQuery
import dev.hermeskotlin.core.bots.mentionable
import dev.hermeskotlin.ui.bots.BotAvatar
import dev.hermeskotlin.ui.bots.LocalBotFaces
import dev.hermeskotlin.ui.bots.DecodedBotAvatar
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.SearchCheck
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Volume2
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
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.rememberHoverIndication
import dev.hermeskotlin.core.chat.Attachment
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.TranscriptEvent
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
import dev.hermeskotlin.ui.keysLabel
import androidx.compose.foundation.layout.BoxWithConstraints
import dev.hermeskotlin.ui.pet.rememberPetState
import dev.hermeskotlin.ui.voice.DictationState
import dev.hermeskotlin.ui.voice.ReadAloudState
import dev.hermeskotlin.ui.voice.VoiceChatState
import dev.hermeskotlin.ui.voice.VoicePanel
import dev.hermeskotlin.ui.voice.VoicePhase
import dev.hermeskotlin.ui.voice.VoiceScreen
import dev.hermeskotlin.ui.voice.briefError
import dev.hermeskotlin.core.voice.readAloudText
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
import dev.hermeskotlin.designsystem.components.Tooltip
import dev.hermeskotlin.designsystem.components.handCursor
import dev.hermeskotlin.designsystem.components.verticalScrollWithBar
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import dev.hermeskotlin.ui.CopyableOnDesktop
import dev.hermeskotlin.designsystem.components.VerticalScrollbar
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
import dev.hermeskotlin.ui.LocalFocusHeld
import dev.hermeskotlin.ui.KeepScreenOn
import dev.hermeskotlin.ui.rememberChangingConfigurations
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.components.messageTime
import dev.hermeskotlin.ui.components.fullMessageTime
import androidx.compose.ui.text.TextRange
import dev.hermeskotlin.ui.components.uses24HourClock
import kotlinx.coroutines.delay
import androidx.compose.runtime.produceState
import kotlin.time.Clock
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import dev.hermeskotlin.ui.sessions.OtherChatEvent
import dev.hermeskotlin.ui.sessions.SessionsBadge
import dev.hermeskotlin.ui.sessions.RowContextMenu
import dev.hermeskotlin.ui.sessions.RowMenuItem
import dev.hermeskotlin.ui.sessions.onSecondaryPress

@Composable
fun ChatScreen(
    target: ChatTarget,
    onOpenSidebar: () -> Unit,
    onNewChat: () -> Unit,
    /** The open chat's options, given the button's bounds in the window; null until it exists on the gateway. */
    onOpenMenu: ((Rect?) -> Unit)?,
    /** Opens another stored chat by id and title (`/resume`, `/branch`). */
    onOpenChat: (String, String?) -> Unit,
    /** Switches profile (`/profile`); null is the gateway's launch profile. */
    onSwitchProfile: (String?) -> Unit,
    viewModel: ChatViewModel = koinViewModel(),
    /** The desktop's keys reach the chat; off while something covers it. */
    keysEnabled: Boolean = true,
    /** What the Sessions button says about the other chats. */
    sessionsBadge: SessionsBadge = SessionsBadge(),
    /** Something that just happened in another chat, for the banner; null shows none. */
    otherChatBanner: OtherChatEvent? = null,
    onOpenOtherChat: (OtherChatEvent) -> Unit = {},
    onDismissOtherChat: (OtherChatEvent) -> Unit = {},
    /** Goes to the next chat needing the user; false when none does. Null offers no Next. */
    onNextChat: (() -> Boolean)? = null,
) {
    LaunchedEffect(target) { viewModel.open(target) }
    val transcript = viewModel.transcript.collectAsStateWithLifecycle().value
    val state = transcript.state
    // The same chat reopened with a new position (a notification for it) keeps the last one until the new one is read.
    val readPosition = viewModel.readPosition.collectAsStateWithLifecycle().value
        ?.takeIf { it.target.copy(readUpTo = null) == target.copy(readUpTo = null) }
    val ownsTranscript = transcript.owner?.copy(readUpTo = null) == target.copy(readUpTo = null)
    // Keep this small lifecycle signal observable through stop/start, when transcript collection is paused.
    val screenShown = viewModel.screenShown.collectAsState().value
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
    var focusComposer by remember { mutableIntStateOf(0) }
    // Cleared (here or on another client) while open, there is nothing left to show.
    val hasControl = state.control != null
    LaunchedEffect(hasControl) { if (!hasControl) controlOpen = false }
    var viewing by remember { mutableStateOf<ViewerImage?>(null) }
    var previewing by remember { mutableStateOf<GatewayFile?>(null) }
    // On a wide desktop window a file opens in a rail beside the chat, as Hermes Desktop's right panel, which
    // Ctrl+J puts away and brings back; a picture still opens in the picture viewer.
    var railShown by remember { mutableStateOf(true) }
    LaunchedEffect(previewing) { if (previewing != null) railShown = true }
    // The chat's own width, not the window's: with the sidebar folded away there's room for the rail sooner.
    var paneWidth by remember { mutableStateOf(0.dp) }
    val wide = paneWidth >= RAIL_MIN_PANE
    val docked = previewing?.let { isDesktop && wide && it.kind != GatewayFileKind.Image } == true
    val railFile = previewing.takeIf { docked && railShown }
    // Over a sheet or a viewer, typing and shortcuts are the dialog's, not the chat's behind it.
    val dialogOpen = modelsOpen || attachOpen || petsOpen || journeyOpen || usageOpen || processesOpen ||
        checkpointsOpen || controlOpen || viewing != null || (previewing != null && !docked)
    // Esc in the rail is the viewer's own: back a file, then closed.
    ShortcutHandler(enabled = keysEnabled && docked) { shortcut ->
        (shortcut == Shortcut.ToggleRail).also { if (it) railShown = !railShown }
    }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2_500)
            notice = null
        }
    }
    val attachmentPicker = rememberAttachmentPicker(onPicked = viewModel::addAttachments, onError = viewModel::showAttachmentError)
    val microphone = rememberMicrophonePermission()
    val startVoiceChat = { ringback: Boolean ->
        microphone.withMicrophone(onDenied = { notice = "Voice needs the microphone. Allow it in Android's settings." }) {
            viewModel.startVoiceChat(ringback)
        }
    }
    val toggleDictation = {
        microphone.withMicrophone(onDenied = { notice = "Dictation needs the microphone. Allow it in Android's settings." }) {
            viewModel.toggleDictation()
        }
    }
    // Turning the phone rebuilds the screen without leaving it: the voice the chat owns plays on through it.
    val changingConfigurations = rememberChangingConfigurations()
    // Dictation stops when the app leaves the screen; a voice chat goes on like a call (VoiceController.onBackground).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (!changingConfigurations()) viewModel.voice.onBackground()
        viewModel.onScreen(false)
        // Preserve the actual reading position and remember a turn still running while away.
        viewModel.markLooked()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onScreen(true) }
    // A room replacing the chat takes it off screen without a stop; a reply read aloud stops with it, but not
    // with a turn of the phone.
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.onScreen(false)
            if (!changingConfigurations()) viewModel.stopReading()
        }
    }
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
                // The goal may have been cleared since the menu offered it; an empty sheet would stay up.
                ChatRequest.OpenControl -> controlOpen = viewModel.state.value.control != null
                // `/voice` and the like on the desktop, which has no voice yet: say so, as a silent no-op reads as broken.
                ChatRequest.StartVoice -> if (hasVoice) startVoiceChat(false) else notice = "Voice chat isn't on the desktop yet."
                ChatRequest.StartCall -> if (hasVoice) startVoiceChat(true) else notice = "Voice chat isn't on the desktop yet."
                ChatRequest.StartDictation -> if (hasVoice) toggleDictation() else notice = "Dictation isn't on the desktop yet."
                ChatRequest.FocusComposer -> focusComposer++
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
    // On the desktop, `@` also offers the agent's files, as Hermes Desktop's composer does: `@file:` and `@folder:`
    // references the gateway expands, completed from its machine.
    val pathRef by remember(viewModel) {
        derivedStateOf { viewModel.composer.let { pathRefQuery(it.text.toString(), it.selection.end) } }
    }
    val projectsApi = koinInject<ProjectsApi>()
    var pathEntries by remember { mutableStateOf(emptyList<PathEntry>()) }
    val folderTyped = pathRef?.folder
    LaunchedEffect(folderTyped, connected, target.profile) {
        pathEntries = emptyList()
        if (!isDesktop || folderTyped == null || !connected) return@LaunchedEffect
        delay(PATH_COMPLETION_DELAY_MS)
        // Any failure (an error, a timeout, the socket closing) just offers nothing.
        pathEntries = try {
            projectsApi.completePath(target.profile, folderTyped)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
    val refs = if (isDesktop) refChoices(mention, pathRef, pathEntries) else emptyList()
    val density = LocalDensity.current
    val modelPillBounds = remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.fillMaxSize().onSizeChanged { paneWidth = with(density) { it.width.toDp() } }) {
    CompositionLocalProvider(LocalModelPillBounds provides modelPillBounds) {
    ChatView(
        // A bot's chat is titled "Bot Chat" on the gateway; the bot's name says more.
        title = target.bot?.label ?: state.title?.takeIf { it.isNotBlank() } ?: target.title ?: "New chat",
        titleFace = LocalBotFaces.current.let { faces ->
            faces.find(target.bot?.name)?.let { bot -> { BotAvatar(bot, faces.picture(bot), size = 22.dp) } }
        },
        state = if (readPosition == null || !ownsTranscript) state.copy(historyLoaded = false) else state,
        conversationKey = Triple(target.gateway.gatewayUrl, target.profile, target.storedSessionId),
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
        readAloud = viewModel.voice.reading.collectAsStateWithLifecycle().value,
        suggestions = viewModel.suggestions.collectAsStateWithLifecycle().value,
        suggestedReply = viewModel.nextPrompt.suggestion.collectAsStateWithLifecycle().value,
        mentions = mentions,
        onMention = { bot ->
            val typed = mention ?: return@ChatView
            viewModel.composer.edit { replace(typed.start, typed.end, "@${bot.handle} ") }
        },
        refs = refs,
        onRef = { ref ->
            viewModel.composer.edit {
                replace(ref.start, ref.end, ref.insert)
                placeCursorBeforeCharAt(ref.start + ref.insert.length)
            }
        },
        sprite = viewModel.pets.sprite.collectAsStateWithLifecycle().value,
        // Re-rolled per conversation, kept while a new chat gets its stored id.
        placeholder = remember(target) { (if (target.storedSessionId == null) NEW_CHAT_PROMPTS else FOLLOW_UP_PROMPTS).random() },
        notice = notice,
        actions = viewModel,
        widgetFiles = transcript.widgetFiles,
        editing = viewModel.editing.collectAsStateWithLifecycle().value,
        // Only for the chat it was taken for, not one the screen moved on to (a branch, a fresh start).
        readUpTo = readPosition?.readUpTo?.takeIf { state.storedSessionId == target.storedSessionId },
        onReadLatest = if (readPosition != null && ownsTranscript && screenShown && connected && state.historyRead && !state.running) {
            { viewModel.readLatest(target, state) }
        } else null,
        onOpenSidebar = onOpenSidebar,
        // Already on an untouched new chat: nothing to start over from.
        onNewChat = onNewChat.takeIf { target.storedSessionId != null || state.messages.isNotEmpty() },
        onOpenMenu = onOpenMenu,
        onOpenModels = { modelsOpen = true },
        // A desktop has no camera, and its file dialog picks photos as well: + goes straight to it.
        onAttach = { if (isDesktop) attachmentPicker.pickFiles() else attachOpen = true },
        onDictate = toggleDictation,
        onVoiceChat = { startVoiceChat(false) },
        onOpenPets = { petsOpen = true },
        onOpenControl = viewModel::openControl,
        onViewImage = { viewing = it },
        // The same file again, after Ctrl+J put the rail away, still has to bring it back.
        onOpenFile = { previewing = it; railShown = true },
        onNotice = { notice = it },
        wallpaper = rememberChatWallpaper(),
        place = target.gateway.label,
        profile = target.profile,
        sessionsBadge = sessionsBadge,
        otherChatBanner = otherChatBanner,
        onOpenOtherChat = onOpenOtherChat,
        onDismissOtherChat = onDismissOtherChat,
        onNextChat = onNextChat?.let { next -> { if (!next()) notice = "Nothing waiting" } },
        focusComposer = focusComposer,
        keysEnabled = keysEnabled && !dialogOpen,
        attachmentPicker = attachmentPicker.takeIf { isDesktop },
        rail = railFile?.let { file ->
            {
                FileViewer(file, onDismiss = { previewing = null }, load = viewModel::previewFile, download = viewModel::downloadFile, docked = true)
            }
        },
    )
    }
    }

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
    // A linked picture opens in the picture viewer, like one the reply delivered. A file docked in the rail has it.
    GatewayFileViewer(
        file = previewing.takeIf { !docked },
        onDismiss = { previewing = null },
        load = viewModel::previewFile,
        download = viewModel::downloadFile,
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
        // On the desktop it opens over the pill that opened it, as a desktop app's picker drops from its button.
        // Read in the sheet, so the pill moving doesn't recompose the whole chat.
        anchor = { modelPillBounds.value.takeIf { isDesktop } },
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
    /** Isolates the transcript's scroll state across gateways, profiles and chats. */
    conversationKey: Any? = null,
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
    /** The reply being read aloud, and why the last one couldn't be. */
    readAloud: ReadAloudState = ReadAloudState(),
    suggestions: List<SlashSuggestion>,
    /** The message the gateway's Next Prompt plugin expects next, shown over an empty composer. */
    suggestedReply: SuggestedReply? = null,
    /** Bots matching the `@word` being typed, to pick one to mention. */
    mentions: List<Bot> = emptyList(),
    onMention: (Bot) -> Unit = {},
    /** `@file:` and `@folder:` references matching what's typed (the desktop's). */
    refs: List<RefChoice> = emptyList(),
    onRef: (RefChoice) -> Unit = {},
    sprite: PetSprite?,
    placeholder: String,
    notice: String?,
    actions: ChatActions,
    widgetFiles: WidgetFiles? = null,
    /** The prompt being edited in the composer, whose send replaces it. */
    editing: String? = null,
    /** How far this chat had been read when it was opened (epoch seconds); later messages are marked new. */
    readUpTo: Double? = null,
    onReadLatest: (() -> Unit)? = null,
    onOpenSidebar: () -> Unit,
    onNewChat: (() -> Unit)?,
    onOpenMenu: ((Rect?) -> Unit)?,
    onOpenModels: () -> Unit,
    onAttach: () -> Unit,
    onDictate: () -> Unit,
    onVoiceChat: () -> Unit,
    onOpenPets: () -> Unit,
    /** Opens the goal and loops sheet from the strip above the composer. */
    onOpenControl: () -> Unit = {},
    onViewImage: (ViewerImage) -> Unit,
    /** A tapped link to a file on the gateway. */
    onOpenFile: (GatewayFile) -> Unit = {},
    onNotice: (String) -> Unit,
    /** The chat background from Settings, drawn behind the conversation (and frosted under the composer). */
    wallpaper: ImageBitmap? = null,
    /** The gateway's name, said where Hermes runs: under the title and on the empty chat. */
    place: String? = null,
    /** The profile the chat runs in; null is the launch profile, shown as "default". */
    profile: String? = null,
    sessionsBadge: SessionsBadge = SessionsBadge(),
    otherChatBanner: OtherChatEvent? = null,
    onOpenOtherChat: (OtherChatEvent) -> Unit = {},
    onDismissOtherChat: (OtherChatEvent) -> Unit = {},
    onNextChat: (() -> Unit)? = null,
    /** Goes up each time the composer should take the keyboard (a chat opened to type in). */
    focusComposer: Int = 0,
    /** The desktop's keys reach the chat; off while something covers it. */
    keysEnabled: Boolean = true,
    /** Takes files dropped on the chat and pasted into it (the desktop's). */
    attachmentPicker: AttachmentPicker? = null,
    /** A panel at the right, beside the conversation: the desktop's file rail. */
    rail: (@Composable () -> Unit)? = null,
) {
    val canAttach = attachmentPicker != null && connected && attachments.size < OutgoingAttachment.MAX_COUNT
    var dropping by remember { mutableStateOf(false) }
    // A chat that stops taking files mid-drag takes its drop target away, which then says nothing more.
    LaunchedEffect(canAttach) { if (!canAttach) dropping = false }
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .then(if (wallpaper == null) Modifier.halo() else Modifier)
            .then(if (canAttach) Modifier.fileDrop(onHover = { dropping = it }) { attachmentPicker?.attachFiles(it) } else Modifier),
        contentAlignment = Alignment.TopCenter,
    ) {
        // A voice chat fills the screen until folded to the panel over the chat; each new one opens full.
        var voiceFolded by rememberSaveable { mutableStateOf(false) }
        var voiceCaptions by rememberSaveable { mutableStateOf(true) }
        var typeAfterVoice by remember { mutableStateOf(false) }
        val voiceOn = voiceChat.phase != VoicePhase.Off
        val voiceFull = voiceOn && !voiceFolded
        LaunchedEffect(voiceOn) { if (!voiceOn) voiceFolded = false }
        // A reply read aloud plays to its end with the phone put down, and stops if it leaves the chat
        // (regenerated, or a reload that keys it anew), so its Stop is never gone while it plays.
        KeepScreenOn(readAloud.key != null)
        // A chat whose transcript hasn't loaded yet hasn't lost it.
        LaunchedEffect(readAloud.key, state.messages, state.historyLoaded) {
            val key = readAloud.key ?: return@LaunchedEffect
            if (state.historyLoaded && state.messages.none { it.key == key }) actions.stopReading()
        }
        // The rail takes the right of the screen; the conversation centres in what's left.
        if (rail != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .width(RAIL_WIDTH)
                    .fillMaxHeight()
                    .background(Theme[colors][background])
                    .border(1.dp, Theme[colors][stroke]),
            ) { rail() }
        }
        Column(
            Modifier
                .padding(end = if (rail != null) RAIL_WIDTH else 0.dp)
                // A desktop window's chat takes its whole width, so the bar's buttons sit at its edges and the
                // scrollbar and the wheel work at the window's side; the reading column inside keeps to READING_WIDTH.
                .then(if (isDesktop) Modifier else Modifier.widthIn(max = READING_WIDTH))
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
                sessionsBadge = sessionsBadge,
                onNextChat = onNextChat,
            )
            // The composer floats over the conversation, which scrolls on beneath it; its height pads the list.
            var dockHeight by remember { mutableIntStateOf(0) }
            val dockInset = with(LocalDensity.current) { dockHeight.toDp() }
            // A desktop's empty chat holds the composer up in the middle, under the greeting, as desktop chat apps
            // open; the first message sends it down to the foot. A phone keeps it under the thumb.
            var paneHeight by remember { mutableIntStateOf(0) }
            var paneWidth by remember { mutableIntStateOf(0) }
            val centred = isDesktop && state.historyLoaded && state.messages.isEmpty() && state.historyError == null
            // 0 held in the middle, 1 at the foot.
            val toFoot by animateFloatAsState(if (centred) 0f else 1f, label = "dock")
            // The composer alone, without a list of suggestions over it: what's held in place, so a slash list opens
            // up over the greeting like a menu and the box being typed in doesn't move.
            // The lists' own height, measured as they open and fade out, is taken off: a composer that grows (lines,
            // attachments) stays centred as a whole.
            var listsHeight by remember { mutableIntStateOf(0) }
            val restingDock = (dockHeight - listsHeight).coerceAtLeast(0)
            val restingTop = (paneHeight - restingDock) * (1f + CENTRED_DOCK_BIAS) / 2f
            val dockTop = lerp(restingTop + restingDock - dockHeight, (paneHeight - dockHeight).toFloat(), toFoot).coerceAtLeast(0f)
            // Room under the greeting: down to the resting composer's top wherever it sits.
            val greetingInset = with(LocalDensity.current) { (paneHeight - lerp(restingTop, (paneHeight - dockHeight).toFloat(), toFoot)).coerceAtLeast(0f).toDp() }
            // Reading back up the chat folds the composer to one line; nearing the end opens it again.
            val composerFold = remember { ComposerFold() }
            // Ctrl+F: the matches for what's typed, from the newest back, each brought on screen and marked.
            var findOpen by remember { mutableStateOf(false) }
            val composerFocus = remember { FocusRequester() }
            val focusHeld = LocalFocusHeld.current
            // Closing the find bar hands the keyboard back to the composer rather than to nothing.
            var findClosed by remember { mutableStateOf(0) }
            LaunchedEffect(findClosed) {
                if (findClosed > 0 && isDesktop) runCatching { composerFocus.requestFocus() }
            }
            val findQuery = remember { TextFieldState() }
            val findFocus = remember { FocusRequester() }
            val findText = findQuery.text.toString()
            // What the list shows; find and its scrolling count the same rows.
            val showReasoning = LocalAppSettings.current.showReasoning
            val listed = remember(state.messages, showReasoning) { state.messages.withoutBlankReplies(showReasoning) }
            // The matched messages by key, so older history loading above them keeps the one shown; worked out only
            // while the bar is open, not on every streamed word.
            val matches = remember(listed.size, listed.lastOrNull()?.key, findText, findOpen) {
                if (findOpen) findMatches(listed, findText).map { listed[it].key } else emptyList()
            }
            var foundKey by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(findText, findOpen) { foundKey = matches.lastOrNull() }
            val match = matches.indexOf(foundKey)
            val found = foundKey.takeIf { findOpen && match >= 0 }
            LaunchedEffect(found) {
                val index = listed.indexOfFirst { it.key == found }
                if (index >= 0) {
                    composerFold.onNavigate()
                    composerFold.list?.animateScrollToItem(index)
                }
            }
            if (findOpen) {
                FindBar(
                    query = findQuery,
                    current = match,
                    total = matches.size,
                    focus = findFocus,
                    onStep = { older ->
                        if (matches.isNotEmpty()) foundKey = matches[stepChat(match, matches.size, forward = !older)]
                    },
                    onClose = {
                        findOpen = false
                        findClosed++
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = READING_WIDTH).padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            // The list keeps the open composer's height below the last message, so folding it doesn't move the end
            // and undo the fold; only the jump button and the pet follow the composer's real height.
            var composerShrink by remember { mutableIntStateOf(0) }
            val listInset = with(LocalDensity.current) { (dockHeight + composerShrink).toDp() }
            // What scrolls under the composer is captured here and frosted behind it.
            val hazeState = rememberHazeState()
            // Comments wait in the composer, so the selection menus offer them only while it shows.
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
            // Asked for once: a question waiting in the composer's place doesn't take the keyboard when it's answered.
            LaunchedEffect(focusComposer) {
                // Not laid out yet (the chat still coming up), the field just isn't focused: nothing to type over.
                if (focusComposer > 0 && composerShown) runCatching { composerFocus.requestFocus() }
            }
            val shortcutScope = rememberCoroutineScope()
            // The desktop's keys for the chat; a voice chat over it has the screen.
            ShortcutHandler(enabled = keysEnabled && !voiceFull) { shortcut ->
                when (shortcut) {
                    Shortcut.FocusComposer -> composerShown.also { if (it) composerFocus.requestFocus() }
                    Shortcut.FindInChat -> true.also {
                        // Again while it's open, the query is selected to type over, as a browser's Ctrl+F does.
                        if (findOpen) {
                            findFocus.requestFocus()
                            findQuery.edit { selection = TextRange(0, length) }
                        } else {
                            findOpen = true
                        }
                    }
                    Shortcut.ModelPicker -> true.also { onOpenModels() }
                    Shortcut.PageUp, Shortcut.PageDown -> {
                        val list = composerFold.list ?: return@ShortcutHandler false
                        composerFold.onNavigate()
                        val page = list.layoutInfo.viewportSize.height * PAGE_SCROLL
                        shortcutScope.launch { list.animateScrollBy(if (shortcut == Shortcut.PageUp) -page else page) }
                        true
                    }
                    // Text in the composer keeps Ctrl+Home / Ctrl+End for its caret; an empty one leaves them to the chat.
                    // Any other field with the focus (search, Find) keeps them too.
                    Shortcut.ScrollTop, Shortcut.ScrollBottom -> {
                        val list = composerFold.list ?: return@ShortcutHandler false
                        if (actions.composer.text.isNotEmpty()) return@ShortcutHandler false
                        if (focusHeld.value && !composerFold.typing) return@ShortcutHandler false
                        composerFold.onNavigate()
                        shortcutScope.launch { if (shortcut == Shortcut.ScrollTop) list.scrollToItem(0) else list.animateToEnd() }
                        true
                    }
                    // Esc stops the reply, as its Stop does; with none running it leaves an edit.
                    Shortcut.Escape -> when {
                        findOpen -> true.also {
                            findOpen = false
                            findClosed++
                        }
                        state.running && connected -> true.also { actions.interrupt() }
                        editing != null -> true.also { actions.cancelEdit() }
                        else -> false
                    }
                    // The quick entry's prompt goes out on its own: what's being typed here, and its files, stay.
                    is Shortcut.Prompt -> connected.also { if (it) actions.sendPrompt(shortcut.text) }
                    // A paste with nothing focused: files and pictures attach, text goes in the composer.
                    Shortcut.Paste -> composerShown.also {
                        if (it && !(canAttach && attachmentPicker?.pasteAttachments() == true)) {
                            val text = attachmentPicker?.clipboardText() ?: return@ShortcutHandler false
                            composerFocus.requestFocus()
                            actions.composer.edit {
                                append(text)
                                placeCursorAtEnd()
                            }
                        }
                    }
                    // Typing with nothing focused writes in the composer.
                    is Shortcut.Type -> composerShown.also {
                        if (it) {
                            composerFocus.requestFocus()
                            actions.composer.edit {
                                append(shortcut.text)
                                placeCursorAtEnd()
                            }
                        }
                    }
                    else -> false
                }
            }
            // A new loader each time the link comes back, so cards that failed offline try again; good ones come from the cache.
            val linkPreviewLoader = remember<suspend (String) -> LinkPreview?>(actions, connected) { { url -> actions.loadLinkPreview(url) } }
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
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { paneHeight = it.height; paneWidth = it.width }) {
                Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                    if (wallpaper != null) ChatWallpaper(wallpaper, LocalAppSettings.current.wallpaperStrength, Modifier.fillMaxSize())
                    if (state.historyLoaded && state.messages.isNotEmpty()) {
                        CompositionLocalProvider(
                            LocalMediaLoader provides actions::loadMedia,
                            LocalWidgetFiles provides widgetFiles,
                            LocalLinkPreviewLoader provides linkPreviewLoader,
                            LocalOpenImage provides onViewImage,
                            LocalFileLinks provides { link -> GatewayFile.fromLink(link)?.let(onOpenFile) },
                            LocalNotice provides onNotice,
                            LocalSubagents provides SubagentContext(state.subagents, actions::stopSubagent),
                            LocalAgentScreenChat provides state.storedSessionId,
                            LocalScreenViews provides LocalAgentScreens.current.let { screens ->
                                // Worked out again when a tool call changes, not on every token of a reply.
                                val calls = ScreenViews.calls(state.messages)
                                val newest = ScreenViews.newest(state.messages)
                                remember(calls, newest, screens, state.storedSessionId) {
                                    ScreenViews(screenViews(calls) { screens.took(state.storedSessionId, it) }, newest)
                                }
                            },
                            LocalAgentScreenSend provides actions::sendPrompt,
                            LocalCommentHost provides commentHost,
                        ) {
                            key(conversationKey) {
                                Messages(
                                    listed,
                                    olderMessages = state.olderMessages,
                                    loadingOlder = state.loadingOlder,
                                    savedCopy = state.savedCopyAt != null,
                                    bottomInset = listInset,
                                    controlsInset = dockInset,
                                    fold = composerFold,
                                    actions = actions,
                                    connected = connected,
                                    // A saved copy may lack what changed since; edits wait for the gateway's transcript.
                                    canChange = state.canChangeChat(connected && state.savedCopyAt == null),
                                    editing = editing,
                                    readUpTo = readUpTo,
                                    onReadLatest = onReadLatest,
                                    reading = readAloud.key,
                                    // The voice chat and dictation have the speaker and the microphone.
                                    canReadAloud = hasVoice && !voiceOn && !dictation.active,
                                    found = found,
                                )
                            }
                        }
                    } else Box(
                        Modifier.align(Alignment.TopCenter).widthIn(max = READING_WIDTH).fillMaxSize()
                            .padding(bottom = if (isDesktop) greetingInset else dockInset),
                    ) {
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
                                overComposer = centred,
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
                    // Another chat replied or waits on the user. Kept while it slides away, so its text doesn't go first.
                    var lastBanner by remember { mutableStateOf(otherChatBanner) }
                    if (otherChatBanner != null) lastBanner = otherChatBanner
                    AnimatedVisibility(
                        visible = otherChatBanner != null,
                        enter = fadeIn() + slideInVertically { -it },
                        exit = fadeOut() + slideOutVertically { -it },
                    ) {
                        lastBanner?.let { event ->
                            OtherChatBanner(
                                event,
                                onOpen = { onOpenOtherChat(event) },
                                onDismiss = { onDismissOtherChat(event) },
                            )
                        }
                    }
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
                Column(
                    Modifier
                        .then(if (isDesktop) Modifier.align(Alignment.TopCenter).offset { IntOffset(0, dockTop.roundToInt()) } else Modifier.align(Alignment.BottomCenter))
                        .widthIn(max = READING_WIDTH)
                        .fillMaxWidth()
                        .onSizeChanged { dockHeight = it.height },
                ) {
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
                        readAloud = readAloud,
                        suggestions = suggestions,
                        suggestedReply = suggestedReply,
                        mentions = mentions,
                        onMention = onMention,
                        refs = refs,
                        onRef = onRef,
                        notice = notice,
                        editing = editing != null,
                        comments = comments,
                        focusComment = focusComment,
                        onCommentFocused = { focusComment = null },
                        composerFocus = composerFocus,
                        composerFold = composerFold,
                        onComposerShrink = { composerShrink = it },
                        onListsHeight = { listsHeight = it },
                        onOpenModels = onOpenModels,
                        onAttach = onAttach,
                        onDictate = onDictate,
                        onVoiceChat = onVoiceChat,
                        onOpenControl = onOpenControl,
                        voiceFull = voiceFull,
                        onExpandVoice = { voiceFolded = false },
                        onPasteFiles = { canAttach && keysEnabled && attachmentPicker?.pasteAttachments() == true },
                    )
                }
                // The pet sits on the composer's top edge, over the conversation rather than in the dock's height.
                if (sprite != null && LocalAppSettings.current.showPet && state.inputRequests.isEmpty()) {
                    PetView(
                        sprite = sprite,
                        state = rememberPetState(state),
                        onClick = onOpenPets,
                        // On the desktop the composer may be held up in the middle; the pet sits on it there too.
                        modifier = Modifier.align(Alignment.BottomEnd).padding(
                            // At the composer's end, which keeps to the reading width in a wide window.
                            end = 28.dp + with(LocalDensity.current) { ((paneWidth.toDp() - READING_WIDTH) / 2).coerceAtLeast(0.dp) },
                            bottom = ((if (isDesktop) with(LocalDensity.current) { (paneHeight - dockTop).toDp() } else dockInset) - 6.dp).coerceAtLeast(0.dp),
                        ),
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
                onSendNow = actions::sendVoiceNow,
            )
        }
        // Files dragged over the chat: it says where they go before they're let go, as a desktop app does.
        AnimatedVisibility(visible = dropping && canAttach, enter = fadeIn(), exit = fadeOut()) { DropOverlay() }
    }
}

/** Over the chat while files are dragged onto it: a dashed frame and a word that they'll be attached. */
@Composable
private fun DropOverlay() {
    val accentColor = Theme[colors][accent]
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background].copy(alpha = 0.82f))
            .padding(16.dp)
            .drawBehind {
                val corner = 16.dp.toPx()
                drawRoundRect(
                    color = accentColor,
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            UnstyledIcon(Lucide.Paperclip, contentDescription = null, tint = accentColor, modifier = Modifier.size(28.dp))
            Text("Drop to attach", style = Theme[typography][body].copy(fontWeight = FontWeight.SemiBold), color = Theme[colors][textColor])
            Text("Up to ${OutgoingAttachment.MAX_COUNT} files, 25 MB each.", style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
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
    readAloud: ReadAloudState,
    suggestions: List<SlashSuggestion>,
    suggestedReply: SuggestedReply?,
    mentions: List<Bot>,
    onMention: (Bot) -> Unit,
    refs: List<RefChoice>,
    onRef: (RefChoice) -> Unit,
    notice: String?,
    editing: Boolean,
    comments: List<PendingComment>,
    focusComment: Long?,
    onCommentFocused: () -> Unit,
    composerFocus: FocusRequester,
    composerFold: ComposerFold,
    onComposerShrink: (Int) -> Unit,
    /** How tall the `/` and `@` lists over the composer stand, as they open and fade out. */
    onListsHeight: (Int) -> Unit = {},
    onOpenModels: () -> Unit,
    onAttach: () -> Unit,
    onDictate: () -> Unit,
    onVoiceChat: () -> Unit,
    /** Opens the goal and loops sheet from the strip above the composer. */
    onOpenControl: () -> Unit,
    /** The voice chat is open over the whole screen. */
    voiceFull: Boolean,
    onExpandVoice: () -> Unit,
    /** A paste in the composer: true when it attached files, a picture or a long text (the desktop's). */
    onPasteFiles: () -> Boolean = { false },
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
    voiceChat.error?.let { Banner(briefError(it), actionLabel = null, onAction = actions::dismissVoiceChatError) }
    dictation.error?.let { Banner(briefError(it), actionLabel = null, onAction = actions::dismissDictationError) }
    readAloud.error?.let { Banner(briefError(it), actionLabel = null, onAction = actions::dismissReadAloudError) }
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
                onSendNow = actions::sendVoiceNow,
            )
        }
    } else {
        // Escape puts a desktop's open list away, as a menu's does, until the typing changes; the chat's own Escape
        // (stopping a task) waits for the next press.
        var dismissedAt by remember { mutableStateOf<String?>(null) }
        val typedNow = actions.composer.text.toString()
        val listsAway = dismissedAt != null && dismissedAt == typedNow
        if (dismissedAt != null && !listsAway) dismissedAt = null
        val slashShown = suggestions.isNotEmpty() && connected && !listsAway
        // The suggestion Up and Down have marked on the desktop, reset as the list changes with the typing. The `/`
        // list starts with none, so Enter still sends what's typed; the `@` lists start on the first, which Enter takes.
        val markedState = remember(suggestions, mentions, refs) { mutableIntStateOf(-1) }
        var marked by markedState
        // The pointer marks the row it's on, on the desktop. One lambda per list, so a row's pointer handler, which
        // keeps the one it started with, writes the mark the list shows.
        val onMark: ((Int) -> Unit)? = if (isDesktop) remember(markedState) { { i: Int -> markedState.intValue = i } } else null
        Column(Modifier.onSizeChanged { onListsHeight(it.height) }) {
        AnimatedVisibility(visible = slashShown, enter = fadeIn(), exit = fadeOut()) {
            // Kept through the fade-out, so the list doesn't empty before it leaves.
            var shown by remember { mutableStateOf(suggestions) }
            if (suggestions.isNotEmpty()) shown = suggestions
            SlashSuggestions(shown, hazeState, marked = marked, onPick = actions::pickSuggestion, onMark = onMark)
        }
        AnimatedVisibility(visible = mentions.isNotEmpty() && suggestions.isEmpty() && !listsAway, enter = fadeIn(), exit = fadeOut()) {
            var shown by remember { mutableStateOf(mentions) }
            if (mentions.isNotEmpty()) shown = mentions
            MentionSuggestions(
                remember(shown) { shown.map { MentionChoice(it.label, it.handle, it) } },
                hazeState,
                onPick = { choice -> choice.bot?.let(onMention) },
                marked = if (isDesktop) marked.coerceAtLeast(0) else -1,
                onMark = onMark,
            )
        }
        // `@file:` and `@folder:` references: the kinds while `@` is typed, then the gateway's files and folders.
        AnimatedVisibility(visible = refs.isNotEmpty() && suggestions.isEmpty() && !listsAway, enter = fadeIn(), exit = fadeOut()) {
            var shown by remember { mutableStateOf(refs) }
            if (refs.isNotEmpty()) shown = refs
            MentionSuggestions(
                remember(shown) { shown.map { MentionChoice(it.label, it.shown, null, it.icon) } },
                hazeState,
                onPick = { choice -> shown.firstOrNull { it.shown == choice.handle }?.let(onRef) },
                // While bots are listed too, the keys walk theirs: only one list shows a mark.
                marked = if (isDesktop && mentions.isEmpty()) marked.coerceAtLeast(0) else -1,
                onMark = onMark.takeIf { mentions.isEmpty() },
            )
        }
        }
        // Over an empty composer only: once the user types, their own words are the next message.
        val replyShown = suggestedReply != null && connected && !state.running && !editing && actions.composer.text.isEmpty()
        AnimatedVisibility(visible = replyShown, enter = fadeIn(), exit = fadeOut()) {
            // Kept through the fade-out, so the chip doesn't empty before it leaves.
            var shown by remember { mutableStateOf(suggestedReply) }
            if (suggestedReply != null) shown = suggestedReply
            shown?.let { reply ->
                SuggestedReplyChip(
                    reply.text,
                    hazeState,
                    onUse = { actions.useSuggestedReply(reply) },
                    onDismiss = actions::dismissSuggestedReply,
                )
            }
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
            onKey = { event ->
                // A bot named so far comes before "A file" / "A folder", which any short @ word offers too.
                val listed = when {
                    listsAway -> 0
                    slashShown -> suggestions.size
                    suggestions.isNotEmpty() -> 0
                    mentions.isNotEmpty() -> mentions.size
                    else -> refs.size
                }
                val step = suggestionStep(event)
                val at = if (slashShown) marked else marked.coerceAtLeast(0)
                when {
                    isPasteKey(event) -> !editing && onPasteFiles()
                    isDesktop && listed > 0 && event.key == Key.Escape && event.type == KeyEventType.KeyDown ->
                        true.also { dismissedAt = actions.composer.text.toString() }
                    listed > 0 && step != null -> true.also { marked = moveHighlight(marked, step, listed) }
                    !isPickKey(event) || listed == 0 -> false
                    // Enter takes a `/` suggestion only once one is marked; Tab takes the first too.
                    slashShown -> (marked in suggestions.indices || event.key == Key.Tab).also {
                        if (it) actions.pickSuggestion(suggestions[at.coerceIn(suggestions.indices)])
                    }
                    mentions.isNotEmpty() -> true.also { onMention(mentions[at.coerceIn(mentions.indices)]) }
                    else -> true.also { onRef(refs[at.coerceIn(refs.indices)]) }
                }
            },
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
    onOpenMenu: ((Rect?) -> Unit)?,
    /** In place of New chat and the menu, e.g. a room's own buttons. */
    trailing: (@Composable () -> Unit)? = null,
    /** What the Sessions button says about the other chats. */
    sessionsBadge: SessionsBadge = SessionsBadge(),
    /** A long press on Sessions: the next chat needing the user. */
    onNextChat: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clearOfWindowControls().padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SessionsButton(sessionsBadge, onClick = onOpenSidebar, onNext = onNextChat)
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
            // A desktop's sidebar already names the gateway, so "Hermes · host" would only say it twice: the line
            // shows when there's news (working, a question, a lost link).
            if (status != null && status.text.isNotBlank() && !(isDesktop && status.tone == StatusTone.Ok)) StatusLine(status)
        }
        // The buttons fill the pill edge to edge, so each keeps its whole 44dp to tap. A desktop's are bare toolbar
        // buttons, set apart from the window's own buttons by a gap, so they don't read as one group with them.
        val shape = RoundedCornerShape(Theme[radii][radiusMedium])
        Row(
            if (isDesktop) {
                Modifier.padding(end = 12.dp)
            } else {
                Modifier
                    .clip(shape)
                    .background(Theme[colors][surface], shape)
                    .border(1.dp, Theme[colors][stroke], shape)
            },
            horizontalArrangement = Arrangement.spacedBy(if (isDesktop) 2.dp else 0.dp),
        ) {
            if (trailing != null) {
                trailing()
            } else {
                BarButton(Lucide.SquarePen, "New chat", onClick = { onNewChat?.invoke() }, enabled = onNewChat != null, keys = "Ctrl+N")
                // A plain holder: only the click reads it. The desktop's menu hangs from the button.
                val menuButton = remember { arrayOfNulls<Rect>(1) }
                BarButton(
                    Lucide.Ellipsis,
                    "Chat options",
                    onClick = { onOpenMenu?.invoke(menuButton[0]) },
                    enabled = onOpenMenu != null,
                    modifier = Modifier.onGloballyPositioned { menuButton[0] = it.boundsInWindow() },
                )
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
    /** The keys that do the same, named in the tooltip ("Ctrl+N"). */
    keys: String? = null,
    modifier: Modifier = Modifier,
) {
    val button = @Composable {
        UnstyledButton(
            onClick = onClick,
            enabled = enabled,
            // A desktop toolbar's button is pointer-sized, rounded as its other icon buttons are.
            modifier = modifier
                .size(if (isDesktop) 32.dp else MinTouchTarget)
                .handCursor(enabled)
                .clip(RoundedCornerShape(if (isDesktop) 8.dp else Theme[radii][radiusSmall]))
                .alpha(if (enabled) 1f else 0.35f),
            indication = rememberHoverIndication(Theme[colors][textColor]),
        ) {
            UnstyledIcon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(if (isDesktop) 17.dp else 19.dp))
        }
    }
    // With a mouse, the icon's name shows on a rest over it.
    if (isDesktop) Tooltip(if (keys != null) "$contentDescription (${keysLabel(keys)})" else contentDescription) { button() } else button()
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
    /** The composer sits right under it, in the middle: it stands on the composer rather than in its own middle. */
    overComposer: Boolean = false,
) {
    val accentWord = Theme[colors][accentText]
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = if (overComposer) Alignment.BottomStart else Alignment.CenterStart) {
        Column(Modifier.fillMaxWidth().padding(bottom = if (overComposer) 22.dp else 30.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
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
                if (hasVoice) StartPill(Lucide.AudioLines, "Voice chat", onClick = onVoiceChat, enabled = connected)
            }
        }
    }
}

/** Where an empty desktop chat holds its composer: a little below the middle, the greeting over it. */
private const val CENTRED_DOCK_BIAS = 0.2f

/** How wide the conversation and the composer are at most: lines longer than this get hard to read. */
private val READING_WIDTH = 760.dp

/** The pointer moving onto a list's row [index] marks it, so the keys and the pointer share one highlight. */
private fun Modifier.marksOnHover(index: Int, onMark: ((Int) -> Unit)?): Modifier =
    if (onMark == null) this else pointerInput(index, onMark) {
        awaitPointerEventScope {
            while (true) if (awaitPointerEvent().type == PointerEventType.Enter) onMark(index)
        }
    }

/** The app's mark: the summit at dawn in a rounded square, as on the launcher, with its glow under it unless it's small. */
@Composable
internal fun AppMark(size: Dp, glow: Boolean = true) {
    val shape = RoundedCornerShape(size * 0.29f)
    Image(
        HeraldMark,
        contentDescription = null,
        modifier = Modifier
            .size(size)
            .then(if (glow) Modifier.glow(shape, bubbleGlow(HeraldBrandBlue)) else Modifier)
            .clip(shape),
    )
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
            .handCursor(enabled)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape),
        indication = rememberHoverIndication(Theme[colors][textColor]),
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
    /** Replaced by current history when the gateway answers; its unread position is provisional. */
    savedCopy: Boolean,
    bottomInset: Dp,
    /** How high the dock really stands, for what floats just above it. */
    controlsInset: Dp,
    /** Folds the composer by how near the reader is to the end. */
    fold: ComposerFold,
    actions: ChatActions,
    connected: Boolean,
    canChange: Boolean,
    editing: String?,
    /** How far the chat had been read when it was opened (epoch seconds); later messages are marked new. */
    readUpTo: Double? = null,
    onReadLatest: (() -> Unit)? = null,
    /** The reply being read aloud, by its key. */
    reading: String? = null,
    /** Replies offer Read aloud. */
    canReadAloud: Boolean = true,
    /** The message Find in chat is on, by its key, marked with an outline. */
    found: String? = null,
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
    var readerNavigated by remember(readUpTo) { mutableStateOf(false) }
    // Once the user sends from here, what follows is their own conversation, not news to mark or jump to.
    var unreadSettled by remember(readUpTo) { mutableStateOf(false) }
    // The opening jump is done; only then may reaching the end mark the chat read. A saved copy giving way to
    // current history looks for the unread line again, so it waits for that too.
    var positioned by remember(readUpTo, savedCopy) { mutableStateOf(readUpTo == null || unreadSettled) }
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
    DisposableEffect(listState, fold, readUpTo) {
        fold.list = listState
        fold.onNavigate = { readerNavigated = true }
        onDispose {
            fold.list = null
            fold.onNavigate = {}
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
    // A prompt just sent here is always shown, wherever the reader was. One sent elsewhere, or a stored one coming
    // in with history, only pulls a reader who hasn't moved.
    LaunchedEffect(newest?.key) {
        // A fast submit can acknowledge the local prompt before the screen ever draws it pending.
        val sentHere = newest is ChatMessage.User && (newest.pending || newest.key.startsWith("local-"))
        if (sentHere) {
            unreadSettled = true
            positioned = true
        }
        if (newest is ChatMessage.User && (sentHere || !readerNavigated)) {
            pinned = true
            listState.scrollToItem(messages.lastIndex, LIST_END)
        }
    }
    // What the bottom held when the reader last saw it; anything since is new to them.
    val tail = messages.lastOrNull()?.let { it.key to it.contentSize() }
    var seenTail by remember { mutableStateOf(tail) }
    LaunchedEffect(awayFromBottom, tail) { if (!awayFromBottom) seenTail = tail }
    val newBelow = awayFromBottom && tail != seenTail
    // Where the messages that came since the chat was last read begin. It stays put for the rest of this visit,
    // so reading on doesn't move or remove it under the reader; the next visit starts from the new last look.
    // Older pages load first while every loaded message is still new.
    // A saved copy may omit older unread messages or contain rows the gateway no longer serves.
    // Discover the boundary again against current history, without pulling a reader who has taken over.
    var unreadFrom by remember(readUpTo, savedCopy) { mutableStateOf<String?>(null) }
    var unreadPagesLoaded by remember(readUpTo, savedCopy) { mutableStateOf(0) }
    // The oldest message when a page was last asked for: a reply streaming in reruns this before the page loads.
    var unreadPageAskedAt by remember(readUpTo, savedCopy) { mutableStateOf<String?>(null) }
    LaunchedEffect(readUpTo, messages, olderMessages, loadingOlder, savedCopy) {
        if (readUpTo == null || unreadFrom != null || unreadSettled) return@LaunchedEffect
        if (messages.any { it is ChatMessage.User && it.pending }) {
            unreadSettled = true
            positioned = true
            return@LaunchedEffect
        }
        if (loadingOlder) return@LaunchedEffect
        val key = when (val step = unreadLineStep(messages, readUpTo, olderMessages, unreadPagesLoaded)) {
            UnreadLineStep.LoadOlder -> {
                val oldest = messages.firstOrNull()?.key
                if (oldest != unreadPageAskedAt) {
                    unreadPageAskedAt = oldest
                    unreadPagesLoaded++
                    actions.loadOlder()
                }
                return@LaunchedEffect
            }
            UnreadLineStep.None -> {
                if (!savedCopy) unreadSettled = true
                if (!readerNavigated && messages.isNotEmpty()) {
                    pinned = true
                    listState.scrollToItem(messages.lastIndex, LIST_END)
                }
                positioned = true
                return@LaunchedEffect
            }
            is UnreadLineStep.Above -> step.key
        }
        unreadFrom = key
        val index = messages.indexOfFirst { it.key == key }
        if (index >= 0 && !readerNavigated) {
            // Opened at the line instead of the end, so reading starts where it was left.
            pinned = false
            listState.scrollToItem(index)
        }
        positioned = true
    }
    val readLatest by rememberUpdatedState(onReadLatest)
    val messageCount by rememberUpdatedState(messages.size)
    // Asked again whenever the messages change, not just the tail: a page loaded above, or a row redrawn, can turn
    // down an ask made while the rendered list and the chat's differed for a frame.
    LaunchedEffect(listState, positioned, onReadLatest != null, messages) {
        if (!positioned || onReadLatest == null) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.isNotEmpty() &&
                listState.layoutInfo.totalItemsCount == messageCount && !listState.canScrollForward
        }.collect { atEnd -> if (atEnd) readLatest?.invoke() }
    }
    val unservable = remember(messages) { unservableEchoes(messages) }
    val unreadLine = remember(messages, unreadFrom, readUpTo) { unreadLineKey(messages, unreadFrom, readUpTo) }
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(readUpTo) {
        awaitPointerEventScope {
            var down: Offset? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                // The wheel, or a drag on the list, seen before children consume it. A tap on a link or an icon
                // doesn't move the list, so it leaves the jump to the unread line alone.
                when (event.type) {
                    PointerEventType.Scroll -> readerNavigated = true
                    PointerEventType.Press -> down = change.position
                    PointerEventType.Release -> if (event.changes.none { it.pressed }) down = null
                    PointerEventType.Move -> if (change.pressed && down?.let { (change.position - it).getDistance() > viewConfiguration.touchSlop } == true) {
                        readerNavigated = true
                    }
                }
            }
        }
    }) {
        // The list spans the pane, so the wheel and the scrollbar work at its edges; what's read keeps to the
        // reading width in the middle. Desktop messages sit a little closer together, as a pointer's eye reads them.
        val side = 16.dp + ((maxWidth - READING_WIDTH) / 2).coerceAtLeast(0.dp)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(readBack),
            contentPadding = PaddingValues(start = side, end = side, top = 12.dp, bottom = 12.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(if (isDesktop) 16.dp else 20.dp, Alignment.Bottom),
        ) {
            // By kind, so a row scrolled off is reused for one of its own sort rather than rebuilt (a reply for a reply).
            itemsIndexed(messages, key = { _, it -> it.key }, contentType = { _, it -> it::class }) { index, message ->
                // Rows of one exchange with another bot are tied together by a line in that bot's colour.
                val partner = partners.getOrNull(index)
                // The "New messages" line breaks the tie: it never runs through it.
                val divided = message.key == unreadLine
                val linkedAbove = partner != null && !divided && partners.getOrNull(index - 1) == partner
                val linkedBelow = partner != null && partners.getOrNull(index + 1) == partner &&
                    messages.getOrNull(index + 1)?.key != unreadLine
                val rail = partner?.let { name -> faces.find(name)?.let { Color(0xFF000000 or botLook(it).color.toLong()) } }
                Column {
                    // The line marking where the new messages start sits on top of the first of them.
                    if (divided) UnreadDivider(Modifier.padding(bottom = 20.dp))
                    // Drawn just outside the message, so marking it doesn't move a line of it.
                    val foundColor = Theme[colors][accent]
                    val foundOutline = if (message.key == found) {
                        Modifier.drawWithContent {
                            drawContent()
                            val out = 6.dp.toPx()
                            drawRoundRect(
                                foundColor,
                                topLeft = Offset(-out, -out),
                                size = Size(size.width + 2 * out, size.height + 2 * out),
                                cornerRadius = CornerRadius(12.dp.toPx()),
                                style = Stroke(2.dp.toPx()),
                            )
                        }
                    } else {
                        Modifier
                    }
                    Box(
                        foundOutline.drawBehind {
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
                                    reading = message.key == reading,
                                    onReadAloud = if (canReadAloud) ({ text -> actions.readAloud(message.key, text) }) else null,
                                    onStopReading = actions::stopReading,
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
        }
        // Clear of the composer floating over the chat's end. Any press on it moves the list, even a click on its track.
        VerticalScrollbar(
            listState,
            Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(top = windowControlsHeight() + 4.dp, bottom = bottomInset + 4.dp, end = 2.dp)
                .pointerInput(readUpTo) {
                    awaitPointerEventScope {
                        while (true) {
                            if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) readerNavigated = true
                        }
                    }
                },
        )
        AnimatedVisibility(
            visible = awayFromBottom,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp + controlsInset),
        ) {
            val shape = RoundedCornerShape(Theme[radii][radiusMedium])
            // A pointer-sized button on the desktop, named on a rest over it.
            val size = if (isDesktop) 34.dp else 40.dp
            Tooltip(if (isDesktop && !newBelow) "Jump to latest" else "") { UnstyledButton(
                onClick = {
                    readerNavigated = true
                    scope.launch {
                        listState.animateToEnd()
                        pinned = true
                    }
                },
                modifier = Modifier
                    .heightIn(min = size)
                    .handCursor()
                    .widthIn(min = size)
                    .clip(shape)
                    .background(if (newBelow) Theme[colors][accent] else Theme[colors][surfaceElevated])
                    .border(1.dp, if (newBelow) Theme[colors][accent] else Theme[colors][strokeStrong], shape)
                    .animateContentSize(),
                indication = rememberHoverIndication(Theme[colors][textColor]),
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
            } }
        }
    }
}

/** How far the reader scrolls back up through the chat before a composer opened by a tap folds again. */
private val READ_BACK_TRAVEL = 48.dp

/** Over how much of the chat's end the composer goes from folded to open. */
private val FOLD_RANGE = 160.dp

/** How much of the conversation's height Page Up and Page Down scroll, keeping a few lines for context. */
private const val PAGE_SCROLL = 0.85f

/**
 * How far the composer is open. Scroll-linked: fully open with the end of the chat in view, folding over the
 * [FOLD_RANGE] above it and folded beyond; a tap on the folded line holds it open until the reader scrolls back up.
 */
@Stable
internal class ComposerFold {
    /** The conversation while it's on screen; none (an empty chat) leaves the composer open. */
    var list: LazyListState? by mutableStateOf(null)

    /** Explicit navigation takes precedence over a delayed initial unread jump. */
    var onNavigate: () -> Unit = {}

    /** The list is following the end; a reply growing there never folds the composer between two frames. */
    var following: Boolean by mutableStateOf(true)

    var heldOpen: Boolean by mutableStateOf(false)

    /** The composer's text box has the keyboard; read when a key decides whether it's the composer's or the chat's. */
    var typing: Boolean = false

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
internal fun OfflineBanner(place: String?, status: String, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            // The tint laid on the chat's page colour: the red wash it had on a plain page, but nothing under it shows through.
            .background(Theme[colors][dangerSoft].compositeOver(Theme[colors][background]), RoundedCornerShape(Theme[radii][radiusMedium]))
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
            CopyableOnDesktop { Text("$status Your draft is kept.", style = Theme[typography][caption], color = Theme[colors][textSecondary]) }
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
            .handCursor()
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
                .indication(interaction, rememberHoverIndication(Theme[colors][textColor]))
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
internal fun UserBubble(
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
    var contextMenuOpen by remember { mutableStateOf(false) }
    val selection = remember { SelectionProbe() }
    // A prompt in doubt always has one: its Edit is there.
    val hasMenu = message.text.isNotBlank() || onEdit != null || onBranch != null || message.check == SendCheck.Unknown
    val review = remember(message.text) { parseReview(message.text) }
    val commentHost = LocalCommentHost.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(
        Modifier.fillMaxWidth().then(if (isDesktop) Modifier.hoverable(hover) else Modifier),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.End,
    ) {
        // A long press opens the prompt's actions just under it; on a phone the text itself isn't selectable, Copy is in there.
        // The bubble hugs its text, up to most of the row, against the end edge.
        Box(Modifier.fillMaxWidth(USER_BUBBLE_WIDTH), contentAlignment = Alignment.TopEnd) {
            val menuItems = {
                listOfNotNull(
                    RowMenuItem("Copy", Lucide.Copy) { scope.launch { clipboard.setClipEntry(plainTextClipEntry(message.text)) } }
                        .takeIf { message.text.isNotBlank() },
                    // From here the comment is on all of the prompt; a desktop selection's menu comments on part of it.
                    RowMenuItem("Comment", Lucide.MessageSquare) {
                        commentHost?.onSelection(
                            SelectionAction.Comment,
                            CommentSource(message.key, "my message that starts “${openingWords(message.text)}”"),
                            SelectionAnchor.whole(message.text),
                        )
                    }.takeIf { commentHost != null && review == null && message.text.isNotBlank() && !message.pending },
                    onEdit?.let { RowMenuItem(editLabel, Lucide.Pencil, run = it) },
                    // A prompt in doubt has only Check and Resend under it; taking it back to edit is here.
                    RowMenuItem("Edit", Lucide.Pencil) { actions.editMessage(message.key) }
                        .takeIf { onEdit == null && message.check == SendCheck.Unknown },
                    onBranch?.let { RowMenuItem("Branch from here", Lucide.GitBranch, run = it) },
                )
            }
            // A right-click's menu opens at the pointer, as a desktop's context menus do.
            RowContextMenu(contextMenuOpen, onDismiss = { contextMenuOpen = false }, items = menuItems)
            DropdownMenu(
                expanded = menuOpen,
                onExpandedChange = { menuOpen = it },
                items = {
                    for (item in menuItems()) {
                        MenuAction(item.label, item.icon, onClick = { menuOpen = false; item.run() })
                    }
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
                                // A desktop's way to the same actions: a right-click, which leaves a drag to select
                                // the text as it does in a reply. A phone keeps its long-press.
                                // With part of it selected, the right-click is the selection's: Copy copies that part.
                                if (hasMenu && isDesktop) {
                                    Modifier.onSecondaryPress(unless = { selection.hasSelection }) { contextMenuOpen = true }
                                } else if (hasMenu) {
                                    Modifier.combinedClickable(
                                        onClick = {},
                                        onLongClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            menuOpen = true
                                        },
                                        onLongClickLabel = "Message actions",
                                        interactionSource = null,
                                        indication = rememberHoverIndication(onBubble),
                                    )
                                } else Modifier,
                            )
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (message.attachments.isNotEmpty()) SentAttachments(message.attachments)
                        when {
                            review != null -> SentReviewContent(review, onBubble)
                            // A mouse selects part of a prompt to copy, as it does a reply; a phone's long press is the menu's.
                            // Marked in the bubble's own text color: the accent selection would vanish on the accent fill.
                            // Its selection offers Comment, Explain and Ask aside too, as a reply's does.
                            message.text.isNotEmpty() && isDesktop -> CompositionLocalProvider(
                                LocalTextSelectionColors provides TextSelectionColors(handleColor = onBubble, backgroundColor = onBubble.copy(alpha = 0.32f)),
                            ) {
                                val source = remember(message.key, message.text) {
                                    CommentSource(message.key, "my message that starts “${openingWords(message.text)}”")
                                }
                                // What a waiting comment is about is marked, in the text color too.
                                CommentableSelection(source.takeIf { !message.pending }, selection) {
                                    Text(
                                        AnnotatedString(message.text).withHighlights(LocalTextHighlights.current, onBubble.copy(alpha = 0.24f)),
                                        style = Theme[typography][body],
                                        color = onBubble,
                                    )
                                }
                            }
                            message.text.isNotEmpty() -> Text(message.text, style = Theme[typography][body], color = onBubble)
                        }
                    }
                }
            }
        }
        if (!message.pending && isDesktop && message.check == null && hasMenu) {
            // A desktop shows a prompt's tools beside its time under the pointer, as a reply's: they only fade, and
            // stand out of the line's height, so nothing moves as the pointer passes. One with the keyboard in it stays.
            var keysInside by remember { mutableStateOf(false) }
            val alpha by animateFloatAsState(if (hovered || keysInside) 1f else 0f, label = "prompt actions")
            Row(Modifier.height(20.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.alpha(alpha).onFocusChanged { keysInside = it.hasFocus }, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (message.text.isNotBlank()) CopyButton(message.text, Modifier.requiredSize(30.dp))
                    onEdit?.let { ReplyAction(Lucide.Pencil, editLabel, it, Modifier.requiredSize(30.dp)) }
                    onBranch?.let { ReplyAction(Lucide.GitBranch, "Branch from here", it, Modifier.requiredSize(30.dp)) }
                }
                MessageTimeLabel(message.timestamp)
            }
        } else if (!message.pending) MessageTimeLabel(message.timestamp)
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
            "$tapVerb Check first to be sure.",
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
            .handCursor(enabled)
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
                .indication(interaction, rememberHoverIndication(Theme[colors][textColor]))
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
    /** This reply is being read aloud: Stop shows in place of Read aloud. */
    reading: Boolean = false,
    /** Reads the reply's text, as shown, aloud; null when it can't be now. */
    onReadAloud: ((String) -> Unit)? = null,
    onStopReading: () -> Unit = {},
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
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    // A desktop's right-click on a reply offers what its icons do, as a prompt's does; with part of it selected,
    // the right-click is the selection's.
    val selection = remember { SelectionProbe() }
    var contextMenuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val canMenu = isDesktop && !message.streaming && text.isNotBlank()
    if (canMenu) {
        RowContextMenu(contextMenuOpen, onDismiss = { contextMenuOpen = false }) {
            listOfNotNull(
                RowMenuItem("Copy", Lucide.Copy) { scope.launch { clipboard.setClipEntry(plainTextClipEntry(text)) } },
                onRegenerate?.let { RowMenuItem("Regenerate", Lucide.RefreshCw, run = it) },
                onBranch?.let { RowMenuItem("Branch from here", Lucide.GitBranch, run = it) },
                if (reading) RowMenuItem("Stop reading", Lucide.Square, run = onStopReading)
                else onReadAloud?.let { read -> RowMenuItem("Read aloud", Lucide.Volume2) { read(text) } },
            )
        }
    }
    Column(
        Modifier.fillMaxWidth()
            .then(if (isDesktop) Modifier.hoverable(hover) else Modifier)
            .then(if (canMenu) Modifier.onSecondaryPress(unless = { selection.hasSelection }) { contextMenuOpen = true } else Modifier),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ReplyHeader(message.timestamp.takeUnless { message.streaming })
        // What's happening now is said once, above the composer; the reply keeps only what it's made of.
        if (showReasoning) Reasoning(message.reasoning, message.streaming)
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
            CommentableSelection(source.takeUnless { message.streaming }, selection) {
                MarkdownText(remember(text) { linkFileUris(text) }, streaming = message.streaming)
            }
        }
        // Screens the agent drew, under what it said and whatever the tool-activity setting: they're for the user to answer.
        val screens = LocalScreenViews.current
        message.tools.forEach { call ->
            screens.byCall[call.id]?.let { key(call.id) { AgentScreenCard(it, newest = it.latest.id in screens.newest) } }
        }
        if (settings.showLinkPreviews && !message.streaming) LinkPreviewCards(text)
        if (media.isNotEmpty()) ReplyMediaList(media)
        when (message.outcome) {
            TurnOutcome.Error -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                UnstyledIcon(Lucide.CircleAlert, contentDescription = null, tint = Theme[colors][danger], modifier = Modifier.size(16.dp))
                CopyableOnDesktop { Text(message.error ?: "The turn failed.", style = Theme[typography][bodySmall], color = Theme[colors][danger]) }
            }
            TurnOutcome.Interrupted -> Text("Stopped", style = Theme[typography][caption], color = Theme[colors][textTertiary])
            else -> Unit
        }
        message.warning?.let { warningText ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                UnstyledIcon(Lucide.TriangleAlert, contentDescription = null, tint = Theme[colors][warning], modifier = Modifier.padding(top = 2.dp).size(14.dp))
                CopyableOnDesktop { Text(warningText, style = Theme[typography][bodySmall], color = Theme[colors][warning]) }
            }
        }
        val usage = message.usage?.takeIf { settings.showUsage && !message.streaming }
        val actionable = !message.streaming && text.isNotBlank()
        // A reply of only code or files has nothing to say.
        val speakable = if (actionable) remember(text) { readAloudText(text).isNotEmpty() } else false
        if (isDesktop && (actionable || usage != null)) {
            // A desktop reads a reply's tools from its own start, as desktop chat apps line them up, and shows
            // them on the newest reply and under the pointer: older replies read without a row of icons each. They
            // only fade, so nothing moves as the pointer passes; one with the keyboard in it stays shown.
            var keysInside by remember { mutableStateOf(false) }
            val shown = last || hovered || keysInside
            val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "reply actions")
            Row(
                Modifier.trimEndTop(end = 0.dp, top = 4.dp).offset(x = (-10).dp).alpha(alpha).onFocusChanged { keysInside = it.hasFocus },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (actionable) {
                    // Pointer-sized, as the icons beside it are.
                    CopyButton(text, Modifier.size(32.dp))
                    onRegenerate?.let { ReplyAction(Lucide.RefreshCw, "Regenerate", it, Modifier) }
                    onBranch?.let { ReplyAction(Lucide.GitBranch, "Branch from here", it, Modifier) }
                }
                usage?.let {
                    Text(
                        replyUsage(it.input, it.output),
                        style = Theme[typography][caption].copy(fontFamily = Theme[typography][code].fontFamily),
                        color = Theme[colors][textMuted],
                        modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = "${compactCount(it.input)} tokens in, ${compactCount(it.output)} out" },
                    )
                }
            }
        } else if (actionable || usage != null) {
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
                    // Copy's padding trimmed off the end lines its icon up with the reply's edge; all of them lose 4dp on top to sit close under it.
                    val action = Modifier.trimEndTop(end = 0.dp, top = 4.dp)
                    onBranch?.let { ReplyAction(Lucide.GitBranch, "Branch from here", it, action) }
                    onRegenerate?.let { ReplyAction(Lucide.RefreshCw, "Regenerate", it, action) }
                    when {
                        reading -> ReplyAction(Lucide.Square, "Stop reading", onStopReading, action)
                        speakable && onReadAloud != null -> ReplyAction(Lucide.Volume2, "Read aloud", { onReadAloud(text) }, action)
                    }
                    CopyButton(text, Modifier.trimEndTop(end = 16.dp, top = 4.dp).size(MinTouchTarget))
                }
            }
        }
    }
}

/** Who's speaking above a reply: the chat's bot (or the app's mark and "Hermes"), and when it finished. */
@Composable
internal fun ReplyHeader(epochSeconds: Double?) {
    val speaker = LocalReplySpeaker.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (speaker != null) DecodedBotAvatar(speaker.face, LocalReplySpeakerPicture.current, size = 20.dp) else AppMark(20.dp, glow = false)
        Text(speaker?.name ?: "Hermes", style = Theme[typography][label], color = Theme[colors][textSecondary])
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
    if (label.isEmpty()) return
    if (isDesktop) {
        // The short label ("Mon 14:05") in full under the pointer, as desktop chat apps show it.
        val full = remember(epochSeconds, use24Hour) { fullMessageTime(epochSeconds, use24Hour) }
        Tooltip(full, modifier) { Text(label, style = Theme[typography][caption], color = Theme[colors][textTertiary]) }
    } else {
        Text(label, style = Theme[typography][caption], color = Theme[colors][textTertiary], modifier = modifier)
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
        CopyableOnDesktop { Text(message.text, style = Theme[typography][caption], color = Theme[colors][textTertiary]) }
    }
}

/** "New messages" between hairlines, above the first message that came since the chat was last read. */
@Composable
private fun UnreadDivider(modifier: Modifier = Modifier) {
    val line = Theme[colors][accentText].copy(alpha = 0.45f)
    Row(
        modifier.fillMaxWidth().semantics { heading() },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(line))
        Text("New messages", style = Theme[typography][caption], color = Theme[colors][accentText])
        Box(Modifier.weight(1f).height(1.dp).background(line))
    }
}

/**
 * With reasoning hidden, a finished reply of nothing but reasoning (a step that only thought, or only commented
 * on its way) would be a bare header: it isn't listed.
 */
internal fun List<ChatMessage>.withoutBlankReplies(showReasoning: Boolean): List<ChatMessage> =
    if (showReasoning) this else filterNot {
        it is ChatMessage.Assistant && !it.streaming && it.text.isBlank() && it.tools.isEmpty() && it.error == null &&
            it.warning == null && it.outcome != TurnOutcome.Error && it.outcome != TurnOutcome.Interrupted
    }

/**
 * The first message newer than [readUpTo] (epoch seconds) that the person would call new: a reply, a prompt
 * from elsewhere, or another bot's row. Messages without a time (a reply still streaming) count as new.
 * Null when every message is older, or when nothing in [messages] is new.
 */
internal fun firstUnreadKey(messages: List<ChatMessage>, readUpTo: Double): String? =
    messages.firstOrNull { it.isNewerThan(readUpTo) }?.key

/**
 * The message the "New messages" line sits on: the one placed for this visit ([unreadFrom]), or the first new message
 * now once that one is gone or no longer new. A running turn's saved step folds into the live reply that repeats it,
 * and splits off the answer before it when its prompt doesn't show, which leaves that answer's key and time.
 */
internal fun unreadLineKey(messages: List<ChatMessage>, unreadFrom: String?, readUpTo: Double?): String? {
    if (unreadFrom == null || readUpTo == null) return unreadFrom
    if (messages.firstOrNull { it.key == unreadFrom }?.isNewerThan(readUpTo) == true) return unreadFrom
    return firstUnreadKey(messages, readUpTo)
}

/** What to do next about the "New messages" line, given what is loaded so far. */
internal sealed interface UnreadLineStep {
    /** Every loaded message is new and there's more history: fetch an older page first. */
    data object LoadOlder : UnreadLineStep

    /** No line: nothing is new, or the first new message is beyond the pages worth fetching. */
    data object None : UnreadLineStep

    /** The line goes above the message [key]. */
    data class Above(val key: String) : UnreadLineStep
}

/**
 * Where the line goes once [pagesLoaded] older pages have been fetched. Past [UNREAD_PAGE_LIMIT] pages with
 * everything still new, the first new message is further back still, so a line above the oldest loaded one
 * would be in the wrong place (loading more would show new messages above it). Then there's no line at all
 * and the chat opens at the bottom, as it did before the line existed.
 */
internal fun unreadLineStep(messages: List<ChatMessage>, readUpTo: Double, olderMessages: Boolean, pagesLoaded: Int): UnreadLineStep {
    if (needsOlderForUnread(messages, readUpTo, olderMessages)) {
        return if (pagesLoaded < UNREAD_PAGE_LIMIT) UnreadLineStep.LoadOlder else UnreadLineStep.None
    }
    return firstUnreadKey(messages, readUpTo)?.let(UnreadLineStep::Above) ?: UnreadLineStep.None
}

/**
 * Whether the line can't be placed yet: every loaded message is new and the conversation goes back further,
 * so the first new message may be on an older page.
 */
internal fun needsOlderForUnread(messages: List<ChatMessage>, readUpTo: Double, olderMessages: Boolean): Boolean =
    olderMessages && messages.firstNotNullOfOrNull { it.sentAt }?.let { it > readUpTo } != false

/**
 * When a prompt, a reply or another bot's message happened (epoch seconds); null for other rows and for a
 * reply still streaming.
 */
internal val ChatMessage.sentAt: Double?
    get() = when (this) {
        is ChatMessage.User -> timestamp
        is ChatMessage.Assistant -> timestamp
        is ChatMessage.Event -> timestamp.takeIf { event is TranscriptEvent.FromBot }
        else -> null
    }

private fun ChatMessage.isNewerThan(readUpTo: Double): Boolean = when (this) {
    is ChatMessage.User -> (timestamp ?: return false) > readUpTo + UNREAD_SLACK_SECONDS
    // A reply with no time yet is still being written, so it came after the last look.
    is ChatMessage.Assistant -> (timestamp ?: Double.MAX_VALUE) > readUpTo + UNREAD_SLACK_SECONDS
    // Another bot writing in is news even when this bot stays silent. The machinery's own rows (deliveries,
    // routines, failures) aren't: the line goes above something the person would want to read.
    is ChatMessage.Event -> event is TranscriptEvent.FromBot && (timestamp ?: return false) > readUpTo + UNREAD_SLACK_SECONDS
    else -> false
}

/** Same slack as the sidebar's unread dot: the gateway and the phone round times differently. */
private const val UNREAD_SLACK_SECONDS = 1.0

/** How many older pages are fetched at most to find where the new messages start. */
internal const val UNREAD_PAGE_LIMIT = 5

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
private fun SlashSuggestions(
    suggestions: List<SlashSuggestion>,
    hazeState: HazeState,
    marked: Int,
    onPick: (SlashSuggestion) -> Unit,
    onMark: ((Int) -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    val list = rememberLazyListState()
    // The marked row kept in sight as Up and Down move past the list's edge; a group's heading counts as an item.
    LaunchedEffect(marked, suggestions) {
        if (marked !in suggestions.indices) return@LaunchedEffect
        if (marked == 0) return@LaunchedEffect list.scrollToItem(0)
        val headings = (0..marked).count { i -> suggestions[i].group.let { it != null && it != suggestions.getOrNull(i - 1)?.group } }
        val item = marked + headings
        val view = list.layoutInfo
        val row = view.visibleItemsInfo.firstOrNull { it.index == item }
        when {
            row == null || row.offset < view.viewportStartOffset -> list.scrollToItem(item)
            row.offset + row.size > view.viewportEndOffset -> list.scrollBy((row.offset + row.size - view.viewportEndOffset).toFloat())
        }
    }
    val page = Theme[colors][background]
    val frosted = remember(page) {
        HazeBlurStyle {
            blurEnabled(true)
            blurRadius(20.dp)
            backgroundColor(page)
        }
    }
    ScrollbarBox(list, Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp)) { LazyColumn(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .frostedPanel(shape, Theme[colors][surfaceElevated].copy(alpha = 0.85f), Theme[colors][strokeStrong], Modifier.hazeBlur(input = HazeInput.Sources(hazeState), style = frosted)),
        state = list,
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
                    Modifier
                        .fillMaxWidth()
                        .then(if (index == marked) Modifier.background(Theme[colors][surface2]) else Modifier)
                        .marksOnHover(index, onMark)
                        .handCursor()
                        .clickable(interactionSource = null, indication = if (onMark != null) null else LocalIndication.current) { onPick(row) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
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
    } }
}

/**
 * The message the user would likely send next, over the composer: a tap puts [text] in the composer to look
 * over and send, the cross closes it. Frosted over the chat like the `/` list.
 */
@Composable
internal fun SuggestedReplyChip(text: String, hazeState: HazeState?, onUse: () -> Unit, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    val page = Theme[colors][background]
    val frosted = remember(page) {
        HazeBlurStyle {
            blurEnabled(true)
            blurRadius(20.dp)
            backgroundColor(page)
        }
    }
    Row(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 6.dp)
            .fillMaxWidth()
            .frostedPanel(
                shape,
                Theme[colors][surfaceElevated].copy(alpha = 0.85f),
                Theme[colors][strokeStrong],
                if (hazeState != null) Modifier.hazeBlur(input = HazeInput.Sources(hazeState), style = frosted) else Modifier,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = MinTouchTarget)
                .handCursor()
                .clickable(role = Role.Button, onClickLabel = "Put in the composer", onClick = onUse)
                .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(Lucide.Lightbulb, contentDescription = "Suggested reply", tint = Theme[colors][accentText], modifier = Modifier.size(16.dp))
            Text(
                text,
                style = Theme[typography][label],
                color = Theme[colors][textColor],
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(Lucide.X, contentDescription = "Dismiss the suggestion", onClick = onDismiss)
    }
}

/**
 * One row of the `@` list: who, the handle a pick inserts, and the bot whose face it shows; with none, [icon]
 * (everyone's, by default).
 */
internal data class MentionChoice(val label: String, val handle: String, val bot: Bot?, val icon: ImageVector? = null)

/** An `@file:` or `@folder:` reference to put in the composer: [insert] in place of the text from [start] to [end]. */
internal data class RefChoice(val label: String, val shown: String, val icon: ImageVector, val start: Int, val end: Int, val insert: String)

/**
 * The `@` list's file references for what's being typed, on the desktop: the reference kinds while a bare `@`
 * word could still be one, then [entries] of the folder an `@file:` or `@folder:` path is in.
 */
internal fun refChoices(mention: MentionQuery?, ref: PathRefQuery?, entries: List<PathEntry>): List<RefChoice> {
    if (ref != null) {
        val name = ref.path.substringAfterLast('/')
        return entries
            .filter { (it.dir || ref.kind == PathRefKind.File) && it.name.startsWith(name, ignoreCase = true) }
            .take(MAX_REF_CHOICES)
            .map { entry ->
                val insert = ref.completion(entry.name, entry.dir)
                RefChoice(entry.name, insert.trim().removePrefix("@"), if (entry.dir) Lucide.Folder else Lucide.File, ref.start, ref.end, insert)
            }
    }
    if (mention == null) return emptyList()
    return PathRefKind.entries
        .filter { it.prefix.removePrefix("@").startsWith(mention.query, ignoreCase = true) }
        .map { kind ->
            val label = if (kind == PathRefKind.File) "A file" else "A folder"
            RefChoice(label, kind.prefix.removePrefix("@"), if (kind == PathRefKind.File) Lucide.File else Lucide.Folder, mention.start, mention.end, kind.prefix)
        }
}

private const val MAX_REF_CHOICES = 8

/** How wide the desktop's file rail is. */
private val RAIL_WIDTH = 420.dp

/** The narrowest chat that docks a file in the rail: room for the rail and a conversation beside it. */
private val RAIL_MIN_PANE = 880.dp

/** How long typing in a path rests before the gateway is asked for the folder's entries. */
private const val PATH_COMPLETION_DELAY_MS = 150L

/**
 * The `@` list over a composer: who to mention, with faces, names and handles. Frosted over [hazeState]'s
 * content when there is one, else on a plain elevated surface.
 */
@Composable
internal fun MentionSuggestions(
    choices: List<MentionChoice>,
    hazeState: HazeState?,
    marked: Int = -1,
    onPick: (MentionChoice) -> Unit,
    /** The desktop's pointer marking the row it's on, so it and the keys share one highlight. */
    onMark: ((Int) -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    val page = Theme[colors][background]
    val faces = LocalBotFaces.current
    val markedRow = remember { BringIntoViewRequester() }
    LaunchedEffect(marked, choices) { if (marked in choices.indices) runCatching { markedRow.bringIntoView() } }
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
            .frostedPanel(
                shape,
                Theme[colors][surfaceElevated].copy(alpha = if (hazeState != null) 0.85f else 1f),
                Theme[colors][strokeStrong],
                if (hazeState != null) Modifier.hazeBlur(input = HazeInput.Sources(hazeState), style = frosted) else Modifier,
            )
            // As tall as the `/` list at most, so eight file references still leave the chat in sight.
            .heightIn(max = 280.dp)
            .verticalScrollWithBar()
            .padding(vertical = 6.dp),
    ) {
        choices.forEachIndexed { index, choice ->
            Row(
                Modifier
                    .fillMaxWidth()
                    // The one Enter takes, marked on the desktop, and kept in sight as Up and Down move it.
                    .then(if (index == marked) Modifier.background(Theme[colors][surface2]).bringIntoViewRequester(markedRow) else Modifier)
                    .marksOnHover(index, onMark)
                    .handCursor()
                    .clickable(
                        interactionSource = null,
                        indication = if (onMark != null) null else LocalIndication.current,
                        onClickLabel = "Mention ${choice.label}",
                    ) { onPick(choice) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (choice.bot != null) {
                    BotAvatar(choice.bot, faces.picture(choice.bot), size = 24.dp)
                } else {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        UnstyledIcon(choice.icon ?: Lucide.Users, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(18.dp))
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
internal fun Disclosure(
    icon: @Composable () -> Unit,
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(Theme[radii][radiusMedium])).handCursor().clickable(onClick = onToggle).padding(vertical = 4.dp, horizontal = 2.dp),
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
private fun Reasoning(text: String, streaming: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Disclosure(
        icon = { UnstyledIcon(Lucide.Brain, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp)) },
        label = "Reasoning",
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        val body = @Composable {
            MarkdownText(text.trim(), streaming = streaming, textStyle = Theme[typography][bodySmall].copy(color = Theme[colors][textSecondary]))
        }
        // On the desktop it's text to select and copy like the reply's, with the same right-click menu.
        if (isDesktop) PlatformCommentableSelection(onAction = null, content = body) else body()
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
                .handCursor()
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
                    .indication(interaction, rememberHoverIndication(Theme[colors][textSecondary]))
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
            .handCursor()
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

private fun String.toolVerb(): String = toolInProgress(this)

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
            Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                CopyableOnDesktop { Text(message, style = Theme[typography][bodySmall], color = Theme[colors][textColor]) }
            }
            if (actionLabel != null) {
                Button(actionLabel, onClick = onAction, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            } else {
                IconButton(Lucide.X, contentDescription = "Dismiss", onClick = onAction)
            }
        }
    }
}

/**
 * The composer's editable field: UnstyledTextField and its TextInput, except that the text gets the field's
 * full width. TextInput's Box measured the text at its own width and put it at the start, so a Hebrew line in
 * an English UI sat on the left, and a Hebrew paragraph above an English one lined up with it. The body
 * style already resolves direction per paragraph; at full width each paragraph also goes to its own side.
 * Split out of [Composer] so a host test can measure it.
 */
@Composable
internal fun ComposerTextInput(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    maxHeightInLines: Int = 8,
    onTextLayout: (Density.(getResult: () -> TextLayoutResult?) -> Unit)? = null,
    placeholder: @Composable () -> Unit,
) {
    BasicTextField(
        state = state,
        textStyle = Theme[typography][body].copy(color = Theme[colors][textColor]),
        cursorBrush = SolidColor(Theme[colors][accent]),
        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = maxHeightInLines),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        onTextLayout = onTextLayout,
        modifier = modifier.semantics(mergeDescendants = true) {},
        decorator = { innerTextField ->
            Box(Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Text, overrideDescendants = true).clipToBounds()) {
                if (state.text.isEmpty()) {
                    Box(Modifier.matchParentSize(), contentAlignment = Alignment.TopStart) { placeholder() }
                }
                // The text measures at the width it's given as a minimum; without this it shrinks to its widest line.
                Box(Modifier.fillMaxWidth(), propagateMinConstraints = true) { innerTextField() }
            }
        },
    )
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
    /** The desktop's keys in the field before its own: a paste that attaches, a suggestion picked. */
    onKey: (KeyEvent) -> Boolean = { false },
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
    // Up and Down through this chat's earlier prompts, from the desktop's keyboard.
    val recall = remember(state.storedSessionId) { PromptRecall() }
    // Back hides the keyboard but leaves the field focused, and a focused field brings the keyboard back on
    // the next relayout (opening a tool card, the menu). Put away means done typing, so let go of the focus.
    val keyboardUp = imeVisible
    val focusManager = LocalFocusManager.current
    LaunchedEffect(keyboardUp) { if (!keyboardUp && focused) focusManager.clearFocus() }
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
            // A desktop keeps it open: the window has the room, and its chat apps don't fold the box being typed in.
            nearEnd to if (isDesktop || fold.heldOpen || focused || busyNow) 1f else nearEnd
        }.collectLatest { (nearEnd, target) ->
            // At the end it's open anyway; scrolling up from there folds it again.
            if (nearEnd >= 1f) fold.heldOpen = false
            if (abs(target - open.value) > 0.2f) open.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow)) else open.snapTo(target)
        }
    }
    // The open height while folded, so the dock can report how much it gave back.
    val openHeight = remember { IntArray(1) }
    DisposableEffect(Unit) { onDispose { onShrink(0) } }
    val edge = if (focused) Theme[colors][textTertiary] else Theme[colors][stroke]
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
                    ComposerTextInput(
                        state = actions.composer,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = 12.dp).focusRequester(focus)
                            .onFocusChanged { fold.typing = it.hasFocus }
                            .then(
                                if (!enterSends) {
                                    Modifier
                                } else {
                                    Modifier.onPreviewKeyEvent { event ->
                                        onKey(event) ||
                                            onComposerKey(composerKey(event) ?: return@onPreviewKeyEvent false, actions, state, recall, connected, editing, attachments, comments)
                                    }
                                },
                            ),
                        maxHeightInLines = if (isDesktop) 14 else 8,
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
                        if (hasVoice && !state.running) Snug { VoiceChatButton(onClick = onVoiceChat, enabled = connected && !dictation.active) }
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
            // Nearly solid: the conversation beneath only just shows through, blurred.
            .frostedPanel(shape, Theme[colors][surface].copy(alpha = 0.92f), edge, Modifier.hazeBlur(input = HazeInput.Sources(hazeState), style = frosted))
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
 * Runs a [ComposerKey]; false leaves the key to the field (Up and Down with nothing to bring back move the cursor).
 * Enter never starts a line on the desktop, even when there's nothing it may send.
 */
private fun onComposerKey(
    key: ComposerKey,
    actions: ChatActions,
    state: ChatState,
    recall: PromptRecall,
    connected: Boolean,
    editing: Boolean,
    attachments: List<OutgoingAttachment>,
    comments: List<PendingComment>,
): Boolean {
    val text = actions.composer.text.toString()
    val prompts = { state.messages.filterIsInstance<ChatMessage.User>().map { it.text }.filter { it.isNotBlank() } }
    when (key) {
        ComposerKey.Send, ComposerKey.Queue -> {
            val sendable = text.isNotBlank() || attachments.isNotEmpty() || comments.isNotEmpty()
            // An edit goes once the reply finishes, as the disabled Send says.
            if (!sendable || !connected || (state.running && editing)) return true
            val command = attachments.isEmpty() && SlashCommand.parse(text.trim()) != null
            actions.send(if (key == ComposerKey.Queue && state.running && !command) RunningSend.Queue else null)
            recall.reset()
        }
        ComposerKey.Older -> actions.composer.setTextAndPlaceCursorAtEnd(recall.older(text, prompts()) ?: return false)
        ComposerKey.Newer -> actions.composer.setTextAndPlaceCursorAtEnd(recall.newer(text, prompts()) ?: return false)
    }
    return true
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
            modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).handCursor().semantics { contentDescription = "Write a message" },
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
        if (hasVoice) Snug { VoiceChatButton(onClick = onVoiceChat, enabled = connected && !dictation.active && !state.running) }
    }
}

/** The mic shows with the Dictation setting on, and while a dictation still runs, so it can be finished. */
@Composable
private fun showMic(dictation: DictationState): Boolean = hasVoice && LocalAppSettings.current.dictation || dictation.active

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
                        .handCursor(usable)
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
                        // A mouse finds the other ways with a right-click, as it can't feel a long press.
                        .then(if (isDesktop && others.isNotEmpty()) Modifier.onSecondaryPress { menuOpen = true } else Modifier)
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
                            .indication(interaction, rememberHoverIndication(tint))
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
    DiscTarget(onClick = onClick, enabled = enabled, tint = tint, label = contentDescription) {
        UnstyledIcon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** A grey disc in the full touch target; a press shows on the disc itself, as on Send. [disc] draws over its fill. */
@Composable
private fun DiscTarget(
    onClick: () -> Unit,
    enabled: Boolean,
    tint: Color,
    /** Said on hover on the desktop, as the bar's buttons do. */
    label: String,
    disc: Modifier = Modifier,
    content: @Composable () -> Unit,
) = Tooltip(label) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(MinTouchTarget)
            .handCursor(enabled)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(ComposerDisc)
                .background(Theme[colors][surface3], CircleShape)
                .then(disc)
                .clip(CircleShape)
                .indication(interaction, rememberHoverIndication(tint)),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/**
 * The size the composer's round buttons are drawn at: +, the mic, voice chat and Send. Smaller on the desktop, in
 * its smaller touch target, so the discs keep a gap between them.
 */
private val ComposerDisc: Dp get() = if (isDesktop) 30.dp else 36.dp

/**
 * Sets a round button [SNUG_TRIM] narrower on each side, so neighbouring discs sit 8dp apart instead of 12dp;
 * each still takes taps over its full touch target, which only overlap at their edges.
 */
@Composable
private fun Snug(button: @Composable () -> Unit) {
    Layout(button) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints)
        // The desktop's targets are already tight around their discs.
        val trim = if (isDesktop) 0 else SNUG_TRIM.roundToPx()
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
    // With its key on the desktop, where Enter sends and Esc stops.
    Tooltip(
        when (icon) {
            SendIcon.Send -> "Send (Enter)"
            SendIcon.Stop -> "Stop the task (Esc)"
        },
    ) {
    Box(
        Modifier
            .size(MinTouchTarget)
            .handCursor(enabled)
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
            .indication(interaction, rememberHoverIndication(tint)),
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
        label = if (recording) "Finish dictating" else "Dictate",
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

/** Where the composer's model pill is in the window, for the desktop's model picker to open over. */
private val LocalModelPillBounds = staticCompositionLocalOf<MutableState<Rect?>?> { null }

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
    val bounds = LocalModelPillBounds.current
    // Gone (no model, a question in the composer's place), the picker opens centred rather than over where it was.
    if (bounds != null) DisposableEffect(bounds) { onDispose { bounds.value = null } }
    // Drawn 36dp tall; the full touch height around it takes the tap.
    Tooltip("Change model (${keysLabel("Ctrl+Shift+M")})", modifier) { Box(
        Modifier
            .then(if (bounds != null) Modifier.onGloballyPositioned { bounds.value = it.boundsInWindow() } else Modifier)
            .heightIn(min = MinTouchTarget)
            .handCursor()
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = listOfNotNull(name, effort?.label, "fast mode".takeIf { fast }).joinToString(", ") + ". Change model"
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier
                .clearAndSetSemantics {}
                // As tall as the discs beside it.
                .heightIn(min = ComposerDisc)
                .then(if (compact) Modifier.widthIn(min = ComposerDisc) else Modifier)
                .clip(CircleShape)
                .background(Theme[colors][surface3], CircleShape)
                .indication(interaction, rememberHoverIndication(Theme[colors][textSecondary]))
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
    } }
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
