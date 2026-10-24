package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Search
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.capabilities.HubFinding
import dev.hermeskotlin.core.capabilities.HubScan
import dev.hermeskotlin.core.capabilities.HubSkill
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.MarkdownText
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.eyebrow
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.components.ListSkeleton
import dev.hermeskotlin.ui.PlatformBackHandler

/** Browse, search and install skills from the hub, into [profile]'s skills; [profileLabel] is how the subtitle names it. */
@Composable
internal fun SkillsHubPage(
    gateway: SavedGateway,
    profile: String?,
    profileLabel: String?,
    onBack: () -> Unit,
    onSessionExpired: () -> Unit,
    viewModel: SkillsHubViewModel,
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    // CapabilitiesPage binds the view model and wires onSkillsChanged; bind again only so the
    // load below never runs ahead of a binding change (it does nothing when already bound).
    LaunchedEffect(gateway, profile) {
        viewModel.bind(gateway, profile)
        viewModel.openHub()
    }
    // Back leaves an open skill for the lists before it leaves the hub.
    PlatformBackHandler(enabled = state.detail != null) { viewModel.closeDetail() }
    // The search field stays composed under an open skill; it must not keep the keyboard and the typing.
    val focus = LocalFocusManager.current
    val detailOpen = state.detail != null
    LaunchedEffect(detailOpen) { if (detailOpen) focus.clearFocus() }
    // Whichever way the page leaves, its sheets go with it.
    DisposableEffect(viewModel) { onDispose { viewModel.closeHub() } }
    // The view model debounces; the page only reports what was typed.
    LaunchedEffect(viewModel) { snapshotFlow { viewModel.query.text.toString() }.collect { viewModel.search(it) } }
    LaunchedEffect(state.sessionExpired) {
        if (state.sessionExpired) {
            viewModel.consumeSessionExpired()
            onSessionExpired()
        }
    }
    SkillsHubView(state, viewModel.query, viewModel, onBack, subtitle = "Installs into ${profileLabel ?: profile ?: "the main profile"}")
}

@Composable
internal fun SkillsHubView(
    state: SkillsHubUiState,
    query: TextFieldState,
    actions: SkillsHubActions,
    onBack: () -> Unit,
    subtitle: String,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // An open skill is a page over the lists; they stay composed (hidden) so they keep their scroll.
            HubBrowse(state, query, actions, onBack, subtitle, Modifier.fillMaxSize().then(if (state.detail != null) Modifier.alpha(0f).clearAndSetSemantics { } else Modifier))
            // Swallows taps on its gaps, so none reaches the hidden lists.
            state.detail?.let { HubDetailPage(it, state, actions, Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) }
        }
        // Above the sidebar's New chat button, as on the Capabilities page.
        state.message?.let { MessageBanner(it, onDismiss = actions::dismissMessage, modifier = Modifier.padding(bottom = 96.dp)) }
    }
    HubConfirmSheet(state.confirm, actions)
}

/** The hub's header, search and lists. */
@Composable
private fun HubBrowse(state: SkillsHubUiState, query: TextFieldState, actions: SkillsHubActions, onBack: () -> Unit, subtitle: String, modifier: Modifier) {
    Column(modifier) {
        SubpageHeader(
            "Skills hub",
            onBack = onBack,
            subtitle = subtitle,
            actions = {
                if (!state.unsupported) {
                    Button(
                        "Update all",
                        onClick = actions::updateAll,
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Small,
                        loading = UPDATE_ALL in state.running,
                    )
                }
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.unsupported -> Box(Modifier.fillMaxSize().padding(12.dp)) { ListNotice("This gateway doesn't have the skills hub yet.") }
                else -> Column(Modifier.fillMaxSize()) {
                    TextField(
                        state = query,
                        placeholder = "Search the hub",
                        leadingIcon = Lucide.Search,
                        clearable = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) { HubLists(state, actions) }
                }
            }
        }
    }
}

