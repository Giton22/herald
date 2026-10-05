package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Search
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.models.ModelCatalog
import dev.hermeskotlin.core.models.ModelOption
import dev.hermeskotlin.core.models.ReasoningEffort
import dev.hermeskotlin.core.models.displayModelName
import dev.hermeskotlin.core.models.displayProviderName
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Chip
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.Switch
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.heading
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height

/** What the composer shows and the sheet marks: the chat's own pick, else the profile default. */
internal data class ModelSelection(val model: String?, val provider: String?, val option: ModelOption?) {
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
) {
    LaunchedEffect(visible) { if (visible) onRefresh() }
    val selection = ModelSelection.of(state, picker.catalog)
    val query = rememberTextFieldState()

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
        val option = selection.option
        val efforts = if (modelOnly) emptyList() else ReasoningEffort.choicesFor(option)
        if (efforts.isNotEmpty()) {
            SectionLabel("Thinking")
            val current = state.effort(option)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                efforts.forEach { effort ->
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
            else -> {
                val total = catalog.providers.sumOf { it.models.size }
                if (total > SEARCH_THRESHOLD) {
                    TextField(
                        state = query,
                        placeholder = "Search models",
                        leadingIcon = Lucide.Search,
                        clearable = true,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                    )
                }
                val needle = query.text.toString().trim()
                val rows = catalog.providers.mapNotNull { provider ->
                    val models = provider.models.filter {
                        needle.isEmpty() || it.id.contains(needle, ignoreCase = true) ||
                            displayModelName(it.id).contains(needle, ignoreCase = true)
                    }
                    if (models.isEmpty()) null else displayProviderName(provider.slug, provider.name) to models
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).padding(top = 4.dp)) {
                    rows.forEach { (providerName, models) ->
                        if (catalog.providers.size > 1) item(key = "h-$providerName") { SectionLabel(providerName) }
                        items(models, key = { "${it.provider}/${it.id}" }) { model ->
                            ModelRow(
                                model = model,
                                selected = model.id == selection.model && (selection.provider == null || model.provider == selection.provider),
                                onClick = { onSelectModel(model) },
                            )
                        }
                    }
                    if (rows.isEmpty()) {
                        item {
                            Text(
                                "No models match \"$needle\".",
                                style = Theme[typography][bodySmall],
                                color = Theme[colors][textTertiary],
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
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

@Composable
private fun ModelRow(model: ModelOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                displayModelName(model.id),
                style = Theme[typography][body],
                color = if (selected) Theme[colors][accent] else Theme[colors][textColor],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(model.id.takeIf { displayModelName(it) != it }, model.price).joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(details, style = Theme[typography][caption], color = Theme[colors][textTertiary], maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) UnstyledIcon(Lucide.Check, contentDescription = "Current", tint = Theme[colors][accent], modifier = Modifier.size(18.dp))
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
