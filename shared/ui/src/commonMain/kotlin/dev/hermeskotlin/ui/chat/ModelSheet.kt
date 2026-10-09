package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Star
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.models.ModelCatalog
import dev.hermeskotlin.core.models.ModelOption
import dev.hermeskotlin.core.models.PickerModel
import dev.hermeskotlin.core.models.PickerScope
import dev.hermeskotlin.core.models.ReasoningEffort
import dev.hermeskotlin.core.models.displayModelName
import dev.hermeskotlin.core.models.displayProviderName
import dev.hermeskotlin.core.models.offersAny
import dev.hermeskotlin.core.models.pickerModels
import dev.hermeskotlin.core.models.pickerScope
import dev.hermeskotlin.core.models.pickerSections
import dev.hermeskotlin.core.models.starKey
import dev.hermeskotlin.core.models.toggleStar
import dev.hermeskotlin.core.models.withRecent
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.designsystem.StarFilled
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Chip
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.Switch
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.heading
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.LocalAppSettings
import org.koin.compose.koinInject

/** What the composer shows and the sheet marks: the chat's own pick, else the profile default. */
internal data class ModelSelection(val model: String?, val provider: String?, val option: ModelOption?) {
    /** [option] is the model in use: the same id, from the same provider when that's known. */
    fun isCurrent(option: ModelOption): Boolean = option.id == model && (provider == null || option.provider == provider)

    companion object {
        fun of(state: ChatState, catalog: ModelCatalog?): ModelSelection {
            val model = state.model ?: catalog?.currentModel
            val provider = if (state.model != null) state.provider else catalog?.currentProvider
            return ModelSelection(model, provider, catalog?.find(provider, model))
        }
    }
}

/** The effort a chat runs at, for labels: its own level, else what the gateway defaults to. */
internal fun ChatState.effort(option: ModelOption?): ReasoningEffort? {
    if (option != null && !option.reasoning) return null
    return ReasoningEffort.fromWire(reasoningEffort) ?: ReasoningEffort.Default.takeIf { option != null }
}

/**
 * Model, thinking level and fast mode for the open chat. With [modelOnly] it picks just a model, e.g. a
 * bot's own, which carries no thinking level or fast mode; [note] says what a pick changes.
 */
@Composable
internal fun ModelSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    state: ChatState,
    picker: ModelPickerState,
    onRefresh: () -> Unit,
    onSelectModel: (ModelOption) -> Unit,
    onSelectEffort: (ReasoningEffort) -> Unit,
    onFast: (Boolean) -> Unit,
    modelOnly: Boolean = false,
    note: String? = null,
    /** Saves stars and recent picks; previews pass their own, since the default writes the device's settings. */
    onSettings: ((AppSettings) -> AppSettings) -> Unit = settingsUpdater(),
    /** What the search box and the scope start with, for previews. */
    initialQuery: String = "",
    initialScope: PickerScope? = null,
) {
    val query = rememberTextFieldState(initialQuery)
    var scope by remember { mutableStateOf(initialScope) }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        onRefresh()
        // Each time it opens again it starts over, with no leftover search and back on your models. Done on open,
        // not on close, so the list doesn't change under the sheet while it slides away.
        if (opened) {
            query.clearText()
            scope = null
        }
        opened = true
    }
    val selection = ModelSelection.of(state, picker.catalog)
    // A pick joins Recent once the chat (or bot) runs it: not when a confirm is cancelled or the switch fails.
    var picked by remember { mutableStateOf<ModelOption?>(null) }
    LaunchedEffect(selection, picked) {
        val model = picked ?: return@LaunchedEffect
        if (selection.isCurrent(model)) {
            onSettings { it.copy(recentModels = it.recentModels.withRecent(model)) }
            picked = null
        }
    }

    BottomSheet(visible = visible, onDismiss = onDismiss) {
        Text(
            "Model",
            style = Theme[typography][heading],
            color = Theme[colors][textColor],
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = if (note != null) 4.dp else 12.dp),
        )
        note?.let {
            Text(
                it,
                style = Theme[typography][bodySmall],
                color = Theme[colors][textSecondary],
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
        }
        val saveLine = if (picker.saving) "Saving…" else picker.saveError
        saveLine?.let {
            Text(
                it,
                style = Theme[typography][bodySmall],
                color = if (picker.saving) Theme[colors][textSecondary] else Theme[colors][danger],
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
        }
        val option = selection.option
        val efforts = if (modelOnly) emptyList() else ReasoningEffort.choicesFor(option)
        if (efforts.isNotEmpty()) {
            SectionLabel("Thinking")
            val current = state.effort(option)
            val chips = rememberLazyListState()
            // The chips don't all fit on a phone: bring the chat's level into view, with its neighbour peeking.
            LaunchedEffect(visible, current, efforts) {
                if (visible) chips.scrollToItem((efforts.indexOf(current) - 1).coerceAtLeast(0))
            }
            LazyRow(
                state = chips,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(efforts, key = { it.wire }) { effort ->
                    Chip(effort.label, selected = effort == current, onClick = { onSelectEffort(effort) })
                }
            }
        }
        if (option?.fast == true && !modelOnly) {
            val fast = state.fast == true
            Row(
                Modifier.fillMaxWidth().clickable { onFast(!fast) }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Fast mode", style = Theme[typography][body], color = Theme[colors][textColor])
                    Text("Priority processing. Costs more.", style = Theme[typography][caption], color = Theme[colors][textTertiary])
                }
                Switch(fast)
            }
        } else if (efforts.isNotEmpty()) {
            Box(Modifier.height(12.dp))
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp).background(Theme[colors][stroke]))

        val catalog = picker.catalog
        when {
            catalog == null && picker.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner() }
            catalog == null -> Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                UnstyledIcon(Lucide.CloudOff, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(24.dp))
                Text(picker.error ?: "Couldn't load the models.", style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
                Button("Try again", onClick = onRefresh, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
            }
            else -> ModelList(
                catalog = catalog,
                selection = selection,
                query = query,
                chosen = scope,
                onChoose = { scope = it },
                onPick = { model ->
                    picked = model
                    onSelectModel(model)
                },
                onSettings = onSettings,
            )
        }
    }
}

