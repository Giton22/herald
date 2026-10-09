package dev.hermeskotlin.ui.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Puzzle
import com.composables.icons.lucide.RefreshCw
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.plugins.DashboardPlugin
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.sessions.CenteredSpinner
import dev.hermeskotlin.ui.sessions.SubpageHeader
import org.koin.compose.viewmodel.koinViewModel

/**
 * The sidebar's Plugins page: the gateway's dashboard plugins that bring a page of their own — Kanban,
 * Achievements. Tapping one opens its page in the app, served by the gateway itself.
 */
@Composable
internal fun PluginsPage(
    gateway: SavedGateway,
    profile: String?,
    onBack: () -> Unit,
    onSessionExpired: () -> Unit,
    onOpenPlugin: (DashboardPlugin) -> Unit,
    viewModel: PluginsViewModel = koinViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(gateway, profile) { viewModel.bind(gateway, profile) }
    // Consume before the callback: the route changes on it, and the view model outlives the screen,
    // so a stale flag must not bounce the next mount after a fresh sign-in.
    LaunchedEffect(state.sessionExpired) {
        if (state.sessionExpired) {
            viewModel.consumeSessionExpired()
            onSessionExpired()
        }
    }
    PluginsView(state, onBack = onBack, onOpenPlugin = onOpenPlugin, onRetry = viewModel::refresh)
}

/** The page's layout, apart from its view model, so a preview can draw it from sample data. */
@Composable
internal fun PluginsView(
    state: PluginsUiState,
    onBack: () -> Unit,
    onOpenPlugin: (DashboardPlugin) -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SubpageHeader("Plugins", onBack = onBack)
        Text(
            "The gateway's dashboard plugins with a page of their own, like Kanban. One opens in the app.",
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            style = Theme[typography][caption],
            color = Theme[colors][textTertiary],
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val plugins = state.plugins
            when {
                plugins == null && state.error != null -> EmptyState(Lucide.CloudOff, "Couldn't load plugins", state.error, error = true) {
                    Button("Try again", onClick = onRetry, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
                }
                plugins == null -> CenteredSpinner()
                state.openable.isEmpty() -> EmptyState(Lucide.Puzzle, "No plugin pages", "A plugin with a page, like Kanban, shows up here.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.openable, key = { it.name }) { plugin -> PluginRow(plugin) { onOpenPlugin(plugin) } }
                }
            }
        }
    }
}

/** One plugin: its name and what it does, version and where it comes from, and the way in. */
@Composable
private fun PluginRow(plugin: DashboardPlugin, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
            .clickable(onClickLabel = "Open ${plugin.label}") { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(Theme[colors][stroke], RoundedCornerShape(Theme[radii][radiusMedium])),
            contentAlignment = Alignment.Center,
        ) {
            UnstyledIcon(Lucide.Puzzle, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                plugin.label,
                style = Theme[typography][body].copy(fontWeight = FontWeight.Medium),
                color = Theme[colors][text],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (plugin.description.isNotBlank()) {
                Text(
                    plugin.description,
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textSecondary],
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                listOfNotNull(plugin.version?.takeIf { it.isNotBlank() }?.let { "v$it" }, plugin.source).joinToString(" · "),
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
                maxLines = 1,
            )
        }
        UnstyledIcon(Lucide.ChevronRight, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp))
    }
}
