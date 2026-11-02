package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.ui.PageFoot
import dev.hermeskotlin.ui.PageRowHeight

import dev.hermeskotlin.designsystem.components.handCursor
import dev.hermeskotlin.designsystem.components.verticalScrollWithBar
import dev.hermeskotlin.core.config.ConfigSchema
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.ui.CopyableOnDesktop
import dev.hermeskotlin.ui.arrowFocus
import dev.hermeskotlin.ui.isDesktop
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.clearText
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import kotlinx.serialization.json.JsonObject
import dev.hermeskotlin.ui.Shortcut
import dev.hermeskotlin.ui.ShortcutHandler

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import dev.hermeskotlin.designsystem.components.ScrollbarBox
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.AudioLines
import com.composables.icons.lucide.Box as BoxIcon
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.FileCode
import com.composables.icons.lucide.FolderCode
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.ListTree
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.PenLine
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Wrench
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.config.ConfigCatalog
import dev.hermeskotlin.core.config.ConfigControl
import dev.hermeskotlin.core.config.ConfigEntry
import dev.hermeskotlin.core.config.ConfigSection
import dev.hermeskotlin.core.config.NOT_SET
import dev.hermeskotlin.core.config.editText
import dev.hermeskotlin.core.config.parseConfigInput
import dev.hermeskotlin.core.config.summary
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.Switch
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.eyebrow
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.components.ListSkeleton
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboard
import com.composables.icons.lucide.Copy
import dev.hermeskotlin.designsystem.components.plainTextClipEntry
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull

/**
 * The sidebar's Config page, after Desktop's Settings: Hermes's own settings for the profile, by section,
 * searchable across every field the gateway knows, and the whole config.yaml for anything else. Each change
 * is saved at once.
 */
@Composable
internal fun ConfigPage(
    gateway: SavedGateway,
    profile: String?,
    onBack: () -> Unit,
    onSessionExpired: () -> Unit,
    title: String = "Config",
    /** A line under the title, e.g. whose settings these are. */
    note: String? = null,
    /** False while the sidebar holding it is closed, so Back is the chat's. */
    visible: Boolean = true,
    viewModel: ConfigViewModel = koinViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(gateway, profile) { viewModel.bind(gateway, profile) }
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
    // Back steps out of a section or the file before it leaves the page.
    PlatformBackHandler(enabled = visible && state.view != ConfigPlace.Overview) { viewModel.back() }
    ConfigView(state, viewModel.query, viewModel.yaml, viewModel, onBack = onBack, title = title, note = note)
}

/** What the Config page can ask for; [ConfigViewModel] does it all, previews nothing. */
interface ConfigActions {
    fun refresh()
    fun open(view: ConfigPlace)
    fun back(): Boolean
    fun set(entry: ConfigEntry, value: JsonElement)
    fun confirmChange()
    fun cancelChange()
    fun saveYaml()
    fun discardYaml()
    fun keepEditing()
    fun dismissMessage()
}

