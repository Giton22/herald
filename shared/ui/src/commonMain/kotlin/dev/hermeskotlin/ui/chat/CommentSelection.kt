package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.hermeskotlin.designsystem.components.LocalTextHighlights
import dev.hermeskotlin.designsystem.components.TextHighlight

/** Takes what the chat's selection menus ask for, and knows what the waiting comments mark. */
interface CommentHost {
    fun onSelection(action: SelectionAction, source: CommentSource, anchor: SelectionAnchor)

    /** What the comments waiting in the composer mark in message [messageKey]. */
    fun highlights(messageKey: String): List<TextHighlight>
}

/** The chat's [CommentHost]; without one, text is only selectable. */
val LocalCommentHost = staticCompositionLocalOf<CommentHost?> { null }

/**
 * Selectable text whose selection menu also offers Comment, Explain and Ask aside about [source], and which
 * marks what the waiting comments on it are about. Without a [source] or a [LocalCommentHost] it's only selectable.
 */
@Composable
fun CommentableSelection(source: CommentSource?, probe: SelectionProbe? = null, content: @Composable () -> Unit) {
    val host = LocalCommentHost.current
    if (source == null || host == null) {
        PlatformCommentableSelection(onAction = null, probe = probe, content = content)
        return
    }
    CompositionLocalProvider(LocalTextHighlights provides host.highlights(source.messageKey)) {
        PlatformCommentableSelection(onAction = { action, anchor -> host.onSelection(action, source, anchor) }, probe = probe, content = content)
    }
}

/** Tells whether something in a selection container is selected now: a right-click then leaves it the text's menu. */
class SelectionProbe {
    internal var selected: () -> Boolean = { false }

    val hasSelection: Boolean get() = selected()
}

/**
 * A selection container whose menu hands [onAction] the selection in place; plain when [onAction] is null.
 * [probe], when given, is kept able to tell whether anything is selected.
 */
@Composable
internal expect fun PlatformCommentableSelection(
    onAction: ((SelectionAction, SelectionAnchor) -> Unit)?,
    probe: SelectionProbe? = null,
    content: @Composable () -> Unit,
)
