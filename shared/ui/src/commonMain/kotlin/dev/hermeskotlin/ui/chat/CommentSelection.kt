package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable

/**
 * Selectable text whose selection menu also offers "Comment", which hands [onComment] the selected text.
 * Without [onComment] it's a plain selection container.
 */
@Composable
expect fun CommentableSelection(onComment: ((String) -> Unit)?, content: @Composable () -> Unit)

/**
 * The composer's text with [selection] added as a Markdown quote after whatever is already typed, ending where
 * the comment on it goes. Several comments stack one under another. A blank line closes the quote: Markdown
 * would take a comment typed on the very next line as more of it.
 */
internal fun withCommentQuote(composer: String, selection: String): String {
    val quote = selection.trim().lines().joinToString("\n") { line -> if (line.isBlank()) ">" else "> ${line.trimEnd()}" }
    val typed = composer.trimEnd()
    return if (typed.isEmpty()) "$quote\n\n" else "$typed\n\n$quote\n\n"
}
