package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.ui.PageFoot
import dev.hermeskotlin.ui.PageRowHeight

import dev.hermeskotlin.designsystem.components.handCursor
import dev.hermeskotlin.ui.CopyableOnDesktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import dev.hermeskotlin.designsystem.components.ScrollbarBox
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.eyebrow
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface3
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.Ellipsis
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.ui.PlatformBackHandler
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
    /** The profile's display name, e.g. a bot's label; the hub and the uninstall sheet name it instead of [profile]. */
    profileLabel: String? = null,
    title: String = "Capabilities",
    /** A line above the tabs, e.g. on when changes take effect. */
    note: String? = null,
    viewModel: CapabilitiesViewModel = koinViewModel(),
    hubViewModel: SkillsHubViewModel = koinViewModel(key = "skills-hub-${profile ?: "default"}"),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val hub = hubViewModel.state.collectAsStateWithLifecycle().value
    var hubOpen by remember { mutableStateOf(false) }
    var uninstalling by remember { mutableStateOf<Skill?>(null) }
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
    // The hub's own state is watched here too: an uninstall runs from this page's list, with the hub page closed.
    LaunchedEffect(hubViewModel, profile, gateway) {
        hubViewModel.onSkillsChanged = viewModel::refresh
        hubViewModel.bind(gateway, profile)
    }
    LaunchedEffect(hub.sessionExpired, hubOpen) {
        if (hub.sessionExpired && !hubOpen) {
            hubViewModel.consumeSessionExpired()
            onSessionExpired()
        }
    }
    LaunchedEffect(hub.message) {
        if (hub.message != null) {
            delay(6_000)
            hubViewModel.dismissMessage()
        }
    }
    val actions = remember(viewModel) {
        object : CapabilitiesActions by viewModel {
            override fun openHub() { hubOpen = true }
            override fun uninstallSkill(skill: Skill) { uninstalling = skill }
        }
    }
    CapabilitiesView(
        state,
        viewModel.skillQuery,
        actions,
        hubOpen = hubOpen,
        onBack = onBack,
        title = title,
        note = note,
        hubMessage = hub.message.takeIf { !hubOpen },
        removing = hub.running,
        onDismissHubMessage = hubViewModel::dismissMessage,
    )
    UninstallSheet(uninstalling, profileLabel ?: profile) { confirmed ->
        uninstalling?.let { if (confirmed) hubViewModel.uninstall(it.name) }
        uninstalling = null
    }
    // The skills search stays composed under the hub; it must not keep the keyboard and the typing.
    val focus = LocalFocusManager.current
    LaunchedEffect(hubOpen) { if (hubOpen) focus.clearFocus() }
    if (hubOpen) {
        PlatformBackHandler(enabled = true) { hubOpen = false }
        // The list beneath is hidden, not covered, so the hub sits on the same backdrop as any page.
        // This consumes taps, so one on a gap in the hub page doesn't reach that list.
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
            SkillsHubPage(gateway, profile, profileLabel, onBack = { hubOpen = false }, onSessionExpired = onSessionExpired, viewModel = hubViewModel)
        }
    }
}