/** The Config page's layout, apart from its view model, so previews can draw it from sample data. */
@Composable
internal fun ConfigView(
    state: ConfigUiState,
    query: TextFieldState,
    yaml: TextFieldState,
    actions: ConfigActions,
    onBack: () -> Unit,
    title: String = "Config",
    note: String? = null,
) {
    // The setting whose picker or editor is open.
    var picking by remember { mutableStateOf<ConfigEntry?>(null) }
    var editing by remember { mutableStateOf<ConfigEntry?>(null) }
    val onEntry: (ConfigEntry) -> Unit = { entry ->
        when (entry.control) {
            ConfigControl.Toggle -> actions.set(entry, JsonPrimitive(!entry.isOn))
            is ConfigControl.Choice -> picking = entry
            else -> editing = entry
        }
    }

    // The desktop's two panes keep their pick here, so a trip to config.yaml comes back to it.
    val pane = remember {
        PaneState((state.view as? ConfigPlace.Section)?.section, state.view == ConfigPlace.AllSettings)
    }
    val entryBounds = remember { mutableMapOf<String, Rect>() }
    CompositionLocalProvider(LocalEntryBounds provides entryBounds) {
    Column(Modifier.fillMaxSize()) {
        val schema = state.schema
        val values = state.values
        when (val view = state.view) {
            ConfigPlace.Yaml -> YamlEditor(state.yaml, yaml, onBack = { actions.back() }, onSave = actions::saveYaml)
            else -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // A desktop window wide enough lays the sections beside their settings, as desktop settings windows
                // do, instead of opening each section as a page of its own.
                if (isDesktop && maxWidth >= TWO_PANE_WIDTH && schema != null && values != null) {
                    TwoPaneConfig(state, pane, schema, values, query, actions, onEntry, title = title, note = note, onBack = onBack)
                } else Column(Modifier.fillMaxSize()) {
                when (view) {
                    is ConfigPlace.Section -> SubpageHeader(view.section.label, onBack = { actions.back() }, subtitle = sectionNote(view.section))
                    ConfigPlace.AllSettings -> SubpageHeader("All settings", onBack = { actions.back() }, subtitle = "Every setting the gateway knows, by its own grouping.")
                    else -> SubpageHeader(title, onBack = onBack, subtitle = note ?: "Hermes's own settings, saved to its config.yaml. Most apply from the next chat.") {
                        IconButton(Lucide.FileCode, contentDescription = "Edit config.yaml", onClick = { actions.open(ConfigPlace.Yaml) })
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        (schema == null || values == null) && state.error != null -> EmptyState(Lucide.CloudOff, "Couldn't load", state.error, error = true) {
                            Button("Try again", onClick = actions::refresh, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
                        }
                        schema == null || values == null -> ListSkeleton()
                        view is ConfigPlace.Section -> SectionSettings(view.section, schema, values, state.saving, onEntry)
                        view == ConfigPlace.AllSettings -> Categories(schema) { actions.open(ConfigPlace.Section(it)) }
                        else -> Overview(state, query, actions, onEntry)
                    }
                }
                }
            }
        }
        state.message?.let { MessageBanner(it, onDismiss = actions::dismissMessage, modifier = Modifier.padding(bottom = PageFoot)) }
    }

    ChoiceSheet(picking, onDismiss = { picking = null }, onPick = { entry, value ->
        picking = null
        actions.set(entry, (entry.control as? ConfigControl.Choice)?.valueOf(value) ?: JsonPrimitive(value))
    }, onOther = { entry ->
        picking = null
        editing = entry
    })
    }
    EditDialog(editing, onDismiss = { editing = null }, onSave = { entry, value ->
        editing = null
        actions.set(entry, value)
    })
    Dialog(
        visible = state.confirmDiscard,
        onDismissRequest = actions::keepEditing,
        title = "Discard your changes?",
        message = "config.yaml stays as it was saved.",
        actions = {
            Button("Keep editing", onClick = actions::keepEditing, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Discard", onClick = actions::discardYaml, variant = ButtonVariant.Danger, size = ButtonSize.Small)
        },
    )
    Dialog(
        visible = state.confirm != null,
        onDismissRequest = actions::cancelChange,
        title = "Turn off every toolset?",
        message = "The agent would lose memory, the terminal, web search, subagents and most of its tools until some are turned back on.",
        actions = {
            Button("Cancel", onClick = actions::cancelChange, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Turn off", onClick = actions::confirmChange, variant = ButtonVariant.Danger, size = ButtonSize.Small)
        },
    )
}

/** The sections, a search over everything, then All settings and the file. */
@Composable
private fun Overview(
    state: ConfigUiState,
    query: TextFieldState,
    actions: ConfigActions,
    onEntry: (ConfigEntry) -> Unit,
    /** False where the search field is the desktop's left pane's, not the list's own. */
    withSearch: Boolean = true,
) {
    val schema = state.schema ?: return
    val values = state.values ?: return
    val typed = query.text.toString()
    val results = remember(typed, schema, values) { ConfigCatalog.search(typed, schema, values) }
    val sections = remember(schema, values) { ConfigCatalog.sections.map { it to ConfigCatalog.entries(it, schema, values).size }.filter { it.second > 0 } }
    PageList {
        if (withSearch) item(key = "search") {
            TextField(
                state = query,
                placeholder = "Search ${schema.fields.size} settings",
                leadingIcon = Lucide.Search,
                clearable = true,
                autofocus = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        if (typed.isNotBlank()) {
            if (results.isEmpty()) {
                item(key = "no-results") { ListNotice("No settings match “${typed.trim()}”.") }
            } else {
                val shown = results.take(SEARCH_LIMIT)
                item(key = "results-count") {
                    Eyebrow(
                        if (results.size > shown.size) "First ${shown.size} of ${results.size} settings"
                        else "${results.size} ${if (results.size == 1) "setting" else "settings"}",
                    )
                }
                item(key = "results") { EntryCard(shown, state.saving, showKeys = true, onEntry = onEntry) }
                if (results.size > shown.size) item(key = "narrow") { ListNotice("Type more to narrow it down.") }
            }
        } else {
            item(key = "sections") {
                NavCard(sections) { (section, count) ->
                    NavRow(sectionIcon(section.id), section.label, "$count ${if (count == 1) "setting" else "settings"}") { actions.open(ConfigPlace.Section(section)) }
                }
            }
            item(key = "more") {
                NavCard(listOf("all", "yaml"), modifier = Modifier.padding(top = 14.dp)) { which ->
                    if (which == "all") {
                        NavRow(Lucide.ListTree, "All settings", "Every field, by the gateway's own grouping") { actions.open(ConfigPlace.AllSettings) }
                    } else {
                        NavRow(Lucide.FileCode, "Edit config.yaml", "The whole file, for anything not listed") { actions.open(ConfigPlace.Yaml) }
                    }
                }
            }
        }
    }
}

/** A section's settings, a category's in a card per parent. */
@Composable
private fun SectionSettings(section: ConfigSection, schema: ConfigSchema, values: JsonObject, saving: Set<String>, onEntry: (ConfigEntry) -> Unit) {
    val entries = remember(section, schema, values) { ConfigCatalog.entries(section, schema, values) }
    PageList {
        when {
            entries.isEmpty() -> item(key = "none") { ListNotice("This gateway has none of these settings.") }
            section.category == null -> item(key = "fields") { EntryCard(entries, saving, showKeys = false, onEntry = onEntry) }
            // A category can hold a hundred fields: one card per parent, each its own list item.
            else -> ConfigCatalog.grouped(entries).forEachIndexed { i, (parent, group) ->
                if (parent.isNotEmpty()) item(key = "h-$parent") { Eyebrow(ConfigCatalog.groupLabel(parent), top = if (i == 0) 2.dp else 14.dp) }
                item(key = "g-$parent") { EntryCard(group, saving, showKeys = true, onEntry = onEntry) }
            }
        }
    }
}

/** Every category the gateway groups its fields by, with how many each holds. */
@Composable
private fun Categories(schema: ConfigSchema, onOpen: (ConfigSection) -> Unit) {
    val categories = remember(schema) { ConfigCatalog.categories(schema).map { it to schema.fields.values.count { f -> f.category == it.category } } }
    PageList {
        item(key = "categories") {
            NavCard(categories) { (section, count) ->
                NavRow(icon = null, title = section.label, detail = "$count ${if (count == 1) "setting" else "settings"}") { onOpen(section) }
            }
        }
    }
}

/**
 * The desktop's Config in a wide window: the search and the sections down the left, the one picked (or the search's
 * matches) on the right, as a desktop settings window lays itself out. The pick is the pane's own, so Escape leaves
 * the page rather than stepping back through sections.
 */
@Composable
private fun TwoPaneConfig(
    state: ConfigUiState,
    pane: PaneState,
    schema: ConfigSchema,
    values: JsonObject,
    query: TextFieldState,
    actions: ConfigActions,
    onEntry: (ConfigEntry) -> Unit,
    title: String,
    note: String?,
    onBack: () -> Unit,
) {
    val sections = remember(schema, values) { ConfigCatalog.sections.map { it to ConfigCatalog.entries(it, schema, values).size }.filter { it.second > 0 } }
    var picked by pane::picked
    var allSettings by pane::allSettings
    var category by pane::category
    // A section opened as a page before the window grew is the pane's pick now: the page steps back to the
    // overview, so its Back handler doesn't take an Escape that should leave the page.
    when (val view = state.view) {
        is ConfigPlace.Section -> if (view.section.category != null) {
            if (!allSettings) allSettings = true
            if (category != view.section) category = view.section
        } else if (picked != view.section || allSettings) {
            picked = view.section
            allSettings = false
        }
        ConfigPlace.AllSettings -> if (!allSettings || category != null) {
            allSettings = true
            category = null
        }
        else -> Unit
    }
    if (picked == null) picked = sections.firstOrNull()?.first
    LaunchedEffect(state.view) {
        if (state.view is ConfigPlace.Section || state.view == ConfigPlace.AllSettings) actions.back()
    }
    val typed = query.text.toString()
    Column(Modifier.fillMaxSize()) {
        SubpageHeader(title, onBack = onBack, subtitle = note ?: "Hermes's own settings, saved to its config.yaml. Most apply from the next chat.") {
            IconButton(Lucide.FileCode, contentDescription = "Edit config.yaml", onClick = { actions.open(ConfigPlace.Yaml) })
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(PANE_LIST_WIDTH).fillMaxHeight().padding(start = 14.dp, top = 4.dp)) {
                TextField(
                    state = query,
                    placeholder = "Search ${schema.fields.size} settings",
                    leadingIcon = Lucide.Search,
                    clearable = true,
                    autofocus = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                Column(Modifier.weight(1f).verticalScrollWithBar().arrowFocus()) {
                    sections.forEach { (section, count) ->
                        PaneRow(sectionIcon(section.id), section.label, count, selected = typed.isBlank() && !allSettings && picked == section) {
                            query.clearText()
                            allSettings = false
                            picked = section
                        }
                    }
                    Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(Theme[colors][stroke]))
                    PaneRow(Lucide.ListTree, "All settings", null, selected = typed.isBlank() && allSettings) {
                        query.clearText()
                        allSettings = true
                        category = null
                    }
                    PaneRow(Lucide.FileCode, "config.yaml", null, selected = false) { actions.open(ConfigPlace.Yaml) }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val section = picked
                val opened = category
                when {
                    typed.isNotBlank() -> Overview(state, query, actions, onEntry, withSearch = false)
                    allSettings && opened != null -> SectionSettings(opened, schema, values, state.saving, onEntry)
                    allSettings -> Categories(schema) { category = it }
                    section != null -> SectionSettings(section, schema, values, state.saving, onEntry)
                }
            }
        }
    }
}

/** A section in the desktop's left pane: its icon, its name and how many settings it holds. */
@Composable
private fun PaneRow(icon: ImageVector, label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusSmall])
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .clip(shape)
            .background(if (selected) Theme[colors][surface2] else Color.Transparent, shape)
            .handCursor()
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(16.dp))
        Text(
            label,
            style = Theme[typography][bodySmall].copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
            color = Theme[colors][text],
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) Text(count.toString(), style = Theme[typography][caption], color = Theme[colors][textTertiary])
    }
}

