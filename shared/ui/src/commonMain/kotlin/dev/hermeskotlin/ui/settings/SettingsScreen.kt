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
import dev.hermeskotlin.core.gateway.CheckStage
import dev.hermeskotlin.core.gateway.StageResult
import dev.hermeskotlin.ui.connect.ConnectionChecklist
import dev.hermeskotlin.ui.resources.Res
import dev.hermeskotlin.ui.sessions.SubpageHeader
import dev.hermeskotlin.ui.update.UpdateBanner
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
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

/** The connection check: each stage on a line down the sheet, then Check again. */
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
                Text(stringResource(Res.string.common_check_connection), style = Theme[typography][title], color = Theme[colors][text], modifier = Modifier.semantics { heading() })
                Text(stringResource(Res.string.settings_check_sheet_detail), style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
            }
            ConnectionChecklist(results, running = running)
            InversePillButton(
                if (running) stringResource(Res.string.common_checking) else stringResource(Res.string.common_check_again),
                Lucide.RefreshCw,
                onCheckAgain,
                loading = running,
            )
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
            SubpageHeader(stringResource(Res.string.common_settings), onBack = onBack)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                AccountCard(gatewayName, gatewayUrl, info.userLabel, onCheckConnection)

                Section(stringResource(Res.string.settings_section_appearance)) {
                    Field(stringResource(Res.string.settings_theme)) {
                        // The control takes its labels as a plain lambda, so the words are resolved here first.
                        val labels = ThemeMode.entries.associateWith { themeModeLabel(it) }
                        SegmentedControl(
                            options = ThemeMode.entries,
                            selected = settings.theme,
                            onSelect = { mode -> onUpdate { it.copy(theme = mode) } },
                            optionLabel = { labels.getValue(it) },
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
                    Field(stringResource(Res.string.settings_accent), detail = stringResource(Res.string.settings_accent_detail)) {
                        AccentPicker(
                            selected = AccentPalette.named(settings.accent),
                            onSelect = { palette -> onUpdate { it.copy(accent = palette.name) } },
                        )
                    }
                    Divider()
                    NavRow(
                        title = stringResource(Res.string.settings_app_icon),
                        detail = stringResource(appIcon.labelRes),
                        icon = Lucide.LayoutGrid,
                        onClick = onOpenAppIcon,
                    )
                    Divider()
                    SwitchRow(
                        title = stringResource(Res.string.settings_pure_black),
                        detail = stringResource(Res.string.settings_pure_black_detail),
                        checked = settings.pureBlack,
                        onCheckedChange = { on -> onUpdate { it.copy(pureBlack = on) } },
                    )
                    Divider()
                    SwitchRow(
                        title = stringResource(Res.string.settings_glow),
                        detail = stringResource(Res.string.settings_glow_detail),
                        checked = settings.glow,
                        onCheckedChange = { on -> onUpdate { it.copy(glow = on) } },
                    )
                    Divider()
                    Field(stringResource(Res.string.settings_text_size), detail = stringResource(Res.string.settings_text_size_detail)) {
                        TextSizeSlider(settings.textSize, onSelect = { size -> onUpdate { it.copy(textSize = size) } })
                    }
                    Divider()
                    Field(
                        stringResource(Res.string.settings_chat_background),
                        detail = stringResource(Res.string.settings_chat_background_detail),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (wallpaper != null) {
                                val shape = RoundedCornerShape(Theme[radii][radiusMedium])
                                Image(
                                    wallpaper,
                                    contentDescription = stringResource(Res.string.settings_chat_background_current),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(width = 40.dp, height = 64.dp).clip(shape).border(1.dp, Theme[colors][stroke], shape),
                                )
                            }
                            Button(
                                if (hasWallpaper) stringResource(Res.string.common_change) else stringResource(Res.string.common_choose_photo),
                                onClick = onChooseWallpaper,
                                variant = ButtonVariant.Secondary,
                                size = ButtonSize.Small,
                                leadingIcon = Lucide.Image,
                            )
                            if (hasWallpaper) {
                                Button(stringResource(Res.string.common_remove), onClick = onRemoveWallpaper, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
                            }
                        }
                        if (wallpaperError != null) {
                            Text(wallpaperError, style = Theme[typography][bodySmall], color = Theme[colors][danger])
                        }
                    }
                    if (hasWallpaper) {
                        Divider()
                        Field(
                            stringResource(Res.string.settings_background_strength),
                            detail = stringResource(Res.string.settings_background_strength_detail),
                        ) {
                            val labels = WallpaperStrength.entries.associateWith { wallpaperStrengthLabel(it) }
                            SegmentedControl(
                                options = WallpaperStrength.entries,
                                selected = settings.wallpaperStrength,
                                onSelect = { strength -> onUpdate { it.copy(wallpaperStrength = strength) } },
                                optionLabel = { labels.getValue(it) },
                            )
                        }
                    }
                    Divider()
                    SwitchRow(
                        title = stringResource(Res.string.settings_projects_as_list),
                        detail = stringResource(Res.string.settings_projects_as_list_detail),
                        checked = settings.projectsAsDropdown,
                        onCheckedChange = { on -> onUpdate { it.copy(projectsAsDropdown = on) } },
                    )
                }

                Section(stringResource(Res.string.settings_section_chat)) {
                    Field(
                        stringResource(Res.string.settings_running_send),
                        detail = stringResource(
                            Res.string.settings_running_send_detail,
                            runningSendSummary(settings.runningSend),
                        ),
                    ) {
                        val labels = RunningSend.entries.associateWith { runningSendLabel(it) }
                        SegmentedControl(
                            options = RunningSend.entries,
                            selected = settings.runningSend,
                            onSelect = { mode -> onUpdate { it.copy(runningSend = mode) } },
                            optionLabel = { labels.getValue(it) },
                        )
                    }
                    Divider()
                    NavRow(
                        title = stringResource(Res.string.settings_start_instruction),
                        // ifEmpty's lambda isn't composable, so the fallback is chosen before the words are.
                        detail = settings.startInstruction.trim().takeIf { it.isNotEmpty() }
                            ?: stringResource(Res.string.settings_start_instruction_none),
                        icon = Lucide.MessageSquarePlus,
                        onClick = onOpenStartInstruction,
                        detailLines = 2,
                    )
                    Divider()
                    SwitchRow(
                        title = stringResource(Res.string.settings_show_reasoning),
                        detail = stringResource(Res.string.settings_show_reasoning_detail),
                        checked = settings.showReasoning,
                        onCheckedChange = { on -> onUpdate { it.copy(showReasoning = on) } },
                        icon = Lucide.Brain,
                    )
                    Divider()
                    SwitchRow(
                        title = stringResource(Res.string.settings_show_tool_activity),
                        detail = stringResource(Res.string.settings_show_tool_activity_detail),
                        checked = settings.showToolActivity,
                        onCheckedChange = { on -> onUpdate { it.copy(showToolActivity = on) } },
                        icon = Lucide.Wrench,
                    )
                    Divider()
                    SwitchRow(
                        title = stringResource(Res.string.settings_show_usage),
                        detail = stringResource(Res.string.settings_show_usage_detail),
                        checked = settings.showUsage,
                        onCheckedChange = { on -> onUpdate { it.copy(showUsage = on) } },
                        icon = Lucide.Gauge,
                    )
                    Divider()
                    SwitchRow(
                        title = stringResource(Res.string.settings_message_timestamps),
                        detail = stringResource(Res.string.settings_message_timestamps_detail),
                        checked = settings.showTimestamps,
                        onCheckedChange = { on -> onUpdate { it.copy(showTimestamps = on) } },
                        icon = Lucide.Clock,
                    )
                    Divider()
                    SwitchRow(
                        title = stringResource(Res.string.settings_wrap_code),
                        detail = stringResource(Res.string.settings_wrap_code_detail),
                        checked = settings.wrapCode,
                        onCheckedChange = { on -> onUpdate { it.copy(wrapCode = on) } },
                        icon = Lucide.WrapText,
                    )
                    Divider()
                    SwitchRow(
                        title = stringResource(Res.string.settings_show_pet),
                        detail = stringResource(Res.string.settings_show_pet_detail),
                        checked = settings.showPet,
                        onCheckedChange = { on -> onUpdate { it.copy(showPet = on) } },
                        icon = Lucide.Cat,
                    )
                    Divider()
                    SwitchRow(
                        title = stringResource(Res.string.settings_link_previews),
                        detail = stringResource(Res.string.settings_link_previews_detail),
                        checked = settings.showLinkPreviews,
                        onCheckedChange = { on -> onUpdate { it.copy(showLinkPreviews = on) } },
                        icon = Lucide.Globe,
                    )
                    Divider()
                    SwitchRow(
                        title = stringResource(Res.string.settings_suggested_replies),
                        detail = suggestionsDetail(settings.suggestedReplies, suggestions),
                        checked = settings.suggestedReplies,
                        onCheckedChange = { on -> onUpdate { it.copy(suggestedReplies = on) } },
                        icon = Lucide.Lightbulb,
                    )
                    if (settings.suggestedReplies && (suggestions == SuggestionsPlugin.Missing || suggestions is SuggestionsPlugin.Failed)) {
                        var asked by remember { mutableStateOf(false) }
                        Divider()
                        ActionRow(stringResource(Res.string.settings_suggestions_install_row), Lucide.Download) { asked = true }
                        SuggestionsInstallDialog(asked, onDismiss = { asked = false }) {
                            asked = false
                            onInstallSuggestions()
                        }
                    }
                    if (settings.suggestedReplies && (suggestions == SuggestionsPlugin.NeedsRestart || suggestions == SuggestionsPlugin.Unknown)) {
                        Divider()
                        ActionRow(stringResource(Res.string.common_check_again), Lucide.RefreshCw, onClick = onCheckSuggestions)
                    }
                }

                Section(stringResource(Res.string.settings_section_voice)) {
                    SwitchRow(
                        title = stringResource(Res.string.settings_dictation),
                        detail = stringResource(Res.string.settings_dictation_detail),
                        checked = settings.dictation,
                        onCheckedChange = { on -> onUpdate { it.copy(dictation = on) } },
                    )
                    Divider()
                    if (settings.dictation) {
                        Field(
                            stringResource(Res.string.settings_dictate_with),
                            detail = when (settings.dictationEngine) {
                                DictationEngine.Device -> stringResource(Res.string.settings_dictate_device)
                                DictationEngine.Gateway -> stringResource(Res.string.settings_dictate_gateway)
                            },
                        ) {
                            val labels = DictationEngine.entries.associateWith { dictationEngineLabel(it) }
                            SegmentedControl(
                                options = DictationEngine.entries,
                                selected = settings.dictationEngine,
                                onSelect = { engine -> onUpdate { it.copy(dictationEngine = engine) } },
                                optionLabel = { labels.getValue(it) },
                            )
                        }
                        Divider()
                    }
                    Field(
                        stringResource(Res.string.settings_voice_pause),
                        detail = stringResource(Res.string.settings_voice_pause_detail),
                    ) {
                        val labels = VoicePause.entries.associateWith { voicePauseLabel(it) }
                        SegmentedControl(
                            options = VoicePause.entries,
                            selected = settings.voicePause,
                            onSelect = { pause -> onUpdate { it.copy(voicePause = pause) } },
                            optionLabel = { labels.getValue(it) },
                        )
                    }
                }

                Section(stringResource(Res.string.settings_section_notifications)) {
                    var installAsked by remember { mutableStateOf(false) }
                    PluginInstallDialog(
                        visible = installAsked,
                        feature = stringResource(Res.string.settings_notifications_anywhere),
                        what = stringResource(Res.string.plugin_notifications_what),
                        onDismiss = { installAsked = false },
                    ) {
                        installAsked = false
                        onPushAnywhere(true)
                    }
                    SwitchRow(
                        title = stringResource(Res.string.settings_notify_requests),
                        detail = stringResource(Res.string.settings_notify_requests_detail),
                        checked = settings.notifyRequests,
                        onCheckedChange = { on -> onUpdate { it.copy(notifyRequests = on) } },
                        icon = Lucide.ShieldAlert,
                    )
                    Divider()
                    SwitchRow(
                        title = stringResource(Res.string.settings_notify_replies),
                        detail = stringResource(Res.string.settings_notify_replies_detail),
                        checked = settings.notifyReplies,
                        onCheckedChange = { on -> onUpdate { it.copy(notifyReplies = on) } },
                        icon = Lucide.MessageCircle,
                    )
                    Divider()
                    SwitchRow(
                        title = stringResource(Res.string.settings_notify_other_in_app),
                        detail = stringResource(Res.string.settings_notify_other_in_app_detail),
                        checked = settings.notifyOtherChatsInApp,
                        onCheckedChange = { on -> onUpdate { it.copy(notifyOtherChatsInApp = on) } },
                        icon = Lucide.BellRing,
                    )
                    Divider()
                    SwitchRow(
                        title = stringResource(Res.string.settings_alerts_other_chats),
                        detail = stringResource(Res.string.settings_alerts_other_chats_detail),
                        checked = settings.otherChatsAlerts,
                        onCheckedChange = { on -> onUpdate { it.copy(otherChatsAlerts = on) } },
                        icon = Lucide.PanelLeft,
                    )
                    // What the alerts do, only while they're on.
                    if (settings.otherChatsAlerts) {
                        Divider()
                        SwitchRow(
                            title = stringResource(Res.string.settings_banner_other_chats),
                            detail = stringResource(Res.string.settings_banner_other_chats_detail),
                            checked = settings.otherChatsBanner,
                            onCheckedChange = { on -> onUpdate { it.copy(otherChatsBanner = on) } },
                            icon = Lucide.PanelTop,
                        )
                        Divider()
                        SwitchRow(
                            title = stringResource(Res.string.settings_vibrate_other_chats),
                            detail = stringResource(Res.string.settings_vibrate_other_chats_detail),
                            checked = settings.otherChatsVibrate,
                            onCheckedChange = { on -> onUpdate { it.copy(otherChatsVibrate = on) } },
                            icon = Lucide.Vibrate,
                        )
                    }
                    Divider()
                    SwitchRow(
                        title = stringResource(Res.string.settings_notifications_anywhere),
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
                                true -> stringResource(Res.string.settings_push_test_sent)
                                false -> stringResource(Res.string.settings_push_test_failed)
                                null -> stringResource(Res.string.settings_push_test)
                            },
                            Lucide.Send,
                            onPushTest,
                        )
                    }
                }

                Section(stringResource(Res.string.settings_section_phone)) {
                    NavRow(
                        title = stringResource(Res.string.settings_phone_tools),
                        detail = phoneToolsSummary(settings),
                        icon = Lucide.Smartphone,
                        onClick = onOpenPhoneTools,
                    )
                }

                Section(stringResource(Res.string.settings_section_privacy)) {
                    val canLock = deviceHasScreenLock()
                    SwitchRow(
                        title = stringResource(Res.string.settings_app_lock),
                        detail = if (canLock || settings.appLock) {
                            stringResource(Res.string.settings_app_lock_detail)
                        } else {
                            stringResource(Res.string.settings_app_lock_needs_screen_lock)
                        },
                        checked = settings.appLock,
                        onCheckedChange = { on -> if (canLock || !on) onUpdate { it.copy(appLock = on) } },
                        icon = Lucide.Lock,
                    )
                }

                Section(stringResource(Res.string.settings_section_account)) {
                    ActionRow(stringResource(Res.string.common_check_connection), Lucide.Activity, onCheckConnection)
                    Divider()
                    ActionRow(stringResource(Res.string.settings_switch_gateway), Lucide.ArrowLeftRight, onOpenGateways)
                    Divider()
                    ActionRow(stringResource(Res.string.settings_sign_out), Lucide.LogOut, onSignOut)
                }

                Section(stringResource(Res.string.settings_section_about)) {
                    InfoRow(stringResource(Res.string.settings_app_version), LocalAppVersion.current ?: stringResource(Res.string.common_unknown))
                    Divider()
                    SwitchRow(
                        title = stringResource(Res.string.settings_check_updates),
                        detail = stringResource(Res.string.settings_check_updates_detail),
                        checked = settings.checkForUpdates,
                        onCheckedChange = { on -> onUpdate { it.copy(checkForUpdates = on) } },
                        icon = Lucide.RefreshCw,
                    )
                    UpdateBanner(Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    Divider()
                    InfoRow(
                        stringResource(Res.string.settings_gateway),
                        info.version?.let { stringResource(Res.string.settings_hermes_version, it) }
                            ?: stringResource(Res.string.common_checking),
                    )
                    Divider()
                    val uriHandler = LocalUriHandler.current
                    NavRow(
                        title = stringResource(Res.string.settings_join_discord),
                        detail = stringResource(Res.string.settings_join_discord_detail),
                        icon = Lucide.MessagesSquare,
                        onClick = { uriHandler.openUri(DISCORD_INVITE) },
                    )
                }
            }
        }
    }
}

