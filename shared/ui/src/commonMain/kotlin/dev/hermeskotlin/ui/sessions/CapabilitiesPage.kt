package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Search
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.capabilities.McpServer
import dev.hermeskotlin.core.capabilities.McpTestResult
import dev.hermeskotlin.core.capabilities.Skill
import dev.hermeskotlin.core.capabilities.Toolset
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.SegmentedControl
import dev.hermeskotlin.designsystem.components.Switch
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.components.ListSkeleton
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel

/**
 * The sidebar's Capabilities page, after Desktop's: the profile's skills, toolsets and MCP servers,
 * each with a switch. Changes are saved to the profile's config and apply from the next chat.
 */
@Composable
internal fun CapabilitiesPage(
    gateway: SavedGateway,
    profile: String?,
    onBack: () -> Unit,
    onSessionExpired: () -> Unit,
    title: String = "Capabilities",
    /** A line above the tabs, e.g. on when changes take effect. */
    note: String? = null,
    viewModel: CapabilitiesViewModel = koinViewModel(),
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
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(4_000)
            viewModel.dismissMessage()
        }
    }
    CapabilitiesView(state, viewModel.skillQuery, viewModel, onBack = onBack, title = title, note = note)
}

/** What the Capabilities page can ask for; [CapabilitiesViewModel] does it all, previews nothing. */
interface CapabilitiesActions {
    fun selectTab(tab: CapabilityTab)
    fun refresh()
    fun setSkillEnabled(skill: Skill, enabled: Boolean)
    fun setToolsetEnabled(toolset: Toolset, enabled: Boolean)
    fun setServerEnabled(server: McpServer, enabled: Boolean)
    fun testServer(server: McpServer)
    fun dismissMessage()
}

/** The Capabilities page's layout, apart from its view model, so previews can draw it from sample data. */
@Composable
internal fun CapabilitiesView(
    state: CapabilitiesUiState,
    skillQuery: TextFieldState,
    actions: CapabilitiesActions,
    onBack: () -> Unit,
    title: String = "Capabilities",
    note: String? = null,
) {
    Column(Modifier.fillMaxSize()) {
        SubpageHeader(title, onBack = onBack)
        note?.let {
            Text(
                it,
                style = Theme[typography][bodySmall],
                color = Theme[colors][textSecondary],
                modifier = Modifier.padding(start = 24.dp, end = 16.dp, bottom = 10.dp),
            )
        }
        SegmentedControl(
            options = CapabilityTab.entries,
            selected = state.tab,
            onSelect = actions::selectTab,
            optionLabel = { it.label },
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.tab) {
                CapabilityTab.Skills -> SkillsTab(state, skillQuery, actions)
                CapabilityTab.Tools -> TabList(state.toolsets, empty = "No toolsets configured.", onRetry = actions::refresh) { toolsets ->
                    item(key = "note") { Hint("Toolsets the agent gets in new chats.") }
                    items(toolsets, key = { it.name }) { ToolsetRow(it, onToggle = { on -> actions.setToolsetEnabled(it, on) }) }
                }
                CapabilityTab.Mcp -> TabList(
                    state.servers,
                    empty = "No MCP servers. Add one on the gateway with `hermes mcp add`, or from Desktop.",
                    onRetry = actions::refresh,
                ) { servers ->
                    item(key = "note") { Hint("Changes apply from the next chat.") }
                    items(servers, key = { it.name }) { server ->
                        ServerRow(
                            server,
                            test = state.tests[server.name],
                            testing = server.name in state.tests && state.tests[server.name] == null,
                            onToggle = { on -> actions.setServerEnabled(server, on) },
                            onTest = { actions.testServer(server) },
                        )
                    }
                }
            }
        }
        state.message?.let { MessageBanner(it, onDismiss = actions::dismissMessage, modifier = Modifier.padding(bottom = 96.dp)) }
    }
}

