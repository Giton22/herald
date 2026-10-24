package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.BellRing
import com.composables.icons.lucide.PanelLeft
import com.composables.icons.lucide.PanelTop
import com.composables.icons.lucide.Vibrate
import com.composables.icons.lucide.ArrowLeftRight
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.Cat
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.MessageSquarePlus
import com.composables.icons.lucide.MessagesSquare
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.WrapText
import com.composables.icons.lucide.Wrench
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import com.composeunstyled.theme.rememberColoredIndication
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.phone.enabledPhoneTools
import dev.hermeskotlin.core.plugins.NextPromptApi
import dev.hermeskotlin.core.push.PushStatus
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.RunningSend
import dev.hermeskotlin.core.settings.TextSize
import dev.hermeskotlin.core.settings.ThemeMode
import dev.hermeskotlin.core.settings.VoicePause
import dev.hermeskotlin.core.settings.DictationEngine
import dev.hermeskotlin.core.settings.WallpaperStrength
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.SegmentedControl
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.components.Switch
import dev.hermeskotlin.designsystem.components.halo
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.eyebrow
import dev.hermeskotlin.designsystem.inverse
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.onInverse
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.LocalAppVersion
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.chat.AppMark
import dev.hermeskotlin.ui.chat.label
import dev.hermeskotlin.ui.chat.summary
import dev.hermeskotlin.core.gateway.CheckStage
import dev.hermeskotlin.core.gateway.StageResult
import dev.hermeskotlin.ui.connect.ConnectionChecklist
import dev.hermeskotlin.ui.sessions.SubpageHeader
import dev.hermeskotlin.ui.update.UpdateBanner
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

/** App preferences, the signed-in account and version info, as one page over the home screen. */
@Composable
fun SettingsScreen(
    gateway: SavedGateway,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onOpenGateways: () -> Unit,
    /** The profile the chats use, where Suggested replies looks for its plugin; null: the launch profile. */
    profile: String? = null,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val info by viewModel.gateway.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val push by viewModel.pushStatus.collectAsStateWithLifecycle()
    val pushTest by viewModel.pushTest.collectAsStateWithLifecycle()
    val wallpaper by viewModel.wallpaper.collectAsStateWithLifecycle()
    var wallpaperError by remember { mutableStateOf<String?>(null) }
    val chooseWallpaper = rememberWallpaperPicker(
        onPicked = {
            wallpaperError = null
            viewModel.setWallpaper(it)
        },
        onError = { wallpaperError = it },
    )
    var checkOpen by remember { mutableStateOf(false) }
    var phoneToolsOpen by remember { mutableStateOf(false) }
    var appIconOpen by remember { mutableStateOf(false) }
    var startInstructionOpen by remember { mutableStateOf(false) }
    // Read again when the App icon page closes, so its row names the icon just applied.
    val appIcons = key(appIconOpen) { rememberAppIcons() }
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    LaunchedEffect(gateway, profile) { viewModel.bind(gateway, profile) }
    PlatformBackHandler(enabled = !checkOpen && !phoneToolsOpen && !appIconOpen && !startInstructionOpen, onBack = onBack)
    SettingsView(
        settings, info, gateway.url, viewModel::update, onBack, onSignOut, onOpenGateways,
        gatewayName = gateway.label,
        onCheckConnection = {
            checkOpen = true
            viewModel.runConnectionCheck()
        },
        push = push,
        pushTest = pushTest,
        onPushAnywhere = viewModel::setPushAnywhere,
        onPushTest = viewModel::sendPushTest,
        onPushUpdate = viewModel::updatePushPlugin,
        wallpaper = rememberWallpaperBitmap(wallpaper),
        hasWallpaper = wallpaper != null,
        wallpaperError = wallpaperError,
        onChooseWallpaper = chooseWallpaper,
        onRemoveWallpaper = {
            wallpaperError = null
            viewModel.removeWallpaper()
        },
        onOpenPhoneTools = { phoneToolsOpen = true },
        appIcon = appIcons.current,
        onOpenAppIcon = { appIconOpen = true },
        onOpenStartInstruction = { startInstructionOpen = true },
        suggestions = suggestions,
        onInstallSuggestions = viewModel::installSuggestions,
        onCheckSuggestions = viewModel::checkSuggestions,
    )
    if (phoneToolsOpen) PhoneToolsScreen(onBack = { phoneToolsOpen = false })
    if (startInstructionOpen) {
        StartInstructionScreen(
            text = settings.startInstruction,
            onChange = { text -> viewModel.update { it.copy(startInstruction = text) } },
            onBack = { startInstructionOpen = false },
        )
    }
    if (appIconOpen) AppIconScreen(onBack = { appIconOpen = false })
    ConnectionCheckSheet(
        visible = checkOpen,
        results = check.results,
        running = check.running,
        onDismiss = { checkOpen = false },
        onCheckAgain = viewModel::runConnectionCheck,
    )
}

