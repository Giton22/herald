package dev.hermeskotlin.designsystem.components

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** One formula found in a reply: its TeX source and whether it stands on its own line. */
data class MathFormula(val latex: String, val display: Boolean)

/**
 * Finds TeX math in Markdown and turns each formula into an image link the renderer can't mangle:
 * `_`, `*` and `\` inside a formula would otherwise read as emphasis and escapes. The link carries the
 * source, so [MarkdownText] draws the formula where the link lands.
 *
 * Display math is `$$…$$` or `\[…\]`; inline math is `\(…\)` or `$…$`. A single `$` only counts when
 * what it wraps looks like math, so prices ("$5 and $10") stay as they were. Nothing inside code spans
 * or fences is touched, and `\$` stays a dollar sign.
 */
object MathMarkup {

    private const val SCHEME = "herald-math:"

    /** The Markdown with its formulas as links; text with no `$` or `\` comes back as it was. */
    fun prepare(markdown: String): String {
        if ('$' !in markdown && '\\' !in markdown) return markdown
        val chars = markdown.toCharArray()
        val protected = protectedMask(chars)
        val out = StringBuilder(markdown.length)
        var index = 0
        while (index < chars.size) {
            val display = displayAt(chars, index, protected)
            if (display != null) {
                val (close, length) = display
                val latex = markdown.substring(index + 2, close).trim()
                if (latex.isNotEmpty()) {
                    out.appendDisplay(markdown, index, close + length, latex)
                    index = close + length
                    continue
                }
            }
            val inline = inlineAt(chars, index, protected)
            if (inline != null) {
                val (open, close, closeLength) = inline
                out.append(link(markdown.substring(index + open, close).trim(), display = false))
                index = close + closeLength
                continue
            }
            out.append(chars[index])
            index++
        }
        return out.toString()
    }