/**
 * The models to pick from. It opens on yours (starred and recent) once there are any, with chips to see all of
 * them or one provider's; a search looks through every model in the chosen provider, or all of them.
 */
@Composable
private fun ModelList(
    catalog: ModelCatalog,
    selection: ModelSelection,
    query: TextFieldState,
    /** The scope chosen with the chips, null for the default. */
    chosen: PickerScope?,
    onChoose: (PickerScope) -> Unit,
    onPick: (ModelOption) -> Unit,
    onSettings: ((AppSettings) -> AppSettings) -> Unit,
) {
    val settings = LocalAppSettings.current
    val starred = settings.starredModels
    val recent = settings.recentModels
    val hasYours = remember(catalog, starred, recent) { catalog.offersAny(starred + recent) }
    val needle = query.text.toString().trim()
    val scope = pickerScope(chosen, hasYours, searching = needle.isNotEmpty())
    val current = selection.option?.starKey
    val sections = remember(catalog, needle, scope, starred, recent, current) {
        catalog.pickerSections(needle, scope, starred, recent, current)
    }
    val providerNames = remember(catalog) { catalog.providers.associate { it.slug to displayProviderName(it.slug, it.name) } }
    val allCount = remember(catalog) { catalog.pickerModels().size }

    if (catalog.providers.sumOf { it.models.size } > SEARCH_THRESHOLD) {
        TextField(
            state = query,
            placeholder = "Search models or providers",
            leadingIcon = Lucide.Search,
            clearable = true,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
        )
    }
    val scopes = buildList {
        if (hasYours) add(PickerScope.Yours to "Your models")
        add(PickerScope.All to "All")
        if (catalog.providers.size > 1) catalog.providers.forEach { add(PickerScope.Provider(it.slug) to providerNames.getValue(it.slug)) }
    }
    if (scopes.size > 1) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(scopes) { (option, title) ->
                Chip(
                    title,
                    selected = option == scope,
                    onClick = {
                        // Your models aren't searched, so going back to them leaves the search.
                        if (option == PickerScope.Yours) query.clearText()
                        onChoose(option)
                    },
                )
            }
        }
    }

    val list = rememberLazyListState()
    // A new search or scope starts at the top, where its best matches are.
    LaunchedEffect(needle, scope) { list.scrollToItem(0) }
    val repeatedNames = remember(sections) { sections.flatMap { it.models }.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).padding(top = 4.dp), state = list) {
        sections.forEachIndexed { index, section ->
            section.title?.let { title -> item(key = "h$index") { SectionLabel(title) } }
            items(section.models, key = { "$index-${it.variants.first().starKey}" }) { row ->
                val pick = row.preferred(selection.provider, selection.model, starred, recent)
                ModelRow(
                    row = row,
                    selection = selection,
                    starred = row.isStarred(starred),
                    // Which provider a model comes from matters once there's more than one, unless one is chosen.
                    providerNames = providerNames.takeIf { catalog.providers.size > 1 && scope !is PickerScope.Provider },
                    // Two rows with the same name need their ids to tell them apart.
                    showId = row.name in repeatedNames,
                    onClick = { onPick(pick) },
                    onPickVariant = onPick,
                    onToggleStar = {
                        // Stay where you are: a first star shouldn't swap the list you're browsing for yours.
                        if (chosen == null) onChoose(scope)
                        onSettings { it.copy(starredModels = it.starredModels.toggleStar(row, pick)) }
                    },
                )
            }
        }
        if (scope == PickerScope.Yours) {
            item(key = "all") { BrowseAllRow(allCount, onClick = { onChoose(PickerScope.All) }) }
        }
        if (sections.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (needle.isEmpty()) "The gateway offers no models." else "No models match \"$needle\".",
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textTertiary],
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }
}