/** "Check connection": each stage on a line down the sheet, then Check again. */
@Composable
internal fun ConnectionCheckSheet(
    visible: Boolean,
    results: Map<CheckStage, StageResult>,
    running: Boolean,
    onDismiss: () -> Unit,
    onCheckAgain: () -> Unit,
) {
    BottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Check connection", style = Theme[typography][title], color = Theme[colors][text], modifier = Modifier.semantics { heading() })
                Text("Each stage is tested on its own.", style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
            }
            ConnectionChecklist(results, running = running)
            InversePillButton(if (running) "Checking…" else "Check again", Lucide.RefreshCw, onCheckAgain, loading = running)
        }
    }
}

/** A full-width pill in the inverse colour: the one action that ends a sheet. */
@Composable
private fun InversePillButton(text: String, icon: ImageVector, onClick: () -> Unit, loading: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val on = Theme[colors][onInverse]
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(CircleShape)
            .background(Theme[colors][inverse], CircleShape)
            .clickable(interaction, rememberColoredIndication(on), enabled = !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) Spinner(Modifier.size(15.dp), color = on) else UnstyledIcon(icon, contentDescription = null, tint = on, modifier = Modifier.size(15.dp))
        Text(text, style = Theme[typography][body].copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold), color = on)
    }
}

