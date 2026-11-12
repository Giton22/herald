package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable

// The iOS edit menu keeps its own items for now: text is selectable and copyable, without Comment and Explain.
@Composable
internal actual fun PlatformCommentableSelection(
    onAction: ((SelectionAction, SelectionAnchor) -> Unit)?,
    // Asked only by the desktop's right-click; its default "nothing selected" stands.
    probe: SelectionProbe?,
    content: @Composable () -> Unit,
) = SelectionContainer(content = content)
