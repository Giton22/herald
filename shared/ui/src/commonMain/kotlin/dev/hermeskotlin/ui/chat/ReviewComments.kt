package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import dev.hermeskotlin.designsystem.components.TextHighlight

/** Something in the chat a comment can be about, named the way the message to the agent names it. */
data class CommentSource(
    /** The chat message it's in, whose highlights it adds to. */
    val messageKey: String,
    /** "your last reply", "the output of the terminal tool", …, as the agent reads it. */
    val label: String,
    /** The Markdown the text was rendered from, to tell a fenced code block from prose. */
    val markdown: String? = null,
    /** Monospace output, where a line number says where better than a sentence. */
    val code: Boolean = false,
)

/**
 * A selection in place. [blocks] are the separately drawn texts of the selectable area in order (paragraphs,
 * list items, code blocks); the selection runs from [start] in block [startBlock] to [end] in block [endBlock].
 */
data class SelectionAnchor(val blocks: List<String>, val startBlock: Int, val start: Int, val endBlock: Int, val end: Int) {
    /** The selected text, its blocks on lines of their own. */
    val text: String
        get() = if (startBlock == endBlock) {
            blocks[startBlock].substring(start, end)
        } else {
            buildList {
                add(blocks[startBlock].substring(start))
                addAll(blocks.subList(startBlock + 1, endBlock))
                add(blocks[endBlock].substring(0, end))
            }.joinToString("\n")
        }

    /** What to mark in each block it covers. */
    val highlights: List<TextHighlight>
        get() = (startBlock..endBlock).map { i ->
            TextHighlight(blocks[i], if (i == startBlock) start else 0, if (i == endBlock) end else blocks[i].length)
        }

    companion object {
        /** All of [text]. */
        fun whole(text: String) = SelectionAnchor(listOf(text), 0, 0, 0, text.length)
    }
}

/** What the selection menu offers besides Copy. */
enum class SelectionAction { Comment, Explain, AskAside }

/** A comment waiting in the composer for the next send; [note] is what's typed on it. */
class PendingComment(
    val id: Long,
    val source: CommentSource,
    /** The selected text. */
    val quote: String,
    /** "item 2", "line 4 of the kotlin code block", when the place has a name. */
    val where: String?,
    /** The sentence or lines around the selection, the selection between «»; null when that adds nothing. */
    val context: String?,
    val highlights: List<TextHighlight>,
    note: String = "",
) {
    val note = TextFieldState(note)
}

/** A comment read back from a sent message, to show as a card. [quote] has the commented part between «». */
data class SentComment(val on: String, val where: String?, val quote: String, val note: String)

/** A sent message carrying comments: what was typed [before] and [after] them. */
data class SentReview(val comments: List<SentComment>, val before: String, val after: String)

/** Builds a comment on [anchor]'s selection, with the name of its place and the words around it. */
internal fun newComment(id: Long, source: CommentSource, anchor: SelectionAnchor, note: String = ""): PendingComment {
    val quote = anchor.text
    return PendingComment(
        id = id,
        source = source,
        quote = quote,
        where = whereOf(source, anchor),
        context = contextOf(source, anchor),
        highlights = anchor.highlights,
        note = note,
    )
}

/** The message that goes out: the comments, in order, then whatever was typed in the composer. */
internal fun formatReview(comments: List<PendingComment>, typed: String): String = buildString {
    appendLine(OPEN)
    comments.forEach { comment ->
        append("<comment on=\"").append(attribute(comment.source.label)).append('"')
        comment.where?.let { append(" where=\"").append(attribute(it)).append('"') }
        appendLine(">")
        val quoted = comment.context ?: "«${comment.quote}»"
        quoted.lines().forEach { appendLine("> $it".trimEnd()) }
        comment.note.text.toString().trim().takeIf { it.isNotEmpty() }?.let { appendLine(it) }
        appendLine("</comment>")
    }
    append(CLOSE)
    typed.trim().takeIf { it.isNotEmpty() }?.let { append("\n\n").append(it) }
}

/** The comments in a sent message, or null when it has none. */
internal fun parseReview(text: String): SentReview? {
    val open = text.indexOf("$OPEN\n")
    val close = text.indexOf(CLOSE, startIndex = open.coerceAtLeast(0))
    if (open < 0 || close < 0) return null
    val body = text.substring(open + OPEN.length + 1, close)
    val comments = COMMENT.findAll(body).map { match ->
        val lines = match.groupValues[3].lines()
        val quote = lines.takeWhile { it.startsWith(">") }.joinToString("\n") { it.removePrefix(">").removePrefix(" ") }
        val note = lines.dropWhile { it.startsWith(">") }.joinToString("\n").trim()
        SentComment(unattribute(match.groupValues[1]), match.groupValues[2].takeIf { it.isNotEmpty() }?.let(::unattribute), quote, note)
    }.toList()
    if (comments.isEmpty()) return null
    return SentReview(comments, before = text.substring(0, open).trim(), after = text.substring(close + CLOSE.length).trim())
}