/** What the desktop's two panes show: a section, or All settings and the category opened from it. */
@Stable
internal class PaneState(section: ConfigSection?, all: Boolean) {
    var picked by mutableStateOf(section)
    var allSettings by mutableStateOf(all)
    // A category opened from All settings; picking All settings again goes back to the list of them.
    var category by mutableStateOf<ConfigSection?>(null)
}

/** How wide a Config page must be to lay its sections beside their settings. */
private val TWO_PANE_WIDTH = 640.dp

/** The desktop's list of sections, beside the settings. */
private val PANE_LIST_WIDTH = 220.dp

/** Results shown at once; a word like "model" matches a few hundred. */
private const val SEARCH_LIMIT = 60

private fun sectionNote(section: ConfigSection): String? = when (section.id) {
    "voice" -> "Only the picked providers' own settings show."
    "model" -> "The default for new chats; a chat's model picker can still change its own."
    else -> null
}

private fun sectionIcon(id: String): ImageVector = when (id) {
    "model" -> Lucide.BoxIcon
    "chat" -> Lucide.MessageCircle
    "workspace" -> Lucide.FolderCode
    "safety" -> Lucide.ShieldCheck
    "browser" -> Lucide.Globe
    "memory" -> Lucide.Brain
    "voice" -> Lucide.AudioLines
    else -> Lucide.Wrench
}

