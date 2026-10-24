package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable

// The iOS edit menu keeps its own items for now: text is selectable and copyable, without Comment and Explain.
@Composable
internal actual fun PlatformCommentableSelection(
    onAction: ((SelectionAction, SelectionAnchor) -> Unit)?,
    content: @Composable () -> Unit,
) = SelectionContainer(content = content)