/** Where in the text the selection is, when that has a name. */
private fun whereOf(source: CommentSource, anchor: SelectionAnchor): String? {
    val block = anchor.blocks[anchor.startBlock]
    if (source.code) {
        // Output drawn a line per text (a subagent's activity) counts its blocks; one text counts its lines.
        return when {
            anchor.blocks.size > 1 -> lines(anchor.startBlock + 1, anchor.endBlock + 1)
            // One line needs no number.
            '\n' !in block.trimEnd('\n') -> null
            else -> lines(lineAt(block, anchor.start), lineAt(block, (anchor.end - 1).coerceAtLeast(anchor.start)))
        }
    }
    val fences = codeFences(source.markdown)
    fences.withIndex().firstOrNull { it.value.code == block.trimEnd('\n') }?.let { (index, fence) ->
        val lines = lines(lineAt(block, anchor.start), lineAt(block, (anchor.end - 1).coerceAtLeast(anchor.start)))
        val which = if (fences.size == 1) "the" else "the ${ordinal(index + 1)}"
        return "$lines of $which ${fence.language ?: "code"} block"
    }
    // A numbered list draws each number as its own text just before the item.
    val previous = anchor.blocks.getOrNull(anchor.startBlock - 1)?.trim()
    NUMBER.matchEntire(previous.orEmpty())?.let { return "item ${it.groupValues[1]}" }
    return null
}

/**
 * The sentence (prose) or lines (code) the selection is in, with the selection between «», so a word that
 * appears more than once is still found. Null when it would just repeat the selection.
 */
private fun contextOf(source: CommentSource, anchor: SelectionAnchor): String? {
    if (anchor.startBlock != anchor.endBlock) return null
    val block = anchor.blocks[anchor.startBlock]
    val code = source.code || codeFences(source.markdown).any { it.code == block.trimEnd('\n') }
    val (from, to) = if (code) lineBounds(block, anchor.start, anchor.end) else sentenceBounds(block, anchor.start, anchor.end)
    if (code && block.substring(from, to).count { it == '\n' } >= MAX_CONTEXT_LINES) return null
    var before = block.substring(from, anchor.start)
    var after = block.substring(anchor.end, to)
    if (!code) {
        before = before.trimStart()
        after = after.trimEnd()
        if (before.length > CONTEXT_SIDE) before = "…" + before.takeLast(CONTEXT_SIDE).trimStart()
        if (after.length > CONTEXT_SIDE) after = after.take(CONTEXT_SIDE).trimEnd() + "…"
    }
    if (before.isBlank() && after.isBlank()) return null
    return "$before«${block.substring(anchor.start, anchor.end)}»$after"
}

private fun lineAt(text: String, offset: Int): Int = text.take(offset).count { it == '\n' } + 1

private fun lines(first: Int, last: Int) = if (first == last) "line $first" else "lines $first–$last"

private fun lineBounds(text: String, start: Int, end: Int): Pair<Int, Int> {
    val from = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0 || start == 0) 0 else it + 1 }
    val to = text.indexOf('\n', end).let { if (it < 0) text.length else it }
    return from to to
}

/** From after the full stop (or line break) before [start] to the one ending the sentence [end] is in. */
private fun sentenceBounds(text: String, start: Int, end: Int): Pair<Int, Int> {
    var from = start
    while (from > 0 && !endsSentence(text, from - 1)) from--
    var to = end
    while (to < text.length && text[to] != '\n' && !endsSentence(text, to - 1)) to++
    return from to to
}

private fun endsSentence(text: String, i: Int): Boolean {
    if (i < 0 || i >= text.length) return false
    val c = text[i]
    if (c == '\n') return true
    if (c != '.' && c != '!' && c != '?') return false
    // "e.g." and "3.5" don't end a sentence: it takes a space (or the end) after the mark.
    return i + 1 >= text.length || text[i + 1].isWhitespace()
}

private class Fence(val language: String?, val code: String)

private fun codeFences(markdown: String?): List<Fence> {
    if (markdown == null) return emptyList()
    return FENCE.findAll(markdown).map { Fence(it.groupValues[2].trim().takeIf(String::isNotEmpty), it.groupValues[3].trimEnd('\n')) }.toList()
}

private fun ordinal(n: Int) = when (n) {
    1 -> "first"
    2 -> "second"
    3 -> "third"
    4 -> "fourth"
    5 -> "fifth"
    else -> "${n}th"
}

private fun attribute(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;").replace('\n', ' ')

private fun unattribute(value: String) = value.replace("&quot;", "\"").replace("&amp;", "&")

/** How the start of a reply is shown in a comment's label: its first words, without Markdown marks. */
internal fun openingWords(text: String, words: Int = 8): String {
    val plain = text.lineSequence()
        .map { it.trim().trimStart('#', '>', '-', '*', ' ').replace(MARKS, "") }
        .firstOrNull { it.isNotBlank() && !it.startsWith("```") }
        .orEmpty()
    val split = plain.split(' ').filter { it.isNotEmpty() }
    return if (split.size <= words) plain else split.take(words).joinToString(" ") + "…"
}

/** How a comment names the newest reply. */
internal const val LAST_REPLY = "your last reply"

private const val OPEN = "<comments>"
private const val CLOSE = "</comments>"
private const val CONTEXT_SIDE = 120
private const val MAX_CONTEXT_LINES = 6
private val COMMENT = Regex("""<comment on="([^"]*)"(?: where="([^"]*)")?>\n(.*?)\n?</comment>""", RegexOption.DOT_MATCHES_ALL)
private val NUMBER = Regex("""(\d+)[.)]""")
private val FENCE = Regex("""(?m)^(\s*)```([^\n`]*)\n(.*?)\n\s*```""", RegexOption.DOT_MATCHES_ALL)
private val MARKS = Regex("""[*_`]""")