/** The settings page itself, stateless so previews can draw it. */
@Composable
internal fun SettingsView(
    settings: AppSettings,
    info: GatewayInfo,
    gatewayUrl: String,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onOpenGateways: () -> Unit,
    /** What the gateway is called: its given name, else its address. */
    gatewayName: String = gatewayUrl.substringAfter("://").trimEnd('/'),
    onCheckConnection: () -> Unit = {},
    push: PushStatus = PushStatus(),
    pushTest: Boolean? = null,
    onPushAnywhere: (Boolean) -> Unit = {},
    onPushTest: () -> Unit = {},
    onPushUpdate: () -> Unit = {},
    /** The chat background, once decoded. */
    wallpaper: ImageBitmap? = null,
    hasWallpaper: Boolean = wallpaper != null,
    wallpaperError: String? = null,
    onChooseWallpaper: () -> Unit = {},
    onRemoveWallpaper: () -> Unit = {},
    onOpenPhoneTools: () -> Unit = {},
    appIcon: AppIcon = AppIcon.Blue,
    onOpenAppIcon: () -> Unit = {},
    onOpenStartInstruction: () -> Unit = {},
    /** Where the Next Prompt plugin behind Suggested replies stands on the gateway. */
    suggestions: SuggestionsPlugin = SuggestionsPlugin.Checking,
    onInstallSuggestions: () -> Unit = {},
    onCheckSuggestions: () -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .halo()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            SubpageHeader("Settings", onBack = onBack)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                AccountCard(gatewayName, gatewayUrl, info.userLabel, onCheckConnection)

                Section("Appearance") {
                    Field("Theme") {
                        SegmentedControl(
                            options = ThemeMode.entries,
                            selected = settings.theme,
                            onSelect = { mode -> onUpdate { it.copy(theme = mode) } },
                            optionLabel = { it.name },
                            optionIcon = {
                                when (it) {
                                    ThemeMode.System -> Lucide.Smartphone
                                    ThemeMode.Light -> Lucide.Sun
                                    ThemeMode.Dark -> Lucide.Moon
                                }
                            },
                        )
                    }
                    Divider()
                    Field("Accent", detail = "Buttons, links and your own messages.") {
                        AccentPicker(
                            selected = AccentPalette.named(settings.accent),
                            onSelect = { palette -> onUpdate { it.copy(accent = palette.name) } },
                        )
                    }
                    Divider()
                    NavRow(title = "App icon", detail = appIcon.label, icon = Lucide.LayoutGrid, onClick = onOpenAppIcon)
                    Divider()
                    SwitchRow(
                        title = "Pure black",
                        detail = "True black backgrounds in dark mode, easier on OLED screens.",
                        checked = settings.pureBlack,
                        onCheckedChange = { on -> onUpdate { it.copy(pureBlack = on) } },
                    )
                    Divider()
                    SwitchRow(
                        title = "Glow",
                        detail = "Soft colored light behind the top of each screen and under your messages and buttons. Off, everything sits flat.",
                        checked = settings.glow,
                        onCheckedChange = { on -> onUpdate { it.copy(glow = on) } },
                    )
                    Divider()
                    Field("Text size", detail = "On top of your phone's own font size.") {
                        TextSizeSlider(settings.textSize, onSelect = { size -> onUpdate { it.copy(textSize = size) } })
                    }
                    Divider()
                    Field("Chat background", detail = "A photo behind your conversations.") {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (wallpaper != null) {
                                val shape = RoundedCornerShape(Theme[radii][radiusMedium])
                                Image(
                                    wallpaper,
                                    contentDescription = "Current chat background",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(width = 40.dp, height = 64.dp).clip(shape).border(1.dp, Theme[colors][stroke], shape),
                                )
                            }
                            Button(
                                if (hasWallpaper) "Change" else "Choose photo",
                                onClick = onChooseWallpaper,
                                variant = ButtonVariant.Secondary,
                                size = ButtonSize.Small,
                                leadingIcon = Lucide.Image,
                            )
                            if (hasWallpaper) {
                                Button("Remove", onClick = onRemoveWallpaper, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
                            }
                        }
                        if (wallpaperError != null) {
                            Text(wallpaperError, style = Theme[typography][bodySmall], color = Theme[colors][danger])
                        }
                    }
                    if (hasWallpaper) {
                        Divider()
                        Field("Background strength", detail = "How much of the photo shows through.") {
                            SegmentedControl(
                                options = WallpaperStrength.entries,
                                selected = settings.wallpaperStrength,
                                onSelect = { strength -> onUpdate { it.copy(wallpaperStrength = strength) } },
                                optionLabel = { it.name },
                            )
                        }
                    }
                    Divider()
                    SwitchRow(
                        title = "Projects as a list",
                        detail = "Show the chat list's projects as one button that opens a list, instead of a row of chips that scrolls sideways.",
                        checked = settings.projectsAsDropdown,
                        onCheckedChange = { on -> onUpdate { it.copy(projectsAsDropdown = on) } },
                    )
                }

                Section("Chat") {
                    Field(
                        "While a reply is running, Send…",
                        detail = "${settings.runningSend.summary}. Hold Send to pick another way for one message.",
                    ) {
                        SegmentedControl(
                            options = RunningSend.entries,
                            selected = settings.runningSend,
                            onSelect = { mode -> onUpdate { it.copy(runningSend = mode) } },
                            optionLabel = { it.label },
                        )
                    }
                    Divider()
                    NavRow(
                        title = "Starting instruction",
                        detail = settings.startInstruction.trim().ifEmpty { "None. Tell each new chat something first, like to keep replies short." },
                        icon = Lucide.MessageSquarePlus,
                        onClick = onOpenStartInstruction,
                        detailLines = 2,
                    )
                    Divider()
                    SwitchRow(
                        title = "Show reasoning",
                        detail = "The model's thinking, collapsed above each reply.",
                        checked = settings.showReasoning,
                        onCheckedChange = { on -> onUpdate { it.copy(showReasoning = on) } },
                        icon = Lucide.Brain,
                    )
                    Divider()
                    SwitchRow(
                        title = "Show tool activity",
                        detail = "The tools each reply used, such as the terminal or web search.",
                        checked = settings.showToolActivity,
                        onCheckedChange = { on -> onUpdate { it.copy(showToolActivity = on) } },
                        icon = Lucide.Wrench,
                    )
                    Divider()
                    SwitchRow(
                        title = "Show token usage",
                        detail = "What each reply took, under it. The chat menu has the totals and cost.",
                        checked = settings.showUsage,
                        onCheckedChange = { on -> onUpdate { it.copy(showUsage = on) } },
                        icon = Lucide.Gauge,
                    )
                    Divider()
                    SwitchRow(
                        title = "Message timestamps",
                        detail = "The time under each prompt and finished reply.",
                        checked = settings.showTimestamps,
                        onCheckedChange = { on -> onUpdate { it.copy(showTimestamps = on) } },
                        icon = Lucide.Clock,
                    )
                    Divider()
                    SwitchRow(
                        title = "Wrap code lines",
                        detail = "Long lines in code blocks wrap instead of scrolling sideways.",
                        checked = settings.wrapCode,
                        onCheckedChange = { on -> onUpdate { it.copy(wrapCode = on) } },
                        icon = Lucide.WrapText,
                    )
                    Divider()
                    SwitchRow(
                        title = "Show the pet",
                        detail = "The profile's pet sits on the composer and acts out what the agent is doing. Adopt one with /pet.",
                        checked = settings.showPet,
                        onCheckedChange = { on -> onUpdate { it.copy(showPet = on) } },
                        icon = Lucide.Cat,
                    )
                    Divider()
                    SwitchRow(
                        title = "Show link previews",
                        detail = "A card for each web link in a finished reply. Kyllene opens the linked pages and their pictures itself, without your gateway's cookies.",
                        checked = settings.showLinkPreviews,
                        onCheckedChange = { on -> onUpdate { it.copy(showLinkPreviews = on) } },
                        icon = Lucide.Globe,
                    )
                    Divider()
                    SwitchRow(
                        title = "Suggested replies",
                        detail = suggestionsDetail(settings.suggestedReplies, suggestions),
                        checked = settings.suggestedReplies,
                        onCheckedChange = { on -> onUpdate { it.copy(suggestedReplies = on) } },
                        icon = Lucide.Lightbulb,
                    )
                    if (settings.suggestedReplies && (suggestions == SuggestionsPlugin.Missing || suggestions is SuggestionsPlugin.Failed)) {
                        var asked by remember { mutableStateOf(false) }
                        Divider()
                        ActionRow("Install Next Prompt on the gateway", Lucide.Download) { asked = true }
                        SuggestionsInstallDialog(asked, onDismiss = { asked = false }) {
                            asked = false
                            onInstallSuggestions()
                        }
                    }
                    if (settings.suggestedReplies && (suggestions == SuggestionsPlugin.NeedsRestart || suggestions == SuggestionsPlugin.Unknown)) {
                        Divider()
                        ActionRow("Check again", Lucide.RefreshCw, onClick = onCheckSuggestions)
                    }
                }

                Section("Voice") {
                    SwitchRow(
                        title = "Dictation",
                        detail = "A mic in the composer to talk instead of type. Off, the model name gets its room.",
                        checked = settings.dictation,
                        onCheckedChange = { on -> onUpdate { it.copy(dictation = on) } },
                    )
                    Divider()
                    if (settings.dictation) {
                        Field(
                            "Dictate with",
                            detail = when (settings.dictationEngine) {
                                DictationEngine.Device -> "The phone's speech recognizer writes as you talk. Falls back to the gateway on a phone without one."
                                DictationEngine.Gateway -> "The profile's speech-to-text on the gateway, once you stop talking."
                            },
                        ) {
                            SegmentedControl(
                                options = DictationEngine.entries,
                                selected = settings.dictationEngine,
                                onSelect = { engine -> onUpdate { it.copy(dictationEngine = engine) } },
                                optionLabel = { it.name },
                            )
                        }
                        Divider()
                    }
                    Field("Pause before sending", detail = "How long a voice chat waits after you stop talking. Short is Desktop's timing.") {
                        SegmentedControl(
                            options = VoicePause.entries,
                            selected = settings.voicePause,
                            onSelect = { pause -> onUpdate { it.copy(voicePause = pause) } },
                            optionLabel = { it.name },
                        )
                    }
                }

                Section("Notifications") {
                    var installAsked by remember { mutableStateOf(false) }
                    PluginInstallDialog(
                        visible = installAsked,
                        feature = "Notifications anywhere",
                        what = "It watches your bots' chats and sends each new reply to this phone, end-to-end " +
                            "encrypted through ntfy.sh.",
                        onDismiss = { installAsked = false },
                    ) {
                        installAsked = false
                        onPushAnywhere(true)
                    }
                    SwitchRow(
                        title = "Approvals and questions",
                        detail = "When the agent waits on you. Answer right from the notification.",
                        checked = settings.notifyRequests,
                        onCheckedChange = { on -> onUpdate { it.copy(notifyRequests = on) } },
                        icon = Lucide.ShieldAlert,
                    )
                    Divider()
                    SwitchRow(
                        title = "Finished replies",
                        detail = "When a turn ends while Kyllene is in the background. Reply from the notification.",
                        checked = settings.notifyReplies,
                        onCheckedChange = { on -> onUpdate { it.copy(notifyReplies = on) } },
                        icon = Lucide.MessageCircle,
                    )
                    Divider()
                    SwitchRow(
                        title = "Notify for other chats while Kyllene is open",
                        detail = "Replies and requests in chats other than the one on screen notify even while you're in Kyllene.",
                        checked = settings.notifyOtherChatsInApp,
                        onCheckedChange = { on -> onUpdate { it.copy(notifyOtherChatsInApp = on) } },
                        icon = Lucide.BellRing,
                    )
                    Divider()
                    SwitchRow(
                        title = "Alerts for other chats",
                        detail = "While you're in a chat, a dot on Sessions when another chat replies or waits on you. " +
                            "Long-press Sessions for the next one.",
                        checked = settings.otherChatsAlerts,
                        onCheckedChange = { on -> onUpdate { it.copy(otherChatsAlerts = on) } },
                        icon = Lucide.PanelLeft,
                    )
                    // What the alerts do, only while they're on.
                    if (settings.otherChatsAlerts) {
                        Divider()
                        SwitchRow(
                            title = "Banner for other chats",
                            detail = "A card over the chat when another chat replies or waits on you, to open it from there.",
                            checked = settings.otherChatsBanner,
                            onCheckedChange = { on -> onUpdate { it.copy(otherChatsBanner = on) } },
                            icon = Lucide.PanelTop,
                        )
                        Divider()
                        SwitchRow(
                            title = "Vibrate when another chat waits",
                            detail = "A short buzz when another chat starts waiting on your approval or answer.",
                            checked = settings.otherChatsVibrate,
                            onCheckedChange = { on -> onUpdate { it.copy(otherChatsVibrate = on) } },
                            icon = Lucide.Vibrate,
                        )
                    }
                    Divider()
                    SwitchRow(
                        title = "Notifications anywhere",
                        detail = pushDetail(settings.pushAnywhere, push),
                        checked = settings.pushAnywhere,
                        // On installs code on the gateway: said first, done only once the user agrees.
                        onCheckedChange = { on -> if (on) installAsked = true else onPushAnywhere(false) },
                        icon = Lucide.Globe,
                    )
                    val pushUpdate = push.update
                    if (settings.pushAnywhere && pushUpdate != null) {
                        Divider()
                        PluginUpdateRow(pushUpdate, onPushUpdate)
                    }
                    if (settings.pushAnywhere && push.state == PushStatus.State.On) {
                        Divider()
                        ActionRow(
                            when (pushTest) {
                                true -> "Test sent: it should arrive in a moment"
                                false -> "The gateway couldn't send the test"
                                null -> "Send a test notification"
                            },
                            Lucide.Send,
                            onPushTest,
                        )
                    }
                }

                Section("Phone") {
                    NavRow(
                        title = "Phone tools",
                        detail = phoneToolsSummary(settings),
                        icon = Lucide.Smartphone,
                        onClick = onOpenPhoneTools,
                    )
                }

                Section("Privacy") {
                    val canLock = deviceHasScreenLock()
                    SwitchRow(
                        title = "App lock",
                        detail = if (canLock || settings.appLock) {
                            "Ask for your fingerprint, face or screen lock when Kyllene opens and after a minute away. " +
                                "Hides Kyllene in Recents and blocks screenshots of it. Notification actions keep working."
                        } else {
                            "Set a screen lock in Android's settings first."
                        },
                        checked = settings.appLock,
                        onCheckedChange = { on -> if (canLock || !on) onUpdate { it.copy(appLock = on) } },
                        icon = Lucide.Lock,
                    )
                }

                Section("Account") {
                    ActionRow("Check connection", Lucide.Activity, onCheckConnection)
                    Divider()
                    ActionRow("Switch or add a gateway", Lucide.ArrowLeftRight, onOpenGateways)
                    Divider()
                    ActionRow("Sign out", Lucide.LogOut, onSignOut)
                }

                Section("About") {
                    InfoRow("App version", LocalAppVersion.current ?: "Unknown")
                    Divider()
                    SwitchRow(
                        title = "Check for updates",
                        detail = "Asks GitHub, where the app is released, whether there's a newer version. Nothing else is sent.",
                        checked = settings.checkForUpdates,
                        onCheckedChange = { on -> onUpdate { it.copy(checkForUpdates = on) } },
                        icon = Lucide.RefreshCw,
                    )
                    UpdateBanner(Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    Divider()
                    InfoRow("Gateway", info.version?.let { "Hermes $it" } ?: "Checking…")
                    Divider()
                    val uriHandler = LocalUriHandler.current
                    NavRow(
                        title = "Join the Discord",
                        detail = "Get help, report bugs, suggest features and hear about new releases.",
                        icon = Lucide.MessagesSquare,
                        onClick = { uriHandler.openUri(DISCORD_INVITE) },
                    )
                }
            }
        }
    }
}