private val ConfigEntry.isOn: Boolean get() = (value as? JsonPrimitive)?.booleanOrNull == true

@Composable
private fun PageList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val list = rememberLazyListState()
    ScrollbarBox(list, Modifier.fillMaxSize().imePadding(), bottomInset = PageFoot) {
        // 4dp on top leaves room for a focused search field's ring, which the list would clip.
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = PageFoot), content = content)
    }
}

@Composable
private fun Eyebrow(text: String, top: Dp = 2.dp) {
    Text(
        text.uppercase(),
        style = Theme[typography][eyebrow],
        color = Theme[colors][textTertiary],
        modifier = Modifier.padding(start = 6.dp, top = top, bottom = 8.dp).semantics {
            heading()
            contentDescription = text
        },
    )
}

/** Rows on one ringed surface card, a hairline between each. */
@Composable
private fun <T> NavCard(rows: List<T>, modifier: Modifier = Modifier, row: @Composable (T) -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(modifier.fillMaxWidth().clip(shape).background(Theme[colors][surface]).border(1.dp, Theme[colors][stroke], shape)) {
        rows.forEachIndexed { i, item ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Theme[colors][stroke]))
            row(item)
        }
    }
}

@Composable
private fun NavRow(icon: ImageVector?, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = PageRowHeight).handCursor().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(32.dp).background(Theme[colors][surface2], RoundedCornerShape(Theme[radii][radiusSmall])), contentAlignment = Alignment.Center) {
                UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(16.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = Theme[typography][body], color = Theme[colors][text])
            Text(detail, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary], maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        UnstyledIcon(Lucide.ChevronRight, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun EntryCard(entries: List<ConfigEntry>, saving: Set<String>, showKeys: Boolean, onEntry: (ConfigEntry) -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(Modifier.fillMaxWidth().clip(shape).background(Theme[colors][surface]).border(1.dp, Theme[colors][stroke], shape)) {
        entries.forEachIndexed { i, entry ->
            key(entry.key) {
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Theme[colors][stroke]))
                EntryRow(entry, saving = entry.key in saving, showKey = showKeys, onClick = { onEntry(entry) })
            }
        }
    }
}