/** Asks before a hub skill is removed from the profile. [onResult] gets true to remove it. */
@Composable
private fun UninstallSheet(skill: Skill?, profile: String?, onResult: (Boolean) -> Unit) {
    var kept by remember { mutableStateOf(skill) }
    if (skill != null) kept = skill
    BottomSheet(visible = skill != null, onDismiss = { onResult(false) }) {
        val s = kept ?: return@BottomSheet
        SheetHeader(s.name, "Remove ${s.name} from ${profile ?: "the main profile"}? Turning it off keeps it installed.")
        Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button("Uninstall", onClick = { onResult(true) }, variant = ButtonVariant.Danger)
            Button("Cancel", onClick = { onResult(false) }, variant = ButtonVariant.Secondary)
        }
    }
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

    /** Opens the skills hub page. */
    fun openHub() {}

    /** Asks to remove a skill the hub installed. */
    fun uninstallSkill(skill: Skill) {}
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
    /** A message from the hub, e.g. after an uninstall, shown with the page's own. */
    hubMessage: String? = null,
    onDismissHubMessage: () -> Unit = {},
    /** Names of hub skills being removed; their "⋯" shows progress and can't be pressed. */
    removing: Set<String> = emptySet(),
    /** The hub page is drawn over this one: hide the list (kept, so it keeps its scroll) and keep screen readers off it. */
    hubOpen: Boolean = false,
) {
    Column(Modifier.fillMaxSize().then(if (hubOpen) Modifier.alpha(0f).clearAndSetSemantics { } else Modifier)) {
        SubpageHeader(title, onBack = onBack, subtitle = note ?: "What the agent can use in new chats")
        SegmentedControl(
            options = CapabilityTab.entries,
            selected = state.tab,
            onSelect = actions::selectTab,
            optionLabel = { it.label },
            // How many each tab holds, once loaded.
            optionBadge = { tab ->
                when (tab) {
                    CapabilityTab.Skills -> state.skills.items?.size
                    CapabilityTab.Tools -> state.toolsets.items?.size
                    CapabilityTab.Mcp -> state.servers.items?.size
                }?.toString()
            },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.tab) {
                CapabilityTab.Skills -> SkillsTab(state, skillQuery, actions, removing)
                CapabilityTab.Tools -> TabList(state.toolsets, empty = "No toolsets configured.", onRetry = actions::refresh) { toolsets ->
                    item(key = "note") { Hint("Toolsets the agent gets in new chats.") }
                    item(key = "toolsets") {
                        RowCard(toolsets, key = { it.name }) { ToolsetRow(it, onToggle = { on -> actions.setToolsetEnabled(it, on) }) }
                    }
                }
                CapabilityTab.Mcp -> TabList(
                    state.servers,
                    empty = "No MCP servers. Add one on the gateway with `hermes mcp add`, or from Hermes Desktop.",
                    onRetry = actions::refresh,
                ) { servers ->
                    item(key = "note") { Hint("Changes apply from the next chat.") }
                    item(key = "servers") {
                        RowCard(servers, key = { it.name }) { server ->
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
        }
        state.message?.let { MessageBanner(it, onDismiss = actions::dismissMessage, modifier = Modifier.padding(bottom = PageFoot)) }
        hubMessage?.let { MessageBanner(it, onDismiss = onDismissHubMessage, modifier = Modifier.padding(bottom = PageFoot)) }
    }
}

@Composable
private fun SkillsTab(state: CapabilitiesUiState, skillQuery: TextFieldState, actions: CapabilitiesActions, removing: Set<String>) {
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
        item(key = "hub") {
            Button("Browse hub", onClick = actions::openHub, variant = ButtonVariant.Secondary, size = ButtonSize.Small, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
        }
        if (groups.isEmpty()) item(key = "none") { Hint("No skills match “$query”.") }
        groups.forEach { (category, inGroup) ->
            item(key = "c-$category") {
                Text(
                    category.uppercase(),
                    style = Theme[typography][eyebrow],
                    color = Theme[colors][textTertiary],
                    modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 6.dp).semantics {
                        heading()
                        contentDescription = category
                    },
                )
            }
            item(key = "g-$category") {
                RowCard(inGroup, key = { "s-${it.name}" }) { skill -> SkillRow(skill, removing = skill.name in removing, onToggle = { on -> actions.setSkillEnabled(skill, on) }, onMore = { actions.uninstallSkill(skill) }) }
            }
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
internal fun <T> TabList(loadable: Loadable<T>, empty: String, onRetry: () -> Unit, content: LazyListScope.(List<T>) -> Unit) {
    val items = loadable.items
    when {
        items == null && loadable.error != null -> EmptyState(Lucide.CloudOff, "Couldn't load", loadable.error, error = true) {
            Button("Try again", onClick = onRetry, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
        }
        items == null -> ListSkeleton()
        items.isEmpty() -> Box(Modifier.fillMaxSize().padding(12.dp)) { ListNotice(empty) }
        // 4dp on top leaves room for a focused search field's ring, which the list would clip.
        else -> {
            val list = rememberLazyListState()
            ScrollbarBox(list, Modifier.fillMaxSize(), bottomInset = PageFoot) {
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = PageFoot)) {
                    content(items)
                }
            }
        }
    }
}

@Composable
private fun SkillRow(skill: Skill, removing: Boolean, onToggle: (Boolean) -> Unit, onMore: () -> Unit) {
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
        onMore = onMore.takeIf { skill.provenance == "hub" },
        moreBusy = removing,
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
            lockedNote = "set by ${server.plugin}",
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button("Test", onClick = onTest, variant = ButtonVariant.Outline, size = ButtonSize.Small, loading = testing)
            when {
                test == null -> Unit
                test.ok -> Text(
                    "Connected · ${test.tools.size} tools",
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][success],
                )
                else -> Box(Modifier.weight(1f)) {
                    CopyableOnDesktop {
                        Text(
                            test.error ?: "Couldn't connect.",
                            style = Theme[typography][bodySmall],
                            color = Theme[colors][danger],
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Rows on one ringed surface card, a hairline between each. */
@Composable
internal fun <T> RowCard(rows: List<T>, key: (T) -> Any, row: @Composable (T) -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][surface])
            .border(1.dp, Theme[colors][stroke], shape),
    ) {
        rows.forEachIndexed { i, item ->
            key(key(item)) {
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Theme[colors][stroke]))
                row(item)
            }
        }
    }
}

/**
 * The name in mono with a small tag pill, the detail under it, a switch on the right; a row switched off
 * has its name in the secondary text. A null [onToggle] locks it, said as [lockedNote].
 * "learned" — a skill the agent wrote itself — takes the accent.
 */
@Composable
private fun SwitchRow(
    title: String,
    detail: String?,
    tag: String?,
    checked: Boolean,
    onToggle: ((Boolean) -> Unit)?,
    lockedNote: String = "can't be changed here",
    /** A "⋯" button after the switch, for a row with more to do. */
    onMore: (() -> Unit)? = null,
    /** Swaps the "⋯" for a spinner of the same size while its action runs. */
    moreBusy: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = PageRowHeight)
            .then(
                if (onToggle != null) {
                    Modifier.handCursor().toggleable(value = checked, role = Role.Switch, onValueChange = onToggle)
                } else {
                    Modifier.semantics(mergeDescendants = true) { stateDescription = "${if (checked) "On" else "Off"}, $lockedNote" }
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // The tag goes under a name too long to leave it room.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = Theme[typography][code].copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                    color = if (checked) Theme[colors][text] else Theme[colors][textSecondary],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                tag?.let { Tag(it, accent = it == "learned") }
            }
            detail?.let {
                Text(it, style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp, lineHeight = 17.5.sp), color = Theme[colors][textTertiary], maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Switch(checked)
        onMore?.let {
            if (moreBusy) Box(Modifier.size(MinTouchTarget), contentAlignment = Alignment.Center) { Spinner(Modifier.size(16.dp)) }
            else IconButton(Lucide.Ellipsis, contentDescription = "More for $title", onClick = it)
        }
    }
}

@Composable
internal fun Tag(text: String, accent: Boolean) {
    Text(
        text,
        style = Theme[typography][caption].copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
        color = if (accent) Theme[colors][accentText] else Theme[colors][textSecondary],
        maxLines = 1,
        modifier = Modifier
            .background(if (accent) Theme[colors][accentSoft] else Theme[colors][surface3], CircleShape)
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary], modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 8.dp))
}