/** The Kyllene community server; the invite never expires. */
private const val DISCORD_INVITE = "https://discord.gg/wCtyvQ3KcM"

/** The Phone tools row's line: off, or how many tools the agent may use. */
private fun phoneToolsSummary(settings: AppSettings): String {
    val count = settings.enabledPhoneTools().size
    return when {
        !settings.phoneTools -> "Off. Let the agent use this phone, each tool only once you turn it on."
        count == 0 -> "On, but no tool is turned on yet."
        count == 1 -> "1 tool on."
        else -> "$count tools on."
    }
}

/** What Suggested replies does, or where the plugin it needs stands on the gateway. */
internal fun suggestionsDetail(on: Boolean, plugin: SuggestionsPlugin): String {
    val what = "After a reply, the message you'd likely send next, over the composer: tap it to edit and send. " +
        "It comes from the gateway's Next Prompt plugin, which asks a model once per turn."
    if (!on) return what
    return when (plugin) {
        SuggestionsPlugin.Missing -> "The gateway doesn't have the Next Prompt plugin, which makes the suggestions."
        SuggestionsPlugin.Installing -> "Installing Next Prompt on the gateway…"
        SuggestionsPlugin.NeedsRestart -> "Next Prompt is installed. Restart the gateway to start the suggestions."
        is SuggestionsPlugin.Failed -> "Next Prompt wasn't installed: ${plugin.message}"
        is SuggestionsPlugin.Installed -> if (plugin.outdated) {
            "The gateway has Next Prompt ${plugin.version}. A gateway with several profiles needs " +
                "${NextPromptApi.PLUGIN_VERSION} or later; update it in the dashboard's Plugins."
        } else what
        SuggestionsPlugin.Checking, SuggestionsPlugin.Unknown -> what
    }
}