/** The Herald community server; the invite never expires. */
private const val DISCORD_INVITE = "https://discord.gg/wCtyvQ3KcM"

/** The Phone tools row's line: off, or how many tools the agent may use. */
@Composable
private fun phoneToolsSummary(settings: AppSettings): String {
    val count = settings.enabledPhoneTools().size
    return when {
        !settings.phoneTools -> stringResource(Res.string.settings_phone_tools_off)
        count == 0 -> stringResource(Res.string.settings_phone_tools_none_on)
        else -> pluralStringResource(Res.plurals.settings_phone_tools_count, count, count)
    }
}

/**
 * Which line Suggested replies shows: its own description, or where the Next Prompt plugin stands. Kept apart
 * from the words so a test can check the choice without Compose.
 */
internal sealed interface SuggestionsLine {
    /** The switch is off, or there is nothing to report: the line only says what the feature does. */
    data object What : SuggestionsLine
    data object Missing : SuggestionsLine
    data object Installing : SuggestionsLine
    data object NeedsRestart : SuggestionsLine

    /** [message] is why the install failed. */
    data class Failed(val message: String) : SuggestionsLine

    /** [have] is the version on the gateway; [need] the one this Herald asks for. */
    data class Outdated(val have: String?, val need: String) : SuggestionsLine
}

