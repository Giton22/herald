package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.TextInput
import com.composeunstyled.UnstyledTextField
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MarkdownText
import dev.hermeskotlin.designsystem.components.highlightColor
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning

/**
 * The comments waiting for the next send, numbered like their order in the message, each with its note.
 * [focusComment] is one just added, whose note takes the focus so it can be typed straight away.
 */
@Composable
internal fun CommentTray(comments: List<PendingComment>, focusComment: Long?, onFocused: () -> Unit, onRemove: (Long) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        comments.forEachIndexed { index, comment ->
            key(comment.id) {
                PendingCommentCard(index + 1, comment, focus = comment.id == focusComment, onFocused = onFocused, onRemove = { onRemove(comment.id) })
            }
        }
    }
}

@Composable
private fun PendingCommentCard(number: Int, comment: PendingComment, focus: Boolean, onFocused: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    val requester = remember { FocusRequester() }
    LaunchedEffect(focus) {
        if (focus) {
            requester.requestFocus()
            onFocused()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(start = 10.dp, top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Number(number, Modifier.padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                quoteLine(comment),
                style = Theme[typography][caption],
                color = Theme[colors][textSecondary],
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            UnstyledTextField(
                state = comment.note,
                textStyle = Theme[typography][bodySmall],
                textColor = Theme[colors][textColor],
                cursorBrush = SolidColor(Theme[colors][accent]),
                selectionColors = LocalTextSelectionColors.current,
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).focusRequester(requester),
            ) {
                TextInput(
                    placeholder = {
                        Text("Your comment, or leave it to just point this out", style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
                    },
                )
            }
        }
        IconButton(Lucide.X, contentDescription = "Remove comment $number", onClick = onRemove, tint = Theme[colors][textTertiary], iconSize = 16.dp)
    }
}

/**
 * Where the comment points, then the selected words: "item 2 · “retry with backoff”". Anything but the
 * newest reply also says what it's in, since that isn't the obvious place.
 */
private fun quoteLine(comment: PendingComment): String {
    val quote = comment.quote.replace(Regex("""\s+"""), " ").trim()
    val source = comment.source.label.takeUnless { it == LAST_REPLY }?.replaceFirstChar { it.uppercaseChar() }
    return listOfNotNull(source, comment.where, "“$quote”").joinToString(" · ")
}

/** The order of a comment, in a small filled circle in the highlighter's color. */
@Composable
private fun Number(number: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size(18.dp).background(highlightColor(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$number", style = Theme[typography][label], color = Theme[colors][warning])
    }
}

/** A sent prompt that carried comments: what was typed around them, and a card for each. */
@Composable
internal fun SentReviewContent(review: SentReview) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (review.before.isNotBlank()) Text(review.before, style = Theme[typography][body], color = Theme[colors][textColor])
        review.comments.forEachIndexed { index, comment -> SentCommentCard(index + 1, comment) }
        if (review.after.isNotBlank()) Text(review.after, style = Theme[typography][body], color = Theme[colors][textColor])
    }
}

@Composable
private fun SentCommentCard(number: Int, comment: SentComment) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    val marker = highlightColor()
    Row(
        Modifier
            .fillMaxWidth()
            .background(Theme[colors][surface].copy(alpha = 0.6f), shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Number(number, Modifier.padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                listOfNotNull(comment.on.replaceFirstChar { it.uppercaseChar() }, comment.where).joinToString(" · "),
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                remember(comment.quote, marker) { marked(comment.quote, SpanStyle(background = marker)) },
                style = Theme[typography][bodySmall],
                color = Theme[colors][textSecondary],
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (comment.note.isNotBlank()) MarkdownText(comment.note)
        }
    }
}

/** [quote] without its «», the part between them in [style]. */
internal fun marked(quote: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    val open = quote.indexOf('«')
    val close = quote.lastIndexOf('»')
    if (open < 0 || close < open) {
        append(quote)
        return@buildAnnotatedString
    }
    append(quote.substring(0, open))
    withStyle(style) { append(quote.substring(open + 1, close)) }
    append(quote.substring(close + 1))
}