/** What Notifications anywhere does, or where its setup on the gateway stands. */
private fun pushDetail(on: Boolean, push: PushStatus): String {
    if (!on) {
        return "Hear from your bots even off your gateway's network, end-to-end encrypted through ntfy.sh. " +
            "Installs the herald-push plugin on the gateway. Keeps a quiet notification."
    }
    return when (push.state) {
        PushStatus.State.On -> push.detail ?: "On. Bot messages come end-to-end encrypted through ntfy.sh; it only sees scrambled data."
        PushStatus.State.Working -> push.detail ?: "Setting up with the gateway…"
        PushStatus.State.NotInstalled -> "The gateway doesn't have the herald-push plugin. Turn this off and on to install it."
        PushStatus.State.Outdated -> push.update?.let {
            "The gateway's herald-push plugin is ${it.installed}; this needs ${it.available}. Update it below."
        } ?: "The gateway's herald-push plugin is too old."
        PushStatus.State.Failed -> "Couldn't set up: ${push.detail ?: "unknown error"}"
        PushStatus.State.Unknown, PushStatus.State.Off -> "On. Checks with the gateway the next time Kyllene reaches it."
    }
}

/** The gateway in use and who is signed in, with Check, on top of the page. */
@Composable
private fun AccountCard(gatewayName: String, gatewayUrl: String, userLabel: String?, onCheck: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppMark(40.dp, glow = false)
        Column(Modifier.weight(1f)) {
            Text(
                gatewayName,
                style = Theme[typography][body].copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                color = Theme[colors][text],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // Who is signed in first, so a long address is what gets cut; the address only when the name
                // above isn't already it.
                listOfNotNull(userLabel ?: "Signed in", gatewayUrl.substringAfter("://").trimEnd('/').takeIf { it != gatewayName.trimEnd('/') })
                    .joinToString(" · "),
                style = Theme[typography][code].copy(fontSize = 11.5.sp),
                color = Theme[colors][textTertiary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PillButton("Check", Lucide.Activity, onCheck, description = "Check connection")
    }
}

/** A small surface-3 pill with an accent icon, drawn 32dp inside a full touch target. */
@Composable
private fun PillButton(caption: String, icon: ImageVector, onClick: () -> Unit, description: String = caption) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .heightIn(min = MinTouchTarget)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .clip(CircleShape)
                .background(Theme[colors][surface3], CircleShape)
                .indication(interaction, rememberColoredIndication(Theme[colors][text]))
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(13.dp))
            Text(caption, style = Theme[typography][label].copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium), color = Theme[colors][text])
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(),
            style = Theme[typography][eyebrow],
            color = Theme[colors][textTertiary],
            // Spoken as written, not spelled out in capitals.
            modifier = Modifier.padding(start = 6.dp).semantics {
                heading()
                contentDescription = title
            },
        )
        Surface(Modifier.fillMaxWidth()) { Column(content = content) }
    }
}

