package dev.hermeskotlin.ui.chat

import android.content.ClipboardManager
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard

@Composable
actual fun CommentableSelection(onComment: ((String) -> Unit)?, content: @Composable () -> Unit) {
    if (onComment == null) {
        SelectionContainer(content = content)
        return
    }
    val latestOnComment by rememberUpdatedState(onComment)
    val clipboard = LocalClipboard.current
    val capture = remember(clipboard) { CommentCapture(clipboard) { latestOnComment(it) } }
    // The menu doesn't say what's selected, so "Comment" runs the selection's own Copy and keeps what it would
    // have put on the clipboard. Everything else copied in here still reaches the real clipboard.
    CompositionLocalProvider(LocalClipboard provides capture) {
        SelectionContainer(
            Modifier
                .filterTextContextMenuComponents { component ->
                    if (component.key == TextContextMenuKeys.CopyKey) capture.copy = (component as? TextContextMenuItem)?.onClick
                    true
                }
                .appendTextContextMenuComponents {
                    item(key = CommentKey, label = "Comment") {
                        capture.comment(this)
                        close()
                    }
                },
            content = content,
        )
    }
}

private object CommentKey

/** Passes clips through to [clipboard], except the one copy [comment] asks for, which goes to [onComment]. */
private class CommentCapture(
    private val clipboard: Clipboard,
    private val onComment: (String) -> Unit,
) : Clipboard {
    /** The selection's Copy item, as the menu last listed it. */
    var copy: ((TextContextMenuSession) -> Unit)? = null
    private var capturing = false

    fun comment(session: TextContextMenuSession) {
        val copy = copy ?: return
        capturing = true
        copy(session)
    }

    override suspend fun getClipEntry(): ClipEntry? = clipboard.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        if (!capturing) return clipboard.setClipEntry(clipEntry)
        capturing = false
        val text = clipEntry?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (!text.isNullOrBlank()) onComment(text)
    }

    override val nativeClipboard: ClipboardManager get() = clipboard.nativeClipboard
}
