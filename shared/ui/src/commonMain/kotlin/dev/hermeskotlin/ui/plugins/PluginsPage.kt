package dev.hermeskotlin.ui.plugins

import dev.hermeskotlin.designsystem.components.handCursor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.sp
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import dev.hermeskotlin.ui.isDesktop
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import dev.hermeskotlin.designsystem.components.ScrollbarBox
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
import dev.hermeskotlin.designsystem.components.Tooltip
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
        SubpageHeader(
            "Plugins",
            onBack = onBack,
            subtitle = "The gateway's dashboard plugins with a page of their own, like Kanban. One opens in the app.",
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val plugins = state.plugins
            when {
                plugins == null && state.error != null -> EmptyState(Lucide.CloudOff, "Couldn't load plugins", state.error, error = true) {
                    Button("Try again", onClick = onRetry, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
                }
                plugins == null -> CenteredSpinner()
                state.openable.isEmpty() -> EmptyState(Lucide.Puzzle, "No plugin pages", "A plugin with a page, like Kanban, shows up here.")
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val list = rememberLazyListState()
                    // A wide desktop page sets the cards side by side, as a desktop's catalogue does; a phone, one a row.
                    val columns = if (isDesktop) ((maxWidth - 28.dp) / GRID_CELL).toInt().coerceIn(1, 3) else 1
                    ScrollbarBox(list, Modifier.fillMaxSize(), bottomInset = 24.dp) {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            state = list,
                            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // A ringed card each, laid out lazily, so a gateway with many plugins scrolls as lightly as one with two.
                            items(state.openable.chunked(columns), key = { row -> row.first().name }) { row ->
                                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { plugin -> PluginRow(plugin, Modifier.weight(1f).fillMaxHeight()) { onOpenPlugin(plugin) } }
                                    // The last row's cards keep the others' width.
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How wide a plugin's card is at the least in the desktop's grid. */
private val GRID_CELL = 300.dp

/** One plugin: its name and what it does, version and where it comes from, and the way in. */
@Composable
private fun PluginRow(plugin: DashboardPlugin, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Row(
        modifier
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(Theme[radii][radiusLarge]))
            .background(Theme[colors][surface])
            .border(1.dp, Theme[colors][stroke], RoundedCornerShape(Theme[radii][radiusLarge]))
            .handCursor()
            .clickable(onClickLabel = "Open ${plugin.label}") { onClick() }
            .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(Theme[colors][accentSoft], RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            UnstyledIcon(Lucide.Puzzle, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                plugin.label,
                style = Theme[typography][body].copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = Theme[colors][text],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (plugin.description.isNotBlank()) {
                // Two lines cut most descriptions short; on the desktop, resting the pointer on them shows the rest.
                Tooltip(plugin.description) {
                    Text(
                        plugin.description,
                        style = Theme[typography][bodySmall],
                        color = Theme[colors][textSecondary],
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                listOfNotNull(plugin.version?.takeIf { it.isNotBlank() }?.let { "v$it" }, plugin.source).joinToString(" · "),
                style = Theme[typography][code].copy(fontSize = 11.5.sp),
                color = Theme[colors][textTertiary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        UnstyledIcon(Lucide.ChevronRight, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp))
    }
}