@Composable
internal fun Divider() {
    Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(1.dp).background(Theme[colors][stroke]))
}

/** A label with its control underneath, for controls too wide to sit at the end of a row. */
@Composable
private fun Field(title: String, detail: String? = null, control: @Composable () -> Unit) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Theme[typography][body], color = Theme[colors][text])
            if (detail != null) Text(detail, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        }
        control()
    }
}

/** A setting that's on or off: the whole row toggles. An [icon] sits on a small tile before the title. */
@Composable
internal fun SwitchRow(title: String, detail: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit, icon: ImageVector? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) IconTile(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = Theme[typography][body], color = Theme[colors][text])
            if (detail != null) Text(detail, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        }
        Switch(checked)
    }
}

@Composable
internal fun IconTile(icon: ImageVector) {
    Box(
        Modifier.size(32.dp).background(Theme[colors][surface2], RoundedCornerShape(Theme[radii][radiusSmall])),
        contentAlignment = Alignment.Center,
    ) {
        UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(15.dp))
    }
}

/**
 * One 30dp swatch per accent preset, drawn in the accent the current light or dark scheme would use, the
 * picked one ringed in its own colour. Each is a 48dp touch target; on a narrow phone or with a large display
 * size the row wraps.
 */
@Composable
private fun AccentPicker(selected: AccentPalette, onSelect: (AccentPalette) -> Unit) {
    val dark = Theme[colors][background].luminance() < 0.5f
    FlowRow(Modifier.selectableGroup()) {
        AccentPalette.all.forEach { palette ->
            val isSelected = palette == selected
            val color = if (dark) palette.dark.accent else palette.light.accent
            Box(
                Modifier
                    .size(MinTouchTarget)
                    .clip(CircleShape)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(palette) })
                    .semantics { contentDescription = palette.name },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .border(2.dp, if (isSelected) color else Color.Transparent, CircleShape)
                        .padding(4.dp)
                        .background(color, CircleShape),
                )
            }
        }
    }
}