@Composable
private fun HubLists(state: SkillsHubUiState, actions: SkillsHubActions) {
    val results = state.results
    when {
        results != null -> {
            val found = results.items
            when {
                found == null && results.error != null -> HubError(results.error, actions::refresh)
                found == null -> ListSkeleton()
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = HubPadding) {
                    if (state.timedOut.isNotEmpty()) item(key = "timeout") { Hint("Some sources didn't answer: ${state.timedOut.joinToString()}") }
                    if (found.isEmpty()) item(key = "none") { Hint("No skills match your search.") }
                    else item(key = "results") { HubRows(found, state, actions) }
                }
            }
        }
        state.featured.items == null && state.official.items == null ->
            if (state.featured.error != null && state.official.error != null) HubError(state.featured.error, actions::refresh) else ListSkeleton()
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = HubPadding) {
            val featured = state.featured.items.orEmpty()
            if (featured.isNotEmpty()) {
                item(key = "h-featured") { HubHeading("FEATURED") }
                item(key = "featured") { HubRows(featured, state, actions) }
            } else if (state.featured.error != null) {
                item(key = "h-featured") { HubHeading("FEATURED") }
                item(key = "featured-error") { Hint(state.featured.error) }
                item(key = "featured-retry") {
                    Button("Try again", onClick = actions::refresh, variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = Lucide.RefreshCw, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
                }
            }
            item(key = "h-official") { HubHeading("OFFICIAL") }
            val official = state.official.items
            when {
                official == null && state.official.error != null -> {
                    item(key = "official-error") { Hint(state.official.error) }
                    item(key = "official-retry") {
                        Button("Try again", onClick = actions::refresh, variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = Lucide.RefreshCw, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
                    }
                }
                official == null -> item(key = "official-loading") { Hint("Loading…") }
                official.isEmpty() -> item(key = "official-none") { Hint("Nothing in the official catalog.") }
                else -> officialGroups(official).forEach { (category, inGroup) ->
                    item(key = "c-$category") { HubHeading(category.uppercase()) }
                    item(key = "g-$category") { HubRows(inGroup, state, actions) }
                }
            }
        }
    }
}

/** The official catalog by category, uncategorised ones last as "other". */
internal fun officialGroups(skills: List<HubSkill>): Map<String, List<HubSkill>> =
    skills
        .groupBy { it.category?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: "other" }
        .toSortedMap(compareBy<String> { it == "other" }.thenBy { it })

private val HubPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 32.dp)

@Composable
private fun HubError(message: String, onRetry: () -> Unit) =
    EmptyState(Lucide.CloudOff, "Couldn't load", message, error = true) {
        Button("Try again", onClick = onRetry, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
    }

@Composable
private fun HubHeading(label: String) {
    Text(
        label,
        style = Theme[typography][eyebrow],
        color = Theme[colors][textTertiary],
        modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 6.dp).semantics {
            heading()
            contentDescription = label.lowercase()
        },
    )
}

