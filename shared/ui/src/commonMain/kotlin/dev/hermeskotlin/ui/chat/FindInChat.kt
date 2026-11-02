package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.TextInput
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.UnstyledTextField
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surfaceElevated
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography

/** The words of [message] that Find in chat looks through; none for what isn't said (tool rows, events). */
internal fun findableText(message: ChatMessage): String = when (message) {
    is ChatMessage.User -> message.text
    is ChatMessage.Assistant -> message.text
    is ChatMessage.Command -> "${message.command}\n${message.output}"
    is ChatMessage.Notice -> message.text
    is ChatMessage.Event -> ""
}

/** The indexes of the [messages] that hold [query], ignoring case, oldest first; none for a blank query. */
internal fun findMatches(messages: List<ChatMessage>, query: String): List<Int> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return messages.indices.filter { findableText(messages[it]).contains(needle, ignoreCase = true) }
}

/**
 * Hermes Desktop's Ctrl+F: a field over the conversation, "3 of 12", and the way between matches. Enter goes to
 * the next (older) match, Shift+Enter back; Escape closes.
 */
@Composable
internal fun FindBar(
    query: TextFieldState,
    /** Which match is shown, from 0; meaningless while [total] is 0. */
    current: Int,
    total: Int,
    focus: FocusRequester,
    onStep: (older: Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier
            .fillMaxWidth()
            .background(Theme[colors][surfaceElevated], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(Lucide.Search, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp))
        UnstyledTextField(
            state = query,
            textStyle = Theme[typography][body],
            textColor = Theme[colors][textColor],
            cursorBrush = SolidColor(Theme[colors][accent]),
            selectionColors = LocalTextSelectionColors.current,
            lineLimits = TextFieldLineLimits.SingleLine,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> true.also { onStep(!event.isShiftPressed) }
                        Key.Escape -> true.also { onClose() }
                        else -> false
                    }
                },
        ) {
            TextInput(placeholder = { Text("Find in this chat", style = Theme[typography][body], color = Theme[colors][textTertiary]) })
        }
        if (query.text.isNotBlank()) {
            Text(
                if (total == 0) "No matches" else "${current + 1} of $total",
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        IconButton(Lucide.ChevronUp, "Previous match", onClick = { onStep(true) }, enabled = total > 0, iconSize = 18.dp)
        IconButton(Lucide.ChevronDown, "Next match", onClick = { onStep(false) }, enabled = total > 0, iconSize = 18.dp)
        IconButton(Lucide.X, "Close find", onClick = onClose, iconSize = 18.dp)
    }
}
