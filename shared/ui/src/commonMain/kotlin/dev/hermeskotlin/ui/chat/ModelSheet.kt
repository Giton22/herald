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
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.eyebrow
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.radiusXSmall
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.designsystem.warningSoft
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Zap
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
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = if (note != null) 4.dp else 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Model",
                style = Theme[typography][title],
                color = Theme[colors][textColor],
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (!modelOnly) {
                Text(
                    "For this chat",
                    style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp),
                    color = Theme[colors][textTertiary],
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
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
            Text(
                "Thinking",
                style = Theme[typography][label],
                color = Theme[colors][textSecondary],
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp).semantics { heading() },
            )
            val current = state.effort(option)
            val chips = rememberLazyListState()
            // Seven or eight levels don't fit across a phone as a segmented control, so they scroll as chips:
            // bring the chat's level into view, with its neighbour peeking.
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
            FastModeCard(state.fast == true, onFast, Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp))
        }

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

/** A list's section, in small caps; read as its own words, not spelled out. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = Theme[typography][eyebrow],
        color = Theme[colors][textTertiary],
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)
            .semantics {
                heading()
                contentDescription = text
            },
    )
}

/** Fast mode on a raised card: a bolt on a warm tile, what it does, and its switch. The whole card toggles it. */
@Composable
private fun FastModeCard(on: Boolean, onFast: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][surface2])
            .toggleable(value = on, role = Role.Switch, onValueChange = onFast)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).background(Theme[colors][warningSoft], RoundedCornerShape(Theme[radii][radiusSmall])),
            contentAlignment = Alignment.Center,
        ) {
            UnstyledIcon(Lucide.Zap, contentDescription = null, tint = Theme[colors][warning], modifier = Modifier.size(15.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Fast mode", style = Theme[typography][body].copy(fontSize = 15.sp), color = Theme[colors][textColor])
            Text("Priority processing. Costs more.", style = Theme[typography][caption].copy(fontSize = 12.5.sp), color = Theme[colors][textTertiary])
        }
        Switch(on)
    }
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
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
            .background(if (selected) Theme[colors][accentSoft] else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleStar()
                },
                onLongClickLabel = if (starred) "Unstar" else "Star",
            )
            .semantics { this.selected = selected }
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The model's initial on a tile, so the list scans by shape before it's read.
        Box(
            Modifier.size(34.dp).background(Theme[colors][surface3], RoundedCornerShape(Theme[radii][radiusSmall])),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                row.name.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty(),
                style = Theme[typography][label].copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = Theme[colors][textSecondary],
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                row.name,
                style = Theme[typography][body].copy(fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                color = Theme[colors][textColor],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                single?.provider?.let { providerNames?.get(it) },
                row.variants.first().id.takeIf { showId && displayModelName(it) != it },
            ).joinToString(" · ")
            val price = single?.price
            if (details.isNotEmpty() || price != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (details.isNotEmpty()) {
                        Text(
                            details,
                            style = Theme[typography][caption].copy(fontSize = 12.sp),
                            color = Theme[colors][textTertiary],
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (price != null) PriceTag(price)
                }
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

/** A model's price per million tokens, in mono on a small raised tag. */
@Composable
private fun PriceTag(price: String) {
    Text(
        price,
        style = Theme[typography][code].copy(fontSize = 10.5.sp),
        color = Theme[colors][textSecondary],
        maxLines = 1,
        modifier = Modifier
            .background(Theme[colors][surface3], RoundedCornerShape(Theme[radii][radiusXSmall]))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Leaves your models for the whole catalog. */
@Composable
private fun BrowseAllRow(count: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Theme[radii][radiusMedium]))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "All models",
            style = Theme[typography][body].copy(fontSize = 14.5.sp, fontWeight = FontWeight.Medium),
            color = Theme[colors][accentText],
            modifier = Modifier.weight(1f),
        )
        Text("$count", style = Theme[typography][code].copy(fontSize = 12.sp), color = Theme[colors][textTertiary])
        UnstyledIcon(Lucide.ChevronRight, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(15.dp))
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