/** The line Suggested replies shows, from its switch and where its plugin stands on the gateway. */
internal fun suggestionsLine(on: Boolean, plugin: SuggestionsPlugin): SuggestionsLine {
    if (!on) return SuggestionsLine.What
    return when (plugin) {
        SuggestionsPlugin.Missing -> SuggestionsLine.Missing
        SuggestionsPlugin.Installing -> SuggestionsLine.Installing
        SuggestionsPlugin.NeedsRestart -> SuggestionsLine.NeedsRestart
        is SuggestionsPlugin.Failed -> SuggestionsLine.Failed(plugin.message)
        is SuggestionsPlugin.Installed -> if (plugin.outdated) {
            SuggestionsLine.Outdated(plugin.version, NextPromptApi.PLUGIN_VERSION)
        } else {
            SuggestionsLine.What
        }
        SuggestionsPlugin.Checking, SuggestionsPlugin.Unknown -> SuggestionsLine.What
    }
}

/** [line] in the app's language. */
@Composable
private fun suggestionsDetail(on: Boolean, plugin: SuggestionsPlugin): String =
    when (val line = suggestionsLine(on, plugin)) {
        SuggestionsLine.What -> stringResource(Res.string.settings_suggestions_what)
        SuggestionsLine.Missing -> stringResource(Res.string.settings_suggestions_missing)
        SuggestionsLine.Installing -> stringResource(Res.string.settings_suggestions_installing)
        SuggestionsLine.NeedsRestart -> stringResource(Res.string.settings_suggestions_needs_restart)
        is SuggestionsLine.Failed -> stringResource(Res.string.settings_suggestions_failed, line.message)
        is SuggestionsLine.Outdated -> stringResource(Res.string.settings_suggestions_outdated, line.have.orEmpty(), line.need)
    }