/**
 * One setting: its name, what it's for, and its value under them, or a switch at the end for one that's
 * on or off. [showKey] adds the dotted key, for settings found outside their section.
 */
@Composable
private fun EntryRow(entry: ConfigEntry, saving: Boolean, showKey: Boolean, onClick: () -> Unit) {
    val toggle = entry.control == ConfigControl.Toggle
    // On the desktop, where the row is, for its picker to open by it; and a right-click menu for its key and value.
    val bounds = LocalEntryBounds.current
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val menu = {
        val value = entry.value?.takeUnless { it is JsonNull }?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
        listOfNotNull(
            RowMenuItem(if (toggle) (if (entry.isOn) "Turn off" else "Turn on") else "Change…", Lucide.PenLine, run = onClick),
            RowMenuItem("Copy key", Lucide.Copy) { scope.launch { clipboard.setClipEntry(plainTextClipEntry(entry.key)) } },
            value?.let { RowMenuItem("Copy value", Lucide.Copy) { scope.launch { clipboard.setClipEntry(plainTextClipEntry(it)) } } },
        )
    }
    RowContextMenu(menuOpen, onDismiss = { menuOpen = false }, items = menu)
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (isDesktop) {
                    Modifier
                        .onGloballyPositioned { bounds?.set(entry.key, it.boundsInWindow()) }
                        .onSecondaryPress { menuOpen = true }
                } else Modifier,
            )
            .heightIn(min = PageRowHeight)
            .then(
                if (toggle) Modifier.handCursor().toggleable(value = entry.isOn, role = Role.Switch, onValueChange = { onClick() })
                else Modifier.handCursor().clickable(role = Role.Button, onClickLabel = "Change", onClick = onClick),
            )
            .padding(horizontal = 14.dp, vertical = if (isDesktop) 9.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.label, style = Theme[typography][body], color = Theme[colors][text])
            if (showKey) {
                Text(entry.key, style = Theme[typography][code].copy(fontSize = 11.5.sp), color = Theme[colors][textTertiary], maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            entry.description?.let {
                Text(it, style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp, lineHeight = 17.5.sp), color = Theme[colors][textTertiary], maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (!toggle) {
                val summary = entry.summary()
                Text(
                    summary,
                    style = Theme[typography][code].copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                    color = if (summary == NOT_SET) Theme[colors][textTertiary] else Theme[colors][accentText],
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        when {
            saving -> Spinner(Modifier.size(16.dp))
            toggle -> Switch(entry.isOn)
            else -> UnstyledIcon(Lucide.PenLine, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(15.dp))
        }
    }
}

/** Where each setting's row sits in the window, by key, so a desktop's picker opens by the row it's for. */
internal val LocalEntryBounds = staticCompositionLocalOf<MutableMap<String, Rect>?> { null }

/**
 * A choice's options in a sheet, the current one ticked; a long list gets a search, a free one an "Other…" row.
 * On the desktop it opens as a popover by the setting's row.
 */
@Composable
internal fun ChoiceSheet(entry: ConfigEntry?, onDismiss: () -> Unit, onPick: (ConfigEntry, String) -> Unit, onOther: (ConfigEntry) -> Unit) {
    // Kept while the sheet slides away, so it doesn't empty mid-animation.
    var shown by remember { mutableStateOf(entry) }
    if (entry != null) shown = entry
    val e = shown
    val control = e?.control as? ConfigControl.Choice
    val search = rememberTextFieldState()
    LaunchedEffect(entry) { if (entry != null) search.edit { replace(0, length, "") } }
    val bounds = LocalEntryBounds.current
    // Taken as it opens, and kept while it closes, so it fades out where it was.
    val anchor = remember(entry != null, e?.key) { e?.key?.let { bounds?.get(it) } }
    BottomSheet(visible = entry != null, onDismiss = onDismiss, anchor = anchor) {
        if (e == null || control == null) return@BottomSheet
        // Null is "not set", like "".
        val current = (e.value as? JsonPrimitive)?.contentOrNull.orEmpty()
        SheetHeader(e.label, e.description)
        if (control.searchable) {
            TextField(
                state = search,
                placeholder = "Search ${control.options.size}",
                leadingIcon = Lucide.Search,
                clearable = true,
                autofocus = true,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
        val typed = search.text.toString().trim()
        val options = remember(control, typed) { control.options.filter { typed.isEmpty() || it.contains(typed, ignoreCase = true) } }
        ScrollbarBox { listState ->
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), state = listState) {
                items(options, key = { "o-$it" }) { option ->
                    OptionRow(if (option.isEmpty()) NOT_SET else option, selected = option == current, mono = option.isNotEmpty()) { onPick(e, option) }
                }
                if (control.other) {
                    item(key = "other") { OptionRow("Other…", selected = false, mono = false) { onOther(e) } }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(option: String, selected: Boolean, mono: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (isDesktop) 36.dp else 48.dp)
            .handCursor()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = if (isDesktop) 7.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            option,
            style = if (mono) Theme[typography][code].copy(fontSize = 13.5.sp) else Theme[typography][body],
            color = if (selected) Theme[colors][accentText] else Theme[colors][text],
            modifier = Modifier.weight(1f),
        )
        if (selected) UnstyledIcon(Lucide.Check, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(16.dp))
    }
}

/** A text field for a setting typed in: one line for words and numbers, several for lists and JSON. */
@Composable
private fun EditDialog(entry: ConfigEntry?, onDismiss: () -> Unit, onSave: (ConfigEntry, JsonElement) -> Unit) {
    var shown by remember { mutableStateOf(entry) }
    if (entry != null) shown = entry
    val e = shown ?: return
    val input = rememberTextFieldState()
    var error by remember { mutableStateOf<String?>(null) }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(entry) {
        if (entry != null) {
            // On the desktop the value comes up selected, so typing replaces it, as a rename box's name does.
            input.resetTo(entry.editText(), inputMode)
            error = null
        }
    }
    val multiline = e.control == ConfigControl.Lines || e.control == ConfigControl.Fallbacks || e.control == ConfigControl.Json ||
        (e.control == ConfigControl.Text && e.editText().length > 60)
    val submit = {
        parseConfigInput(e, input.text.toString()).fold(
            onSuccess = { onSave(e, it) },
            onFailure = { error = it.message },
        )
    }
    Dialog(
        visible = entry != null,
        onDismissRequest = onDismiss,
        title = e.label,
        message = when (e.control) {
            ConfigControl.Lines -> listOfNotNull(e.description, "One per line.").joinToString(" ")
            else -> e.description
        },
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Save", onClick = { submit() }, size = ButtonSize.Small)
        },
    ) {
        TextField(
            state = input,
            placeholder = NOT_SET,
            singleLine = !multiline,
            maxLines = 10,
            error = error,
            autofocus = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (e.control == ConfigControl.Number) KeyboardType.Decimal else KeyboardType.Text,
                imeAction = if (multiline) ImeAction.Default else ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            onKeyboardAction = if (multiline) null else { _ -> submit() },
        )
    }
}

/**
 * The whole config.yaml in a mono field. Save replaces the file after a confirm; the gateway refuses YAML
 * that isn't a mapping, and its reason shows under the field.
 */
@Composable
private fun ColumnScope.YamlEditor(state: YamlState?, file: TextFieldState, onBack: () -> Unit, onSave: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val changed = state?.loaded != null && file.text.toString() != state.loaded
    // Ctrl+S asks as the Save button does: the whole file is replaced.
    ShortcutHandler(enabled = changed && state?.saving != true && !confirming) { (it == Shortcut.Save).also { save -> if (save) confirming = true } }
    SubpageHeader("config.yaml", onBack = onBack, subtitle = state?.path?.ifEmpty { null } ?: "The profile's whole config file.") {
        Button(
            "Save",
            onClick = { confirming = true },
            size = ButtonSize.Small,
            enabled = changed && state?.saving != true,
            loading = state?.saving == true,
        )
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            state == null || state.loading -> ListSkeleton()
            state.loaded == null && state.error != null -> EmptyState(Lucide.CloudOff, "Couldn't load", state.error, error = true)
            else -> {
                val scroll = rememberScrollState()
                ScrollbarBox(scroll, Modifier.fillMaxSize().imePadding(), bottomInset = PageFoot) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 14.dp, end = 14.dp, bottom = PageFoot)) {
                        state.error?.let { CopyableOnDesktop { Text(it, style = Theme[typography][bodySmall], color = Theme[colors][danger], modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) } }
                        YamlField(file)
                    }
                }
            }
        }
    }
    Dialog(
        visible = confirming,
        onDismissRequest = { confirming = false },
        title = "Replace config.yaml?",
        message = "The file becomes exactly this text. Hermes reads it again for new chats; some settings need a gateway restart.",
        actions = {
            Button("Cancel", onClick = { confirming = false }, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Replace", onClick = { confirming = false; onSave() }, size = ButtonSize.Small)
        },
    )
}

@Composable
private fun YamlField(file: TextFieldState) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    androidx.compose.foundation.text.BasicTextField(
        state = file,
        textStyle = Theme[typography][code].copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = Theme[colors][text]),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Theme[colors][accentText]),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp)
            .clip(shape)
            .background(Theme[colors][surface])
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(12.dp)
            // YAML takes no tabs: on a desktop Tab indents by two spaces and Shift+Tab takes two off the lines, as
            // a code editor's do, rather than a tab the gateway would refuse the file for. A Shift+Tab with nothing
            // to take off moves the focus back out, so the keyboard isn't held in the field.
            .then(
                if (isDesktop) {
                    Modifier.onPreviewKeyEvent { event ->
                        val plainTab = event.type == KeyEventType.KeyDown && event.key == Key.Tab &&
                            !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
                        if (!plainTab) return@onPreviewKeyEvent false
                        var changed = false
                        file.edit { changed = yamlIndent(this, outdent = event.isShiftPressed) }
                        changed
                    }
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = "config.yaml" },
    )
}

