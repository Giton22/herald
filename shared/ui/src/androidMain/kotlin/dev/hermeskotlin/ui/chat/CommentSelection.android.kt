package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString

@Composable
internal actual fun PlatformCommentableSelection(
    onAction: ((SelectionAction, SelectionAnchor) -> Unit)?,
    content: @Composable () -> Unit,
) {
    if (onAction == null) {
        SelectionContainer(content = content)
        return
    }
    val latestOnAction by rememberUpdatedState(onAction)
    val state = remember { SelectionState() }
    SelectionContainer(
        state = state,
        modifier = Modifier.appendTextContextMenuComponents {
            SelectionAction.entries.forEach { action ->
                item(key = action, label = action.label) {
                    anchorOf(state)?.let { latestOnAction(action, it) }
                    state.clear()
                    close()
                }
            }
        },
        content = content,
    )
}

private val SelectionAction.label: String
    get() = when (this) {
        SelectionAction.Comment -> "Comment"
        SelectionAction.Explain -> "Explain"
        SelectionAction.AskAside -> "Ask aside"
    }

/**
 * Where the selection is. The state shares the selected texts but not the offsets, which only its saver
 * writes out: the selected texts, then start offset, start id, start direction, end offset, end id, end
 * direction and whether the handles crossed. Without those, the first block holding the selected text stands in.
 */
private fun anchorOf(state: SelectionState): SelectionAnchor? {
    val blocks = selectableTexts(state) ?: return null
    val selected = state.selectedTexts.map { it.text }.filter { it.isNotEmpty() }
    if (blocks.isEmpty() || selected.isEmpty()) return null
    val saved = runCatching { with(SelectionState.Saver) { AnySaverScope.save(state) } as? List<*> }.getOrNull()
    val startOffset = saved?.getOrNull(1) as? Int
    val endOffset = saved?.getOrNull(4) as? Int
    val crossed = saved?.getOrNull(7) as? Boolean ?: false
    val first = selected.first()
    // Where the selected part of the first block begins: the smaller offset when it's all in one block.
    val offset = when {
        startOffset == null || endOffset == null -> null
        selected.size == 1 -> minOf(startOffset, endOffset)
        crossed -> endOffset
        else -> startOffset
    }
    // Without the offset: where the text first appears, or for a selection running on, the end of the block.
    fun guess(i: Int) = if (selected.size == 1) blocks[i].indexOf(first) else blocks[i].length - first.length
    val startBlock = offset?.let { o -> blocks.indices.firstOrNull { fits(blocks, selected, it, o) } }
        ?: blocks.indices.firstOrNull { fits(blocks, selected, it, guess(it)) }
        ?: return null
    val start = if (offset != null && fits(blocks, selected, startBlock, offset)) offset else guess(startBlock)
    val endBlock = startBlock + selected.size - 1
    val end = if (selected.size == 1) start + first.length else selected.last().length
    return SelectionAnchor(blocks, startBlock, start, endBlock, end)
}

/**
 * Every text in the container, in order. Public on the JVM but hidden from Kotlin, so it's called by name;
 * androidApp's R8 rules keep that name.
 */
private fun selectableTexts(state: SelectionState): List<String>? = runCatching {
    (SelectionState::class.java.getMethod("getSelectableTexts").invoke(state) as List<*>).map { (it as AnnotatedString).text }
}.getOrNull()

/** Whether the selection could start at [offset] in block [i] and run on through the blocks after it. */
private fun fits(blocks: List<String>, selected: List<String>, i: Int, offset: Int): Boolean {
    if (offset < 0 || i + selected.size > blocks.size) return false
    val block = blocks[i]
    if (!block.regionMatches(offset, selected.first(), 0, selected.first().length)) return false
    if (selected.size == 1) return true
    if (offset + selected.first().length != block.length) return false
    for (k in 1 until selected.lastIndex) if (blocks[i + k] != selected[k]) return false
    return blocks[i + selected.lastIndex].startsWith(selected.last())
}

private object AnySaverScope : SaverScope {
    override fun canBeSaved(value: Any) = true
}