/** What Notifications anywhere does, or where its setup on the gateway stands. */
@Composable
private fun pushDetail(on: Boolean, push: PushStatus): String {
    if (!on) return stringResource(Res.string.settings_notifications_anywhere_detail)
    return when (push.state) {
        PushStatus.State.On -> push.detail ?: stringResource(Res.string.settings_push_on)
        PushStatus.State.Working -> push.detail ?: stringResource(Res.string.settings_push_working)
        PushStatus.State.NotInstalled -> stringResource(Res.string.settings_push_not_installed)
        PushStatus.State.Outdated -> push.update?.let {
            stringResource(Res.string.settings_push_outdated, it.installed, it.available)
        } ?: stringResource(Res.string.settings_push_too_old)
        PushStatus.State.Failed -> stringResource(
            Res.string.settings_push_failed,
            push.detail ?: stringResource(Res.string.settings_error_unknown),
        )
        PushStatus.State.Unknown, PushStatus.State.Off -> stringResource(Res.string.settings_push_unknown)
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
                listOfNotNull(userLabel ?: stringResource(Res.string.settings_signed_in), gatewayUrl.substringAfter("://").trimEnd('/').takeIf { it != gatewayName.trimEnd('/') })
                    .joinToString(" · "),
                style = Theme[typography][code].copy(fontSize = 11.5.sp),
                color = Theme[colors][textTertiary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PillButton(
            stringResource(Res.string.common_check),
            Lucide.Activity,
            onCheck,
            description = stringResource(Res.string.common_check_connection),
        )
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
            val name = accentName(palette)
            Box(
                Modifier
                    .size(MinTouchTarget)
                    .clip(CircleShape)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(palette) })
                    .semantics { contentDescription = name },
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
    val textSizeA11y = stringResource(Res.string.settings_text_size)
    val sizeName = textSizeLabel(selected)
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
                        contentDescription = textSizeA11y
                        stateDescription = sizeName
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
            sizeName,
            style = Theme[typography][label].copy(fontSize = 12.sp),
            color = Theme[colors][accentText],
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
        )
    }
}