    /** The formula a link made by [prepare] carries, or null for any other link. */
    @OptIn(ExperimentalEncodingApi::class)
    fun formula(link: String): MathFormula? {
        if (!link.startsWith(SCHEME)) return null
        val rest = link.removePrefix(SCHEME)
        val display = when {
            rest.startsWith("d:") -> true
            rest.startsWith("i:") -> false
            else -> return null
        }
        val latex = runCatching { BASE64.decode(rest.drop(2)).decodeToString() }.getOrNull() ?: return null
        return MathFormula(latex, display)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun link(latex: String, display: Boolean): String =
        "![math](" + SCHEME + (if (display) "d:" else "i:") + BASE64.encode(latex.encodeToByteArray()) + ")"

    @OptIn(ExperimentalEncodingApi::class)
    private val BASE64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /**
     * A display formula alone on its lines becomes a paragraph of its own (keeping the line's indent, so
     * it stays inside a list item); one written mid-sentence is drawn in the line.
     */
    private fun StringBuilder.appendDisplay(source: String, start: Int, end: Int, latex: String) {
        val lineStart = source.lastIndexOf('\n', start - 1) + 1
        val indent = source.substring(lineStart, start)
        val lineEnd = source.indexOf('\n', end).let { if (it < 0) source.length else it }
        val alone = indent.isBlank() && source.substring(end, lineEnd).isBlank()
        if (alone) {
            // A blank line before and after makes it a block; the indent is already written.
            if (lineStart > 0) append('\n').append(indent)
            append(link(latex, display = true))
            append('\n')
        } else {
            append(link(latex, display = true))
        }
    }

    /** `$$` or `\[` opening at [index] with its closer found: the closer's index and length. */
    private fun displayAt(chars: CharArray, index: Int, protected: BooleanArray): Pair<Int, Int>? {
        val closer = when {
            matches(chars, index, "$$", protected) -> "$$"
            matches(chars, index, "\\[", protected) -> "\\]"
            else -> return null
        }
        if (isEscaped(chars, index)) return null
        var i = index + 2
        while (i < chars.size) {
            if (matches(chars, i, closer, protected) && !isEscaped(chars, i)) return i to 2
            i++
        }
        return null
    }

    /** Inline math opening at [index]: opener length, closer index and closer length. */
    private fun inlineAt(chars: CharArray, index: Int, protected: BooleanArray): Triple<Int, Int, Int>? {
        if (isEscaped(chars, index)) return null
        if (matches(chars, index, "\\(", protected)) {
            var i = index + 2
            while (i < chars.size && chars[i] != '\n') {
                if (matches(chars, i, "\\)", protected) && !isEscaped(chars, i)) {
                    return if (chars.concatToString(index + 2, i).isNotBlank()) Triple(2, i, 2) else null
                }
                i++
            }
            return null
        }
        if (!isSingleDollar(chars, index, protected)) return null
        // Like Pandoc: no space just inside the opener.
        if (index + 1 >= chars.size || chars[index + 1].isWhitespace()) return null
        var i = index + 1
        while (i < chars.size && chars[i] != '\n') {
            if (isSingleDollar(chars, i, protected) && !isEscaped(chars, i)) {
                val inner = chars.concatToString(index + 1, i)
                // No space just inside the closer, and no digit right after it ("$5 to $10" is money).
                val closes = !chars[i - 1].isWhitespace() && chars.getOrNull(i + 1)?.isDigit() != true
                return if (closes && looksLikeMath(inner)) Triple(1, i, 1) else null
            }
            i++
        }
        return null
    }

    private fun isSingleDollar(chars: CharArray, index: Int, protected: BooleanArray): Boolean =
        chars[index] == '$' && !protected[index] &&
            chars.getOrNull(index - 1) != '$' && chars.getOrNull(index + 1) != '$'

    private fun matches(chars: CharArray, index: Int, token: String, protected: BooleanArray): Boolean {
        if (index < 0 || index + token.length > chars.size) return false
        for (k in token.indices) {
            if (protected[index + k] || chars[index + k] != token[k]) return false
        }
        return true
    }

    /** Preceded by an odd number of backslashes. */
    private fun isEscaped(chars: CharArray, index: Int): Boolean {
        var count = 0
        var i = index - 1
        while (i >= 0 && chars[i] == '\\') {
            count++
            i--
        }
        return count % 2 == 1
    }

    /** Whether text between single dollars is math rather than prose or prices. */
    internal fun looksLikeMath(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        if (SINGLE_SYMBOL.matches(trimmed)) return true
        if (looksLikeAssignment(trimmed)) return true
        // A bare numeric tuple is math; a bare number (a price) is not.
        if (NUMERIC_TUPLE.matches(trimmed)) return true
        if ('\\' in trimmed || '^' in trimmed || '_' in trimmed) return true
        return OPERATOR_BETWEEN.containsMatchIn(trimmed)
    }

    /** `x = (a+b)`-style forms, whose grouped right side the operator rule doesn't see. */
    private fun looksLikeAssignment(value: String): Boolean {
        val parts = value.split('=', limit = 2)
        if (parts.size != 2) return false
        if (!SINGLE_SYMBOL.matches(parts[0].trim())) return false
        val rhs = parts[1].trim()
        if (rhs.isEmpty()) return false
        if (!rhs.all { it.isLetterOrDigit() || it.isWhitespace() || it in ASSIGNMENT_SYMBOLS }) return false
        val stack = ArrayDeque<Char>()
        for (c in rhs) {
            when (c) {
                '(', '[', '{' -> stack.addLast(c)
                ')' -> if (stack.removeLastOrNull() != '(') return false
                ']' -> if (stack.removeLastOrNull() != '[') return false
                '}' -> if (stack.removeLastOrNull() != '{') return false
            }
        }
        return stack.isEmpty() && rhs.any { it.isDigit() || it in "\\_^,+-*/<>|" }
    }

    private val SINGLE_SYMBOL = Regex("""^[A-Za-z](?:_[A-Za-z0-9]+|\^[A-Za-z0-9]+)?$""")
    private val NUMERIC_TUPLE = Regex("""^\(\s*[+-]?\d+(?:\.\d+)?(?:\s*,\s*[+-]?\d+(?:\.\d+)?)+\s*\)$""")
    private val OPERATOR_BETWEEN = Regex("""[A-Za-z0-9]\s*[=<>+\-*/|]\s*[A-Za-z0-9]""")
    private const val ASSIGNMENT_SYMBOLS = "\\_^,+-*/.=<>|[](){}"

    /** Characters inside fenced or indented code blocks and code spans, where `$` and `\` mean nothing. */
    internal fun protectedMask(chars: CharArray): BooleanArray {
        val mask = BooleanArray(chars.size)
        // Fences: a line starting (after spaces) with ``` or ~~~ opens; a bare run of at least as many of
        // the same character closes ("```kotlin" inside a ```` fence is content).
        var lineStart = 0
        var fenceStart = -1
        var fenceChar = ' '
        var fenceRun = 0
        // Indented code: lines indented 4+ after a blank line, unless that indent belongs to a list item.
        var previousBlank = true
        var flatContext = true
        var inIndentedCode = false
        while (lineStart < chars.size) {
            var lineEnd = lineStart
            while (lineEnd < chars.size && chars[lineEnd] != '\n') lineEnd++
            val next = if (lineEnd < chars.size) lineEnd + 1 else lineEnd
            var c = lineStart
            var indent = 0
            while (c < lineEnd && chars[c].isWhitespace()) {
                indent += if (chars[c] == '\t') 4 - indent % 4 else 1
                c++
            }
            val blank = c == lineEnd
            if (fenceStart < 0 && !blank && indent >= 4 && (inIndentedCode || (previousBlank && flatContext))) {
                inIndentedCode = true
                for (k in lineStart until next) mask[k] = true
                lineStart = next
                previousBlank = false
                continue
            }
            if (!blank) inIndentedCode = false
            val candidate = chars.getOrNull(c)
            if (candidate == '`' || candidate == '~') {
                var run = 0
                while (c + run < lineEnd && chars[c + run] == candidate) run++
                if (run >= 3) {
                    val bare = (c + run until lineEnd).all { chars[it].isWhitespace() }
                    if (fenceStart >= 0 && candidate == fenceChar && run >= fenceRun && bare) {
                        for (k in fenceStart until next) mask[k] = true
                        fenceStart = -1
                    } else if (fenceStart < 0) {
                        fenceStart = lineStart
                        fenceChar = candidate
                        fenceRun = run
                    }
                }
            }
            if (!blank && fenceStart < 0) flatContext = indent == 0 && !LIST_MARKER.containsMatchIn(chars.concatToString(c, lineEnd))
            previousBlank = blank
            lineStart = next
        }
        // A fence still open (a reply mid-stream) runs to the end.
        if (fenceStart >= 0) for (k in fenceStart until chars.size) mask[k] = true
        // Code spans: a run of backticks up to the next run of the same length in the same paragraph.
        var i = 0
        while (i < chars.size) {
            if (chars[i] != '`' || mask[i]) {
                i++
                continue
            }
            val run = runLength(chars, i)
            var j = i + run
            var close = -1
            while (j < chars.size) {
                if (chars[j] == '\n' && endsParagraph(chars, j + 1)) break
                if (chars[j] == '`' && !mask[j]) {
                    val other = runLength(chars, j)
                    if (other == run) {
                        close = j
                        break
                    }
                    j += other
                } else {
                    j++
                }
            }
            if (close >= 0) {
                for (k in i until close + run) mask[k] = true
                i = close + run
            } else {
                i += run
            }
        }
        return mask
    }

    /** The line starting at [index] is blank, so a code span can't run on past it. */
    private fun endsParagraph(chars: CharArray, index: Int): Boolean {
        var i = index
        while (i < chars.size && chars[i] != '\n') {
            if (!chars[i].isWhitespace()) return false
            i++
        }
        return true
    }

    private val LIST_MARKER = Regex("""^(?:[-*+]|\d{1,9}[.)])(?:\s|$)""")

    private fun runLength(chars: CharArray, index: Int): Int {
        var n = 0
        while (index + n < chars.size && chars[index + n] == '`') n++
        return n
    }
}
