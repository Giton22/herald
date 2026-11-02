package dev.hermeskotlin.ui.components

import dev.hermeskotlin.designsystem.components.handCursor
import dev.hermeskotlin.designsystem.components.OpenModals

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.draw.clip
import dev.hermeskotlin.designsystem.components.ScrollbarBox
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composeunstyled.DialogPanel
import com.composeunstyled.Scrim
import com.composeunstyled.Text
import com.composeunstyled.TextInput
import com.composeunstyled.UnstyledDialog
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.UnstyledTextField
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.surfaceElevated
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography

/** One thing the palette can do: [label] under [group], found by it or its [keywords]; [hint] is its shortcut. */
class PaletteItem(
    val group: String,
    val label: String,
    val icon: ImageVector,
    val hint: String? = null,
    val keywords: String = "",
    val run: () -> Unit,
)

/**
 * What [query] finds among [items]: every word of it somewhere in a label or its keywords, ignoring case.
 * Labels that start with the query come first, then ones with a word that does; otherwise the given order
 * stands, so the groups stay together. An empty query finds everything.
 */
fun paletteMatches(query: String, items: List<PaletteItem>): List<PaletteItem> {
    val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return items
    val q = words.joinToString(" ")
    return items
        .filter { item -> "${item.label} ${item.keywords} ${item.group}".lowercase().let { text -> words.all { it in text } } }
        .sortedBy { item ->
            val label = item.label.lowercase()
            when {
                label.startsWith(q) -> 0
                label.split(' ').any { it.startsWith(words.first()) } -> 1
                else -> 2
            }
        }
}

/**
 * Hermes Desktop's Ctrl+K: one field to reach a chat, a page or an action. Up and Down pick, Enter runs,
 * Escape closes. [onSearchAll] adds a last row that searches every chat for what's typed.
 */