private val SLIDER_THUMB = 22.dp

/** The theme modes as the Theme control names them. */
@Composable
private fun themeModeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.System -> Res.string.settings_theme_system
        ThemeMode.Light -> Res.string.settings_theme_light
        ThemeMode.Dark -> Res.string.settings_theme_dark
    },
)

/** How faintly the chat background shows, as the Background strength control names it. */
@Composable
private fun wallpaperStrengthLabel(strength: WallpaperStrength): String = stringResource(
    when (strength) {
        WallpaperStrength.Faint -> Res.string.wallpaper_faint
        WallpaperStrength.Medium -> Res.string.wallpaper_medium
        WallpaperStrength.Strong -> Res.string.wallpaper_strong
    },
)

/** The text sizes as the slider names them. */
@Composable
private fun textSizeLabel(size: TextSize): String = stringResource(
    when (size) {
        TextSize.Small -> Res.string.text_size_small
        TextSize.Default -> Res.string.text_size_default
        TextSize.Large -> Res.string.text_size_large
        TextSize.Largest -> Res.string.text_size_largest
    },
)

/** Who writes down dictation, as the Dictate with control names it. */
@Composable
private fun dictationEngineLabel(engine: DictationEngine): String = stringResource(
    when (engine) {
        DictationEngine.Device -> Res.string.dictation_device
        DictationEngine.Gateway -> Res.string.dictation_gateway
    },
)