/**
 * The text sizes as stops on a track, small "A" to large "A", the current one named underneath. Tap or drag
 * to a stop; screen readers adjust it like any slider.
 */
@Composable
private fun TextSizeSlider(selected: TextSize, onSelect: (TextSize) -> Unit) {
    val sizes = TextSize.entries
    val index = sizes.indexOf(selected)
    val last = sizes.lastIndex
    val onPick by rememberUpdatedState(onSelect)
    val current by rememberUpdatedState(index)
    // Only a new stop is saved: a drag reports every move.
    val pick = { to: Int -> if (to != current) onPick(sizes[to]) }
    // The "A"s keep one size whatever text size is picked, so the track never moves under the finger.
    val fontScale = LocalDensity.current.fontScale
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(20.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                Text("A", style = Theme[typography][label].copy(fontSize = (12 / fontScale).sp), color = Theme[colors][textTertiary])
            }
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .height(MinTouchTarget)
                    .semantics {
                        contentDescription = "Text size"
                        stateDescription = selected.name
                        progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..last.toFloat(), steps = last - 1)
                        setProgress { target ->
                            pick(target.roundToInt().coerceIn(0, last))
                            true
                        }
                    }
                    .pointerInput(last) {
                        // Stops sit half a thumb in from each end, where the thumb's centre can reach.
                        val inset = (SLIDER_THUMB / 2).toPx()
                        fun at(x: Float) = ((x - inset) / (size.width - inset * 2) * last).roundToInt().coerceIn(0, last)
                        detectTapGestures { pick(at(it.x)) }
                    }
                    .pointerInput(last) {
                        val inset = (SLIDER_THUMB / 2).toPx()
                        fun at(x: Float) = ((x - inset) / (size.width - inset * 2) * last).roundToInt().coerceIn(0, last)
                        detectHorizontalDragGestures { change, _ -> pick(at(change.position.x)) }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                val fraction = index.toFloat() / last
                val inset = SLIDER_THUMB / 2
                val track = Modifier.padding(horizontal = inset - 3.dp)
                Box(track.fillMaxWidth().height(4.dp).background(Theme[colors][surface3], CircleShape))
                Box(
                    track
                        .width(6.dp + (maxWidth - SLIDER_THUMB) * fraction)
                        .height(4.dp)
                        .background(Theme[colors][accent], CircleShape),
                )
                // The stops, each a dot centred where the thumb's centre lands; the passed ones in the accent.
                Row(track.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    sizes.forEachIndexed { i, _ ->
                        Box(Modifier.size(6.dp).background(if (i <= index) Theme[colors][accent] else Theme[colors][textMuted], CircleShape))
                    }
                }
                Box(
                    Modifier
                        .offset { IntOffset(((maxWidth - SLIDER_THUMB) * fraction).roundToPx(), 0) }
                        .size(SLIDER_THUMB)
                        .dropShadow(CircleShape, Shadow(radius = 8.dp, color = Color.Black.copy(alpha = 0.35f), offset = DpOffset(0.dp, 2.dp)))
                        .background(Color.White, CircleShape),
                )
            }
            Box(Modifier.width(20.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                Text("A", style = Theme[typography][label].copy(fontSize = (18 / fontScale).sp), color = Theme[colors][textTertiary])
            }
        }
        Text(
            selected.name,
            style = Theme[typography][label].copy(fontSize = 12.sp),
            color = Theme[colors][accentText],
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
        )
    }
}

private val SLIDER_THUMB = 22.dp

@Composable
internal fun InfoRow(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = Theme[typography][body], color = Theme[colors][text])
        Text(value, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary], maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ActionRow(title: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Text(title, style = Theme[typography][body], color = Theme[colors][text])
    }
}