@Composable
fun CommandPalette(
    visible: Boolean,
    items: List<PaletteItem>,
    onDismiss: () -> Unit,
    onSearchAll: ((String) -> Unit)? = null,
) {
    val query = remember { TextFieldState() }
    val focus = remember { FocusRequester() }
    var selected by remember { mutableIntStateOf(0) }
    // Whether the arrow keys or typing moved the highlight last: then the pointer takes it back only by moving,
    // not when rows slide under it as the list scrolls or filters.
    var byKeys by remember { mutableStateOf(true) }
    LaunchedEffect(visible) {
        if (visible) {
            query.clearText()
            selected = 0
            byKeys = true
        }
    }
    // Kept while the panel fades out: its owner passes no items once it's closed.
    var kept by remember { mutableStateOf(items) }
    if (visible) kept = items
    val found by remember(kept) { derivedStateOf { paletteMatches(query.text.toString(), kept) } }
    val typed = query.text.toString().trim()
    val rows = if (onSearchAll != null && typed.isNotEmpty()) {
        found + PaletteItem("Search", "Search all chats for “$typed”", Lucide.Search) { onSearchAll(typed) }
    } else {
        found
    }
    val list = rememberLazyListState()
    // Each new query, and each opening, starts at its best match, at the top of the list.
    LaunchedEffect(typed, visible) {
        // Not on closing: the list holds still while it fades out.
        if (!visible) return@LaunchedEffect
        selected = 0
        byKeys = true
        list.scrollToItem(0)
    }
    // The list can also shrink while it's open (a chat deleted elsewhere): the highlight stays on a row.
    LaunchedEffect(rows.size) { selected = selected.coerceIn(0, (rows.size - 1).coerceAtLeast(0)) }
    val scope = rememberCoroutineScope()
    // The arrow keys bring their row into view, only as far as it takes; the pointer picks what it rests on and never scrolls.
    fun pick(index: Int) {
        selected = index
        byKeys = true
        scope.launch { list.reveal(index) }
    }
    fun run(item: PaletteItem) {
        onDismiss()
        item.run()
    }

    OpenModals.Count(visible)
    UnstyledDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        overlay = { Scrim(scrimColor = Color.Black.copy(alpha = 0.35f), enter = fadeIn(), exit = fadeOut()) },
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 72.dp), contentAlignment = Alignment.TopCenter) {
            DialogPanel(
                paneTitle = "Command palette",
                enter = fadeIn() + scaleIn(initialScale = 0.97f),
                exit = fadeOut() + scaleOut(targetScale = 0.97f),
                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
            ) {
                val shape = RoundedCornerShape(Theme[radii][radiusLarge])
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme[colors][surfaceElevated], shape)
                        .border(1.dp, Theme[colors][stroke], shape),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        UnstyledIcon(Lucide.Search, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(18.dp))
                        LaunchedEffect(visible) { if (visible) focus.requestFocus() }
                        UnstyledTextField(
                            state = query,
                            textStyle = Theme[typography][body],
                            textColor = Theme[colors][textColor],
                            cursorBrush = SolidColor(Theme[colors][accent]),
                            selectionColors = LocalTextSelectionColors.current,
                            lineLimits = TextFieldLineLimits.SingleLine,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                                .focusRequester(focus)
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (event.key) {
                                        Key.DirectionDown -> true.also { if (rows.isNotEmpty()) pick((selected + 1) % rows.size) }
                                        Key.DirectionUp -> true.also { if (rows.isNotEmpty()) pick((selected - 1 + rows.size) % rows.size) }
                                        Key.Enter, Key.NumPadEnter -> true.also { rows.getOrNull(selected)?.let(::run) }
                                        Key.Escape -> true.also { onDismiss() }
                                        else -> false
                                    }
                                },
                        ) {
                            TextInput(placeholder = {
                                Text("Search chats, pages and actions", style = Theme[typography][body], color = Theme[colors][textTertiary])
                            })
                        }
                    }
                    Box(Modifier.fillMaxWidth().background(Theme[colors][stroke]).heightIn(min = 1.dp, max = 1.dp))
                    if (rows.isEmpty()) {
                        Text(
                            "Nothing matches",
                            style = Theme[typography][bodySmall],
                            color = Theme[colors][textTertiary],
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    ScrollbarBox(
                        list,
                        // The panel doesn't scroll, so a new place on it is the pointer really moving.
                        Modifier.fillMaxWidth().pointerInput(Unit) {
                            var last: Offset? = null
                            awaitPointerEventScope {
                                while (true) {
                                    val at = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull()?.position ?: continue
                                    if (last != null && at != last) byKeys = false
                                    last = at
                                }
                            }
                        },
                    ) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
                            itemsIndexed(rows) { index, item ->
                                if (index == 0 || rows[index - 1].group != item.group) {
                                    Text(
                                        item.group,
                                        style = Theme[typography][caption],
                                        color = Theme[colors][textTertiary],
                                        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = if (index == 0) 4.dp else 12.dp, bottom = 4.dp),
                                    )
                                }
                                PaletteRow(item, selected = index == selected, onHover = { if (!byKeys) selected = index }, onClick = { run(item) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(item: PaletteItem, selected: Boolean, onHover: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusSmall])
    val hover by rememberUpdatedState(onHover)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Theme[colors][surface2] else Color.Transparent, shape)
            .clip(shape)
            // One highlight, wherever it came from: the row under the mouse becomes the one Enter runs.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Enter || event.type == PointerEventType.Move) hover()
                    }
                }
            }
            .handCursor()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UnstyledIcon(item.icon, contentDescription = null, tint = Theme[colors][textSecondary], modifier = Modifier.size(16.dp))
        Text(
            item.label,
            style = Theme[typography][body],
            color = Theme[colors][textColor],
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        item.hint?.let { Text(it, style = Theme[typography][caption], color = Theme[colors][textTertiary]) }
    }
}

/** Scrolls the least that shows the item at [index] whole: up to its top when above, or its bottom to the end when below. */
private suspend fun LazyListState.reveal(index: Int) {
    // How far the item's bottom reaches past the list's end; negative while it shows whole.
    fun overhang(): Int? = layoutInfo.let { info ->
        info.visibleItemsInfo.firstOrNull { it.index == index }
            ?.let { it.offset + it.size - info.viewportEndOffset + info.afterContentPadding }
    }
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    when {
        // Out of sight below, as the next row down is: it comes in on the bottom edge, not the top.
        item == null && index > (info.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE) -> {
            scrollToItem(index)
            overhang()?.let { scrollBy(it.toFloat()) }
        }
        item == null || item.offset < info.viewportStartOffset -> scrollToItem(index)
        else -> overhang()?.takeIf { it > 0 }?.let { scrollBy(it.toFloat()) }
    }
}