/** How long a voice chat waits, as the Pause control names it. */
@Composable
private fun voicePauseLabel(pause: VoicePause): String = stringResource(
    when (pause) {
        VoicePause.Short -> Res.string.voice_pause_short
        VoicePause.Normal -> Res.string.voice_pause_normal
        VoicePause.Long -> Res.string.voice_pause_long
    },
)

/** What a message sent mid-reply does, as the Send control names it. */
@Composable
private fun runningSendLabel(mode: RunningSend): String = stringResource(
    when (mode) {
        RunningSend.Steer -> Res.string.running_send_steer_label
        RunningSend.Queue -> Res.string.running_send_queue_label
        RunningSend.StopAndSend -> Res.string.running_send_stop_and_send_label
    },
)

/** The same, as the sentence under the control quotes it. */
@Composable
private fun runningSendSummary(mode: RunningSend): String = stringResource(
    when (mode) {
        RunningSend.Steer -> Res.string.running_send_steer
        RunningSend.Queue -> Res.string.running_send_queue
        RunningSend.StopAndSend -> Res.string.running_send_stop_and_send
    },
)

/** An accent swatch's name for screen readers; a palette the catalogue doesn't know keeps its own name. */
@Composable
private fun accentName(palette: AccentPalette): String {
    val key = when (palette.name) {
        "Blue" -> Res.string.accent_blue
        "Violet" -> Res.string.accent_violet
        "Green" -> Res.string.accent_green
        "Orange" -> Res.string.accent_orange
        "Pink" -> Res.string.accent_pink
        "Teal" -> Res.string.accent_teal
        else -> null
    }
    return if (key != null) stringResource(key) else palette.name
}

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