@Composable
private fun HubRows(skills: List<HubSkill>, state: SkillsHubUiState, actions: SkillsHubActions) {
    RowCard(skills, key = { it.installKey }) { skill ->
        val installed = skill.installKey in state.installed
        Column(
            Modifier.fillMaxWidth().clickable { actions.open(skill) }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(
                    skill.name,
                    style = Theme[typography][code].copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                    color = Theme[colors][text],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                skill.trustLevel.takeIf { it.isNotBlank() }?.let { Tag(it, accent = false) }
                if (installed) Tag("Installed", accent = true)
            }
            if (skill.description.isNotBlank()) {
                Text(
                    skill.description,
                    style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp, lineHeight = 17.5.sp),
                    color = Theme[colors][textTertiary],
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A skill's page in the hub: its install button, scan, SKILL.md and files. */
@Composable
private fun HubDetailPage(d: HubDetail, state: SkillsHubUiState, actions: SkillsHubActions, modifier: Modifier = Modifier) {
    val skill = d.skill
    val id = skill.installKey
    val installed = id in state.installed
    val blocked = d.scan?.policy == "block"
    Column(modifier) {
        SubpageHeader(
            skill.name,
            onBack = actions::closeDetail,
            subtitle = listOfNotNull(skill.trustLevel.takeIf { it.isNotBlank() }, skill.source.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null },
            actions = {
                when {
                    installed -> Button("Installed", onClick = {}, enabled = false, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
                    else -> Button("Install", onClick = { actions.install(skill) }, enabled = !blocked, loading = id in state.running, size = ButtonSize.Small)
                }
            },
        )
        // Each skill opens at its top.
        key(id) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                (d.preview?.description?.takeIf { it.isNotBlank() } ?: skill.description.takeIf { it.isNotBlank() })?.let {
                    Text(it, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
                }
                skill.repo?.let { Text(it, style = Theme[typography][code].copy(fontSize = 12.sp), color = Theme[colors][textTertiary]) }
                val tags = d.preview?.tags?.ifEmpty { null } ?: skill.tags
                if (tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { tags.forEach { Tag(it, accent = false) } }
                }
                if (blocked) Text("Blocked by security scan", style = Theme[typography][bodySmall], color = Theme[colors][danger])
                d.error?.let { Text(it, style = Theme[typography][bodySmall], color = Theme[colors][danger]) }
                d.scanError?.let { Text(it, style = Theme[typography][bodySmall], color = Theme[colors][danger]) }
                d.scan?.let { ScanFindings(it, expanded = blocked) }
                when {
                    d.preview != null -> {
                        MarkdownText(d.preview.body)
                        if (d.preview.files.isNotEmpty()) {
                            Text(d.preview.files.joinToString("\n"), style = Theme[typography][code].copy(fontSize = 12.sp), color = Theme[colors][textTertiary])
                        }
                    }
                    d.error != null -> Unit
                    else -> Text("Loading…", style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
                }
            }
        }
    }
}

/** The scan's verdict, with what it flagged. */
@Composable
private fun ScanFindings(scan: HubScan, expanded: Boolean) {
    var open by remember(scan) { mutableStateOf(expanded) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!open && scan.findings.isNotEmpty()) {
            Button("View scan", onClick = { open = true }, variant = ButtonVariant.Outline, size = ButtonSize.Small)
        } else {
            Text(
                listOf(scan.verdict, scan.summary).filter { it.isNotBlank() }.joinToString(": "),
                style = Theme[typography][bodySmall],
                color = Theme[colors][textSecondary],
            )
            scan.findings.forEach {
                FindingLine(it)
            }
        }
    }
}

/** A community skill the scan didn't clear outright: its findings, and the user's yes or no. */
@Composable
internal fun HubConfirmSheet(scan: HubScan?, actions: SkillsHubActions) {
    var kept by remember { mutableStateOf(scan) }
    if (scan != null) kept = scan
    BottomSheet(visible = scan != null, onDismiss = actions::cancelConfirm) {
        val s = kept ?: return@BottomSheet
        Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
            SheetHeader("${s.verdict.replaceFirstChar { it.uppercase() }} scan", s.name.ifBlank { null })
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(s.summary, s.policyReason).filter { it.isNotBlank() }.forEach {
                    Text(it, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
                }
                s.findings.forEach {
                    FindingLine(it)
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button("Install anyway", onClick = actions::confirmInstall, variant = ButtonVariant.Danger)
                    Button("Cancel", onClick = actions::cancelConfirm, variant = ButtonVariant.Secondary)
                }
            }
        }
    }
}

@Composable
private fun FindingLine(f: HubFinding) {
    Text(
        "${f.severity} · ${f.file}:${f.line} · ${f.description}",
        style = Theme[typography][code].copy(fontSize = 12.sp),
        color = Theme[colors][textTertiary],
    )
}