/** Writes the device's settings. */
@Composable
private fun settingsUpdater(): ((AppSettings) -> AppSettings) -> Unit {
    val settings = koinInject<SettingsStore>()
    return remember(settings) { settings::update }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = Theme[typography][label],
        color = Theme[colors][textTertiary],
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
    )
}

/**
 * One model. A tap picks it from [PickerModel.preferred]'s provider; when several providers offer it, a pill per
 * provider (with its price) picks that one. The star, or a long press, stars it.
 */
@Composable
private fun ModelRow(
    row: PickerModel,
    selection: ModelSelection,
    starred: Boolean,
    providerNames: Map<String, String>?,
    showId: Boolean,
    onClick: () -> Unit,
    onPickVariant: (ModelOption) -> Unit,
    onToggleStar: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val selected = row.variants.any(selection::isCurrent)
    val single = row.variants.singleOrNull()
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleStar()
                },
                onLongClickLabel = if (starred) "Unstar" else "Star",
            )
            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                row.name,
                style = Theme[typography][body],
                color = if (selected) Theme[colors][accentText] else Theme[colors][textColor],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                single?.provider?.let { providerNames?.get(it) },
                row.variants.first().id.takeIf { showId && displayModelName(it) != it },
                single?.price,
            ).joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(details, style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (single == null) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.variants.forEach { variant ->
                        ProviderPill(
                            text = listOfNotNull(providerNames?.get(variant.provider) ?: displayProviderName(variant.provider), variant.price).joinToString(" · "),
                            selected = selection.isCurrent(variant),
                            onClick = { onPickVariant(variant) },
                        )
                    }
                }
            }
        }
        if (selected) UnstyledIcon(Lucide.Check, contentDescription = "Current", tint = Theme[colors][accentText], modifier = Modifier.size(18.dp))
        IconButton(
            icon = if (starred) StarFilled else Lucide.Star,
            contentDescription = if (starred) "Unstar ${row.name}" else "Star ${row.name}",
            onClick = onToggleStar,
            tint = if (starred) Theme[colors][accentText] else Theme[colors][textTertiary],
            iconSize = 18.dp,
        )
    }
}

/** A provider offering the model of a row: picks the model from that provider. */
@Composable
private fun ProviderPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusSmall])
    Box(
        Modifier
            .height(26.dp)
            .background(if (selected) Theme[colors][accentSoft] else Color.Transparent, shape)
            .border(1.dp, if (selected) Color.Transparent else Theme[colors][stroke], shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = Theme[typography][caption],
            color = if (selected) Theme[colors][accentText] else Theme[colors][textSecondary],
            maxLines = 1,
        )
    }
}

/** Leaves your models for the whole catalog. */
@Composable
private fun BrowseAllRow(count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("All models", style = Theme[typography][body], color = Theme[colors][accentText], modifier = Modifier.weight(1f))
        Text("$count", style = Theme[typography][caption], color = Theme[colors][textTertiary])
        UnstyledIcon(Lucide.ChevronRight, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.padding(start = 6.dp).size(16.dp))
    }
}

/** The gateway asks before switching to an expensive model. */
@Composable
internal fun ModelConfirmDialog(pending: PendingSwitch?, onConfirm: (ModelOption) -> Unit, onDismiss: () -> Unit) {
    Dialog(
        visible = pending != null,
        onDismissRequest = onDismiss,
        title = "Switch to ${pending?.model?.id?.let(::displayModelName).orEmpty()}?",
        message = pending?.message,
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost)
            Button("Switch", onClick = { pending?.let { onConfirm(it.model) } })
        },
    )
}

private const val SEARCH_THRESHOLD = 12
