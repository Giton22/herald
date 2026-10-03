package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeftRight
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.settings.TextSize
import dev.hermeskotlin.core.settings.ThemeMode
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.SegmentedControl
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
import dev.hermeskotlin.ui.sessions.SubpageHeader
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
    LaunchedEffect(gateway) { viewModel.bind(gateway) }
    PlatformBackHandler(enabled = true, onBack = onBack)

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
                            onSelect = { mode -> viewModel.update { it.copy(theme = mode) } },
                            optionLabel = { it.name },
                        )
                    }
                    Divider()
                    SwitchRow(
                        title = "Pure black",
                        detail = "True black backgrounds in dark mode, easier on OLED screens.",
                        checked = settings.pureBlack,
                        onCheckedChange = { on -> viewModel.update { it.copy(pureBlack = on) } },
                    )
                    Divider()
                    Field("Text size", detail = "On top of your phone's own font size.") {
                        SegmentedControl(
                            options = TextSize.entries,
                            selected = settings.textSize,
                            onSelect = { size -> viewModel.update { it.copy(textSize = size) } },
                            optionLabel = { it.name },
                        )
                    }
                }

                Section("Chat") {
                    SwitchRow(
                        title = "Show reasoning",
                        detail = "The model's thinking, collapsed above each reply.",
                        checked = settings.showReasoning,
                        onCheckedChange = { on -> viewModel.update { it.copy(showReasoning = on) } },
                    )
                    Divider()
                    SwitchRow(
                        title = "Show tool activity",
                        detail = "The tools each reply used, such as the terminal or web search.",
                        checked = settings.showToolActivity,
                        onCheckedChange = { on -> viewModel.update { it.copy(showToolActivity = on) } },
                    )
                }

                Section("Account") {
                    InfoRow(info.userLabel ?: "Signed in", gateway.url)
                    Divider()
                    ActionRow("Sign out", Lucide.LogOut, onSignOut)
                    Divider()
                    ActionRow("Use a different gateway", Lucide.ArrowLeftRight, onChangeGateway)
                }

                Section("About") {
                    InfoRow("App version", LocalAppVersion.current ?: "Unknown")
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
