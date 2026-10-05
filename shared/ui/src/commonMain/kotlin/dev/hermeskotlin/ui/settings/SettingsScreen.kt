package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.ArrowLeftRight
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.TextSize
import dev.hermeskotlin.core.settings.ThemeMode
import dev.hermeskotlin.core.settings.VoicePause
import dev.hermeskotlin.core.settings.RunningSend
import dev.hermeskotlin.core.settings.WallpaperStrength
import dev.hermeskotlin.ui.chat.label
import dev.hermeskotlin.ui.chat.summary
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.components.SegmentedControl
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.components.Switch
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.LocalAppVersion
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.connect.ConnectionChecklist
import dev.hermeskotlin.ui.sessions.SubpageHeader
import dev.hermeskotlin.ui.update.UpdateBanner
import org.koin.compose.viewmodel.koinViewModel

/** App preferences, the signed-in account and version info, as one page over the home screen. */
@Composable
fun SettingsScreen(
    gateway: SavedGateway,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onChangeGateway: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val info by viewModel.gateway.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
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
    LaunchedEffect(gateway) { viewModel.bind(gateway) }
    PlatformBackHandler(enabled = !checkOpen, onBack = onBack)
    SettingsView(
        settings, info, gateway.url, viewModel::update, onBack, onSignOut, onChangeGateway,
        onCheckConnection = {
            checkOpen = true
            viewModel.runConnectionCheck()
        },
        wallpaper = rememberWallpaperBitmap(wallpaper),
        hasWallpaper = wallpaper != null,
        wallpaperError = wallpaperError,
        onChooseWallpaper = chooseWallpaper,
        onRemoveWallpaper = {
            wallpaperError = null
            viewModel.removeWallpaper()
        },
    )
    BottomSheet(visible = checkOpen, onDismiss = { checkOpen = false }) {
        SheetHeader("Check connection", subtitle = "Each stage is tested on its own.")
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ConnectionChecklist(check.results, running = check.running)
            Button(
                if (check.running) "Checking…" else "Check again",
                onClick = viewModel::runConnectionCheck,
                variant = ButtonVariant.Secondary,
                loading = check.running,
                leadingIcon = Lucide.RefreshCw,
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
    onChangeGateway: () -> Unit,
    onCheckConnection: () -> Unit = {},
    /** The chat background, once decoded. */
    wallpaper: ImageBitmap? = null,
    hasWallpaper: Boolean = wallpaper != null,
    wallpaperError: String? = null,
    onChooseWallpaper: () -> Unit = {},
    onRemoveWallpaper: () -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            SubpageHeader("Settings", onBack = onBack)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Section("Appearance") {
                    Field("Theme") {
                        SegmentedControl(
                            options = ThemeMode.entries,
                            selected = settings.theme,
                            onSelect = { mode -> onUpdate { it.copy(theme = mode) } },
                            optionLabel = { it.name },
                        )
                    }
                    Divider()
                    SwitchRow(
                        title = "Pure black",
                        detail = "True black backgrounds in dark mode, easier on OLED screens.",
                        checked = settings.pureBlack,
                        onCheckedChange = { on -> onUpdate { it.copy(pureBlack = on) } },
                    )
                    Divider()
                    Field("Text size", detail = "On top of your phone's own font size.") {
                        SegmentedControl(
                            options = TextSize.entries,
                            selected = settings.textSize,
                            onSelect = { size -> onUpdate { it.copy(textSize = size) } },
                            optionLabel = { it.name },
                        )
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
                    SwitchRow(
                        title = "Show reasoning",
                        detail = "The model's thinking, collapsed above each reply.",
                        checked = settings.showReasoning,
                        onCheckedChange = { on -> onUpdate { it.copy(showReasoning = on) } },
                    )
                    Divider()
                    SwitchRow(
                        title = "Show tool activity",
                        detail = "The tools each reply used, such as the terminal or web search.",
                        checked = settings.showToolActivity,
                        onCheckedChange = { on -> onUpdate { it.copy(showToolActivity = on) } },
                    )
                    Divider()
                    SwitchRow(
                        title = "Show token usage",
                        detail = "What each reply took, under it. The chat menu has the totals and cost.",
                        checked = settings.showUsage,
                        onCheckedChange = { on -> onUpdate { it.copy(showUsage = on) } },
                    )
                    Divider()
                    SwitchRow(
                        title = "Message timestamps",
                        detail = "The time under each prompt and finished reply.",
                        checked = settings.showTimestamps,
                        onCheckedChange = { on -> onUpdate { it.copy(showTimestamps = on) } },
                    )
                    Divider()
                    SwitchRow(
                        title = "Wrap code lines",
                        detail = "Long lines in code blocks wrap instead of scrolling sideways.",
                        checked = settings.wrapCode,
                        onCheckedChange = { on -> onUpdate { it.copy(wrapCode = on) } },
                    )
                    Divider()
                    SwitchRow(
                        title = "Show the pet",
                        detail = "The profile's pet sits on the composer and acts out what the agent is doing. Adopt one with /pet.",
                        checked = settings.showPet,
                        onCheckedChange = { on -> onUpdate { it.copy(showPet = on) } },
                    )
                }

                Section("Voice") {
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
                    SwitchRow(
                        title = "Approvals and questions",
                        detail = "When the agent waits on you. Answer right from the notification.",
                        checked = settings.notifyRequests,
                        onCheckedChange = { on -> onUpdate { it.copy(notifyRequests = on) } },
                    )
                    Divider()
                    SwitchRow(
                        title = "Finished replies",
                        detail = "When a turn ends while Herald is in the background. Reply from the notification.",
                        checked = settings.notifyReplies,
                        onCheckedChange = { on -> onUpdate { it.copy(notifyReplies = on) } },
                    )
                    Divider()
                    SwitchRow(
                        title = "Stay connected",
                        detail = "Also catch turns started on other devices, like Hermes Desktop. " +
                            "Keeps a quiet notification and uses more battery.",
                        checked = settings.stayConnected,
                        onCheckedChange = { on -> onUpdate { it.copy(stayConnected = on) } },
                    )
                }

                Section("Privacy") {
                    val canLock = deviceHasScreenLock()
                    SwitchRow(
                        title = "App lock",
                        detail = if (canLock || settings.appLock) {
                            "Ask for your fingerprint, face or screen lock when Herald opens and after a minute away. " +
                                "Hides Herald in Recents and blocks screenshots of it. Notification actions keep working."
                        } else {
                            "Set a screen lock in Android's settings first."
                        },
                        checked = settings.appLock,
                        onCheckedChange = { on -> if (canLock || !on) onUpdate { it.copy(appLock = on) } },
                    )
                }

                Section("Account") {
                    InfoRow(info.userLabel ?: "Signed in", gatewayUrl)
                    Divider()
                    ActionRow("Check connection", Lucide.Activity, onCheckConnection)
                    Divider()
                    ActionRow("Sign out", Lucide.LogOut, onSignOut)
                    Divider()
                    ActionRow("Use a different gateway", Lucide.ArrowLeftRight, onChangeGateway)
                }

                Section("About") {
                    InfoRow("App version", LocalAppVersion.current ?: "Unknown")
                    Divider()
                    SwitchRow(
                        title = "Check for updates",
                        detail = "Asks GitHub, where the app is released, whether there's a newer version. Nothing else is sent.",
                        checked = settings.checkForUpdates,
                        onCheckedChange = { on -> onUpdate { it.copy(checkForUpdates = on) } },
                    )
                    UpdateBanner(Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    Divider()
                    InfoRow("Gateway", info.version?.let { "Hermes $it" } ?: "Checking…")
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = Theme[typography][label], color = Theme[colors][textTertiary], modifier = Modifier.padding(start = 4.dp))
        Surface(Modifier.fillMaxWidth()) { Column(content = content) }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(Theme[colors][stroke]))
}

/** A label with its control underneath, for controls too wide to sit at the end of a row. */
@Composable
private fun Field(title: String, detail: String? = null, control: @Composable () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Theme[typography][body], color = Theme[colors][text])
            if (detail != null) Text(detail, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
        }
        control()
    }
}

@Composable
private fun SwitchRow(title: String, detail: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Theme[typography][body], color = Theme[colors][text])
            if (detail != null) Text(detail, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
        }
        Switch(checked)
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = Theme[typography][body], color = Theme[colors][text])
        Text(value, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary], maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ActionRow(title: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(20.dp))
        Text(title, style = Theme[typography][body], color = Theme[colors][text])
    }
}