@Composable
private fun SkillsTab(state: CapabilitiesUiState, skillQuery: TextFieldState, actions: CapabilitiesActions) {
    val query = skillQuery.text.toString().trim()
    val all = state.skills.items
    val groups = remember(all, query) { skillGroups(all.orEmpty(), query) }
    TabList(state.skills, empty = "No skills yet. The agent writes them as it learns, or install them with `hermes skills`.", onRetry = actions::refresh) { skills ->
        item(key = "search") {
            TextField(
                state = skillQuery,
                placeholder = "Search ${skills.size} skills",
                leadingIcon = Lucide.Search,
                clearable = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (groups.isEmpty()) item(key = "none") { Hint("No skills match “$query”.") }
        groups.forEach { (category, inGroup) ->
            item(key = "c-$category") {
                Text(
                    category.uppercase(),
                    style = Theme[typography][caption],
                    color = Theme[colors][textTertiary],
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(inGroup, key = { "s-${it.name}" }) { skill -> SkillRow(skill, onToggle = { on -> actions.setSkillEnabled(skill, on) }) }
        }
    }
}

/**
 * Skills matching [query] (over name, description and category), grouped by category with
 * uncategorised ones last as "other". Categories are compared ignoring case, as their headers are
 * shown in capitals anyway, so "Research" and "research" are one group.
 */
internal fun skillGroups(skills: List<Skill>, query: String): Map<String, List<Skill>> =
    skills
        .filter { query.isEmpty() || listOfNotNull(it.name, it.description, it.category).any { f -> f.contains(query, ignoreCase = true) } }
        .groupBy { it.category?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: "other" }
        .toSortedMap(compareBy<String> { it == "other" }.thenBy { it })

/** A tab's loading, error and empty states around its list. */
@Composable
private fun <T> TabList(loadable: Loadable<T>, empty: String, onRetry: () -> Unit, content: LazyListScope.(List<T>) -> Unit) {
    val items = loadable.items
    when {
        items == null && loadable.error != null -> EmptyState(Lucide.CloudOff, "Couldn't load", loadable.error, error = true) {
            Button("Try again", onClick = onRetry, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
        }
        items == null -> ListSkeleton()
        items.isEmpty() -> Box(Modifier.fillMaxSize().padding(12.dp)) { ListNotice(empty) }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp)) {
            content(items)
        }
    }
}

@Composable
private fun SkillRow(skill: Skill, onToggle: (Boolean) -> Unit) {
    SwitchRow(
        title = skill.name,
        detail = skill.description.ifBlank { null },
        tag = when (skill.provenance) {
            "hub" -> "hub"
            "agent" -> "learned"
            "external" -> "external"
            else -> null
        },
        checked = skill.enabled,
        onToggle = onToggle,
    )
}

@Composable
private fun ToolsetRow(toolset: Toolset, onToggle: (Boolean) -> Unit) {
    SwitchRow(
        title = toolset.label,
        detail = toolset.description.ifBlank { null },
        tag = when {
            !toolset.configured -> "needs setup"
            toolset.tools.isNotEmpty() -> "${toolset.tools.size} tools"
            else -> null
        },
        checked = toolset.enabled,
        onToggle = onToggle,
    )
}

@Composable
private fun ServerRow(server: McpServer, test: McpTestResult?, testing: Boolean, onToggle: (Boolean) -> Unit, onTest: () -> Unit) {
    Column {
        SwitchRow(
            title = server.name,
            detail = server.target.ifBlank { null },
            tag = server.plugin?.let { "from $it" } ?: server.transport,
            checked = server.enabled,
            // A plugin's server is the plugin's to switch.
            onToggle = onToggle.takeIf { server.plugin == null },
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button("Test", onClick = onTest, variant = ButtonVariant.Outline, size = ButtonSize.Small, loading = testing, pill = true)
            when {
                test == null -> Unit
                test.ok -> Text(
                    "Connected · ${test.tools.size} tools",
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][success],
                )
                else -> Text(
                    test.error ?: "Couldn't connect.",
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][danger],
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Title, detail and a small tag on the left, a switch on the right; a null [onToggle] locks it. */
@Composable
private fun SwitchRow(title: String, detail: String?, tag: String?, checked: Boolean, onToggle: ((Boolean) -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
            .then(if (onToggle != null) Modifier.clickable { onToggle(!checked) } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = Theme[typography][body],
                    color = Theme[colors][if (checked) text else textSecondary],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                tag?.let { Text(it, style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1) }
            }
            detail?.let {
                Text(it, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary], maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Switch(checked)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary], modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
}