/**
 * Tab's two spaces at the caret, or before each selected line; Shift+Tab's up to two taken off the start of each.
 * Says whether the text changed: a Shift+Tab with nothing to take off leaves the key to move the focus on.
 */
internal fun yamlIndent(text: TextFieldBuffer, outdent: Boolean): Boolean {
    val from = text.selection.min
    val to = text.selection.max
    if (!outdent && from == to) {
        text.replace(from, to, "  ")
        text.selection = TextRange(from + 2)
        return true
    }
    val all = text.toString()
    // The lines the selection touches; a selection ending at a line's very start leaves that line out.
    val last = if (to > from && all.getOrNull(to - 1) == '\n') to - 1 else to
    val starts = buildList {
        add(if (from == 0) 0 else all.lastIndexOf('\n', from - 1) + 1)
        var at = all.indexOf('\n', from)
        while (at in 0 until last) {
            add(at + 1)
            at = all.indexOf('\n', at + 1)
        }
    }
    var newFrom = from
    var newTo = to
    var changed = false
    // From the last line up, so the earlier lines' offsets hold.
    for (start in starts.asReversed()) {
        val delta = if (outdent) {
            val spaces = all.drop(start).takeWhile { it == ' ' }.length.coerceAtMost(2)
            if (spaces == 0) continue
            text.replace(start, start + spaces, "")
            -spaces
        } else {
            text.replace(start, start, "  ")
            2
        }
        changed = true
        if (start < from) newFrom = (newFrom + delta).coerceAtLeast(start)
        if (start < to || from == to) newTo = (newTo + delta).coerceAtLeast(start)
    }
    text.selection = TextRange(newFrom, newTo)
    return changed
}
