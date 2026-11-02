package dev.hermeskotlin.ui

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuComponent
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSeparator
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.PopupPositionProvider
import com.composables.icons.lucide.ClipboardPaste
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleQuestion
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Scissors
import com.composables.icons.lucide.TextSelect
import dev.hermeskotlin.designsystem.components.LinkUnderPointer
import dev.hermeskotlin.designsystem.components.MenuPanel
import dev.hermeskotlin.designsystem.components.MenuPopup
import dev.hermeskotlin.designsystem.components.PointedLink
import dev.hermeskotlin.designsystem.components.MenuRow
import dev.hermeskotlin.designsystem.components.MenuSeparator
import dev.hermeskotlin.ui.chat.SelectionAction
import kotlinx.coroutines.CompletableDeferred

/**
 * Turns on Compose's newer text menu, which takes the items a [androidx.compose.foundation.text.selection.SelectionContainer]
 * adds (Comment, Explain, Ask aside on a reply). The older one, the default on a desktop, only ever shows its
 * own Copy, Cut, Paste and Select all. Call it once, before the first window is composed.
 */
@OptIn(ExperimentalFoundationApi::class)
fun useAppTextContextMenu() {
    ComposeFoundationFlags.isNewContextMenuEnabled = true
}

/**
 * Right-clicking text in [content] opens the app's own menu, drawn as every other desktop menu here, in place of
 * Compose's plain default. It needs [useAppTextContextMenu]: without it, Compose never asks for this menu.
 */
@Composable
internal actual fun PlatformTextContextMenu(content: @Composable () -> Unit) {
    val menu = remember { AppTextMenu() }
    var anchor by remember { mutableStateOf<LayoutCoordinates?>(null) }
    CompositionLocalProvider(LocalTextContextMenuDropdownProvider provides menu) {
        Box(Modifier.onGloballyPositioned { anchor = it }, propagateMinConstraints = true) { content() }
    }
    val open = menu.open
    val at = anchor
    // Each opening a menu of its own: nothing the last one remembered carries over.
    if (open != null && at != null) key(open) { TextMenu(open, at) }
}

/** Shows one menu at a time: the text asking for it waits until it's closed, or moves on and drops it. */
private class AppTextMenu : TextContextMenuProvider {
    var open by mutableStateOf<OpenMenu?>(null)

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        val shown = OpenMenu(dataProvider)
        open = shown
        try {
            shown.closed.await()
        } finally {
            if (open === shown) open = null
        }
    }
}

private class OpenMenu(val data: TextContextMenuDataProvider) : TextContextMenuSession {
    val closed = CompletableDeferred<Unit>()

    /** The link the right-click was on, if any: kept as it was then, while the pointer moves on to the menu. */
    val link: PointedLink? = LinkUnderPointer.link

    override fun close() {
        closed.complete(Unit)
    }
}

@Composable
private fun TextMenu(open: OpenMenu, anchor: LayoutCoordinates) {
    val entries by remember(open) {
        derivedStateOf {
            val own = open.data.data().components.entries()
            val links = open.linkEntries()
            if (own.isEmpty()) links.dropLastWhile { it == null } else links + own
        }
    }
    // Nothing to offer, such as a right-click on text that can't be selected: no empty panel.
    if (entries.isEmpty()) return open.close()
    val position = remember(open, anchor) { AtPoint { open.data.position(anchor).round() } }
    MenuPopup(position, onDismiss = open::close) {
        MenuPanel(minWidth = 180.dp) {
            entries.forEach { entry ->
                if (entry == null) MenuSeparator()
                else MenuRow(entry.label, iconFor(entry.key), onClick = { entry.onClick(open) }, enabled = entry.enabled)
            }
        }
    }
}

/** A row of the menu; null for a rule between rows. */
private class Entry(val key: Any, val label: String, val enabled: Boolean, val onClick: (TextContextMenuSession) -> Unit)

/** The rows and rules to draw: no rule at either end, none twice in a row, and nothing this menu can't draw. */
private fun List<TextContextMenuComponent>.entries(): List<Entry?> {
    val drawn = mapNotNull { if (it === TextContextMenuSeparator) SEPARATOR else it.entry() }
    return drawn.filterIndexed { i, e -> e !== SEPARATOR || (i > 0 && drawn[i - 1] !== SEPARATOR) }
        .dropWhile { it === SEPARATOR }
        .dropLastWhile { it === SEPARATOR }
        .map { it.takeIf { e -> e !== SEPARATOR } }
}

private val SEPARATOR = Entry(Unit, "", false) {}

/** Open link and Copy link, above the text's own rows, when the right-click was on a link. */
private fun OpenMenu.linkEntries(): List<Entry?> {
    val link = link ?: return emptyList()
    return listOf(
        Entry(LinkKey.Open, "Open link", true) { it.close(); link.open() },
        Entry(LinkKey.Copy, "Copy link", true) { it.close(); link.copy() },
        null,
    )
}

private enum class LinkKey { Open, Copy }

/**
 * The desktop's item class is internal to Compose, with its label, state and action public on the JVM: read
 * by name, as Compose's own menu does. An item that doesn't read is left out.
 */
@Suppress("UNCHECKED_CAST")
private fun TextContextMenuComponent.entry(): Entry? = runCatching {
    val type = javaClass
    Entry(
        key = key,
        label = type.getMethod("getLabel").invoke(this) as String,
        enabled = type.getMethod("getEnabled").invoke(this) as Boolean,
        onClick = type.getMethod("getOnClick").invoke(this) as (TextContextMenuSession) -> Unit,
    )
}.getOrNull()

private fun iconFor(key: Any): ImageVector? = when (key) {
    TextContextMenuKeys.CutKey -> Lucide.Scissors
    TextContextMenuKeys.CopyKey -> Lucide.Copy
    TextContextMenuKeys.PasteKey -> Lucide.ClipboardPaste
    TextContextMenuKeys.SelectAllKey -> Lucide.TextSelect
    LinkKey.Open -> Lucide.ExternalLink
    LinkKey.Copy -> Lucide.Link
    SelectionAction.Comment -> Lucide.MessageSquare
    SelectionAction.Explain -> Lucide.Lightbulb
    SelectionAction.AskAside -> Lucide.MessageCircleQuestion
    else -> null
}

/**
 * Opens at [point] (where the click was, inside the anchor), down and to the right, or flipped up and left
 * where the window has no room that way.
 */
private class AtPoint(private val point: () -> IntOffset) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val at = anchorBounds.topLeft + point()
        val x = if (at.x + popupContentSize.width > windowSize.width) at.x - popupContentSize.width else at.x
        val y = if (at.y + popupContentSize.height > windowSize.height) at.y - popupContentSize.height else at.y
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}
