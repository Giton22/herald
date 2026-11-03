package dev.hermeskotlin.designsystem.components

/**
 * Unicode Bidirectional isolation for text that mixes scripts.
 *
 * Arabic (and Hebrew) paragraphs are laid out right to left, but the runs inside them — inline code,
 * commands, file paths, version numbers — are left to right. When such a run sits between Arabic words,
 * the bidi algorithm reorders the neutrals around it (the full stop, a colon, a bracket) and the line
 * reads as if the punctuation and the clauses had swapped places.
 *
 * Isolating the run with LRI…PDI (U+2066 … U+2069) makes the whole run one object with its own
 * direction, so the Arabic around it keeps its own order and the run stays intact and legible. The
 * characters are zero width: they change nothing about what the text says, only how it is ordered.
 */
internal object Bidi {

    /** LEFT-TO-RIGHT ISOLATE: opens a run that is ordered left to right inside any paragraph. */
    const val LRI: Char = '\u2066'

    /** POP DIRECTIONAL ISOLATE: closes the run opened by [LRI] (or [FSI]). */
    const val PDI: Char = '\u2069'

    /**
     * Isolates every inline code span in [markdown]: `` `hermes update` `` becomes `` `\u2066hermes update\u2069` ``
     * so the span stays one left-to-right unit inside an Arabic sentence.
     *
     * Fenced blocks (``` / ~~~) and indented code are left alone — a code block is already its own
     * left-to-right paragraph. A span whose text is right to left (Arabic inside backticks) is left alone
     * too: isolating it to the left would force the wrong order. Text that already carries an isolate
     * character is not wrapped again, so the pass is idempotent.
     *
     * Only lines with right-to-left letters are touched, or every line when the whole reply is laid out
     * [rightToLeft]: a code span in a left-to-right line needs no isolate, and what the user selects and
     * copies from it then holds none.
     */
    fun isolateInlineCode(markdown: String, rightToLeft: Boolean = false): String {
        if (markdown.indexOf('`') < 0) return markdown
        return mapProseLines(markdown) { line -> if (rightToLeft || line.any(::isRightToLeft)) isolateSpans(line) else line }
    }

    /**
     * [markdown] with [transform] applied to every line that is not code, and [code] to every line that is:
     * fenced blocks (``` / ~~~), indented code and link reference definitions (`[1]: https://…`), whose
     * address must stay as written.
     *
     * A line indented four spaces is code unless it continues a paragraph (indented code can't interrupt
     * one) or sits in a list, where that indent belongs to a nested item or its text. A list starts at a
     * marker indented less than four spaces and ends at an unindented line after a blank one.
     */
    private inline fun mapProseLines(markdown: String, code: (String) -> String = { it }, transform: (String) -> String): String {
        var fence: String? = null
        var previousParagraph = false
        var inList = false
        val out = StringBuilder(markdown.length + 32)
        var previousBlank = true
        var start = 0
        while (start <= markdown.length) {
            val end = markdown.indexOf('\n', start).let { if (it < 0) markdown.length else it }
            val line = markdown.substring(start, end)
            val trimmed = line.trimStart()
            val opener = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            val blank = trimmed.isEmpty()
            val indented = line.startsWith("    ") || line.startsWith("\t")
            val inFence = fence != null || opener != null
            val listItem = !inFence && !blank && (!indented || inList) && listMarkerEnd(trimmed, 0) > 0
            if (listItem) inList = true else if (!inFence && !blank && !indented && previousBlank) inList = false
            val indentedCode = !inFence && indented && !inList && !previousParagraph
            val isCode = inFence || indentedCode || (!indented && LINK_DEFINITION.matches(line))
            if (opener != null) fence = if (fence == opener) null else opener
            out.append(if (isCode) code(line) else transform(line))
            if (end < markdown.length) out.append('\n')
            // Only paragraph text can be continued by an indented line; a heading, a rule or a table can't.
            previousParagraph = !isCode && !blank && !trimmed.startsWith('#') && !trimmed.startsWith('|') &&
                !THEMATIC_BREAK.matches(trimmed)
            previousBlank = blank
            start = end + 1
        }
        return out.toString()
    }

    /** [markdown]'s prose without code blocks and code spans: the words that decide its direction. */
    private fun prose(markdown: String): String =
        mapProseLines(markdown, code = { "" }) { line -> if (line.indexOf('`') < 0) line else CODE_SPAN.replace(line, " ") }

    /** One line's backtick spans, each isolated on its own. Handles runs of two or more backticks. */
    private fun isolateSpans(line: String): String {
        if (line.indexOf('`') < 0) return line
        val out = StringBuilder(line.length + 16)
        var i = 0
        while (i < line.length) {
            if (line[i] != '`') {
                out.append(line[i])
                i++
                continue
            }
            var run = 0
            while (i + run < line.length && line[i + run] == '`') run++
            val ticks = "`".repeat(run)
            val close = line.indexOf(ticks, i + run)
            if (close < 0) {
                out.append(ticks)
                i += run
                continue
            }
            val content = line.substring(i + run, close)
            out.append(ticks)
            out.append(if (needsIsolation(content)) "$LRI$content$PDI" else content)
            out.append(ticks)
            i = close + run
        }
        return out.toString()
    }

    /** A span needs isolation when it has something to show and its first strong character is left to right. */
    private fun needsIsolation(content: String): Boolean {
        if (content.isEmpty()) return false
        if (content.any { it == LRI || it == PDI }) return false
        for (c in content) {
            when {
                isRightToLeft(c) -> return false
                isLeftToRight(c) -> return true
            }
        }
        // Digits, punctuation or symbols alone: a number is already ordered left to right by bidi.
        return false
    }

    /**
     * Isolates the left-to-right runs a mostly-Arabic line carries — dates, amounts, versions, identifiers,
     * paths and bare URLs — the same way [isolateInlineCode] does for backtick spans.
     *
     * Wrapping them matters because a run such as `2026-11-20` or `www-data` between Arabic words
     * otherwise drags the punctuation and the words around it across the line. Markdown structure is left
     * alone: code spans (already isolated by [isolateInlineCode]), fenced and indented code, links and
     * images, autolinks and inline HTML. A bare URL becomes a link with the same text, since an autolink
     * would take the closing isolate into its address.
     */
    fun isolateLtrRuns(markdown: String): String = mapProseLines(markdown, transform = ::isolateRuns)

    /** One line's left-to-right runs, each isolated on its own. */
    private fun isolateRuns(line: String): String {
        if (line.none { it.isAsciiLetter() || it in '0'..'9' }) return line
        val out = StringBuilder(line.length + 32)
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                // A list marker starts the line: `1.`, `1)` or `12.`. CommonMark only reads an ordered
                // list when the digits are the first thing on the line, so this must be copied untouched.
                (c in '0'..'9' || c == '-' || c == '*' || c == '+') && startOfLine(line, i) -> {
                    val markerEnd = listMarkerEnd(line, i)
                    if (markerEnd > i) {
                        out.append(line, i, markerEnd)
                        i = markerEnd
                    } else {
                        out.append(c)
                        i++
                    }
                }
                // A code span: isolateInlineCode has already wrapped its content; copy it as it is.
                c == '`' -> i = copyCodeSpan(line, i, out)
                // An image or a link: `![math](herald-math:…)`, `[text](url)`. The alt text is matched
                // against patterns downstream, so neither side may be touched. A bracket that opens no
                // link — a task box, a note — is prose.
                c == '!' && linkEnd(line, i + 1) > 0 -> {
                    val end = linkEnd(line, i + 1)
                    out.append(line, i, end)
                    i = end
                }
                // The label of a reference link, `[text][1]`: it must match its `[1]: …` definition as written.
                c == '[' && line.getOrNull(i - 1) == ']' && line.indexOf(']', i + 1) > 0 -> {
                    val end = line.indexOf(']', i + 1) + 1
                    out.append(line, i, end)
                    i = end
                }
                // A task box `[ ]` / `[x]`: isolating the x would turn it into a link reference.
                c == '[' && line.getOrNull(i + 1)?.let { it in " xX" } == true && line.getOrNull(i + 2) == ']' -> {
                    out.append(line, i, i + 3)
                    i += 3
                }
                c == '[' && linkEnd(line, i) > 0 -> {
                    val end = linkEnd(line, i)
                    out.append(line, i, end)
                    i = end
                }
                // A link destination `](url)` whose `[` we already passed.
                c == ']' && line.getOrNull(i + 1) == '(' -> i = copyUntil(line, i, ')', out)
                // Autolinks and inline HTML are markup, not prose; `3 < 5` is not.
                c == '<' && line.getOrNull(i + 1).let { it != null && (it.isAsciiLetter() || it in "/!?") } ->
                    i = copyUntil(line, i, '>', out)
                urlAt(line, i) -> i = appendUrl(line, i, out)
                c.isAsciiLetter() || c in '0'..'9' || (c == '/' && line.getOrNull(i + 1)?.isAsciiLetter() == true) -> {
                    // One word, then any further words joined to it by single spaces: an English phrase
                    // inside an Arabic sentence is one run, or its words would come out in reverse.
                    var end = i
                    while (true) {
                        val tokenStart = end
                        // A URL glued on (`Docs:https://…`) ends the run here and is appended as a link.
                        while (end < line.length && line[end].isRunChar() && !(end > tokenStart && urlAt(line, end))) end++
                        if (end == tokenStart) break
                        var next = end
                        while (next < line.length && line[next] == ' ') next++
                        // A URL after the phrase is a run of its own, see appendUrl.
                        val continues = next > end && next < line.length && !urlAt(line, next) &&
                            (line[next].isAsciiLetter() || line[next] in '0'..'9' || (line[next] == '/' && line.getOrNull(next + 1)?.isAsciiLetter() == true))
                        if (!continues) break
                        end = next
                    }
                    // Sentence punctuation that happens to sit next to the run stays in the prose.
                    while (end > i + 1 && line[end - 1] in RUN_TRAILING) end--
                    val alreadyIsolated = line.getOrNull(i - 1) == LRI && line.getOrNull(end) == PDI
                    if (end > i && !alreadyIsolated) {
                        out.append(LRI).append(line, i, end).append(PDI)
                        i = end
                    } else if (end > i) {
                        out.append(line, i, end)
                        i = end
                    } else {
                        out.append(c)
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun copyCodeSpan(line: String, at: Int, out: StringBuilder): Int {
        var run = 0
        while (at + run < line.length && line[at + run] == '`') run++
        val ticks = "`".repeat(run)
        val close = line.indexOf(ticks, at + run)
        if (close < 0) {
            out.append(ticks)
            return at + run
        }
        out.append(ticks).append(line, at + run, close).append(ticks)
        return close + run
    }

    /**
     * The end of the link `[text](url)` or `[text](<url>)` whose `[` is at [at], or -1 when the bracket
     * opens no link. A destination may hold balanced brackets, as `…/wiki/Foo_(bar)` does.
     */
    private fun linkEnd(line: String, at: Int): Int {
        if (line.getOrNull(at) != '[') return -1
        val close = line.indexOf(']', at + 1)
        if (close < 0 || line.getOrNull(close + 1) != '(') return -1
        var j = close + 2
        if (line.getOrNull(j) == '<') {
            val gt = line.indexOf('>', j)
            if (gt < 0) return -1
            j = gt + 1
        }
        var depth = 0
        while (j < line.length) {
            when (line[j]) {
                '(' -> depth++
                ')' -> if (depth == 0) return j + 1 else depth--
            }
            j++
        }
        return -1
    }

    /** Whether a bare URL, which GFM turns into an autolink, starts at [at]. */
    private fun urlAt(line: String, at: Int): Boolean {
        if (at > 0 && (line[at - 1].isAsciiLetter() || line[at - 1] in '0'..'9')) return false
        return URL_STARTS.any { line.startsWith(it, at, ignoreCase = true) }
    }

    /**
     * Appends the bare URL at [at] as a link with the same text, isolated: `⁦[https://x](https://x)⁩`.
     * Isolating the bare URL instead would not work: an autolink runs to the next space, so it would take
     * the closing isolate into its address and open a broken page. Returns where the URL ends.
     */
    private fun appendUrl(line: String, at: Int, out: StringBuilder): Int {
        var end = at
        while (end < line.length && !line[end].isWhitespace() && line[end] != '<' && line[end] !in ISOLATES && !isRightToLeft(line[end])) end++
        // As GFM does: sentence punctuation after the address, and a `)` that closes none, are prose.
        while (end > at) {
            val last = line[end - 1]
            val unbalanced = last == ')' && line.substring(at, end).count { it == '(' } < line.substring(at, end).count { it == ')' }
            if (last in URL_TRAILING || unbalanced) end-- else break
        }
        val url = line.substring(at, end)
        if (url.any { it in "[]<>\\`*" } || url.count { it == '(' } != url.count { it == ')' }) {
            // Text the link would read as markup, or brackets that would end its address early: left as
            // the autolink it was.
            out.append(url)
            return end
        }
        // The renderer only takes the address of `(url)`; `(<url>)` parses as an autolink and opens nothing.
        val address = if (url.startsWith("www.", ignoreCase = true)) "http://$url" else url
        out.append(LRI).append('[').append(url).append("](").append(address).append(')').append(PDI)
        return end
    }

    private fun copyUntil(line: String, at: Int, closing: Char, out: StringBuilder): Int {
        val close = line.indexOf(closing, at + 1)
        if (close < 0) {
            out.append(line[at])
            return at + 1
        }
        out.append(line, at, close + 1)
        return close + 1
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

    /** Whether [at] is the first non-space character of the line; a nested item is indented further. */
    private fun startOfLine(line: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0 && line[i] == ' ') i--
        return i < 0 || line[i] == '\n'
    }

    /** End of a list marker starting at [at], or [at] when there is none. `1. `, `1) `, `- `, `* `, `+ `. */
    private fun listMarkerEnd(line: String, at: Int): Int {
        var end = at
        if (line[at] in '0'..'9') {
            while (end < line.length && line[end] in '0'..'9') end++
            if (end - at > 9) return at // CommonMark allows at most nine digits
            if (end >= line.length || line[end] !in ".)") return at
            end++
        } else {
            // A bullet: the marker is one character and needs a space after it.
            end = at + 1
        }
        if (end >= line.length || line[end] != ' ') return at
        return end + 1
    }

    /**
     * [text] without the isolate characters this file adds. The app's copy actions put a message's raw
     * text on the clipboard, but text selected on screen comes from the rendered string, so anything that
     * copies what the user sees runs it through here first.
     */
    fun stripIsolates(text: String): String =
        if (text.none { it in ISOLATES }) text else text.filterNot { it in ISOLATES }

    /** What a run may be made of: the characters of an identifier, a path, a URL, a date or an amount. */
    private fun Char.isRunChar(): Boolean = isAsciiLetter() || this in '0'..'9' || this in RUN_CHARS

    /**
     * Whether [text] should be laid out right to left: true when most of its words are Arabic or Hebrew,
     * false when they are Latin or when it has no words at all (digits, punctuation).
     *
     * Counting words rather than taking the first strong character matters for real replies: a sentence
     * such as "409 MB مكتوبة صراحةً…" would otherwise be laid out left to right because it opens with a
     * number and a unit, and its Arabic clauses would read in the wrong order. Every word counts: a reply
     * that greets in Arabic and then explains in English is an English reply.
     */
    fun prefersRightToLeft(text: CharSequence): Boolean = proseDirection(text) == true

    /**
     * True when most of the words in [markdown]'s prose are right to left, false when most are left to
     * right, null when it has none. Code blocks and code spans don't vote: an Arabic answer with a Kotlin
     * sample is still an Arabic answer.
     */
    fun proseDirection(markdown: CharSequence): Boolean? = wordDirection(prose(markdown.toString()))

    /**
     * True when most of [text]'s words are right to left, false when most are left to right, null when it
     * has none. Words, not letters: a URL, a path or an identifier in an Arabic sentence is one word, not
     * the dozens of Latin letters that would outvote the Arabic around it.
     */
    private fun wordDirection(text: CharSequence): Boolean? {
        var rtl = 0
        var ltr = 0
        var word = 0 // 1 for a right-to-left word, -1 for a left-to-right one, 0 until its first letter
        for (i in 0..text.length) {
            val c = if (i < text.length) text[i] else ' '
            if (c.isWhitespace() || c == LRI || c == PDI) {
                if (word > 0) rtl++ else if (word < 0) ltr++
                word = 0
            } else if (word == 0) {
                word = when {
                    isRightToLeft(c) -> 1
                    isLeftToRight(c) -> -1
                    else -> 0
                }
            }
        }
        return if (rtl + ltr == 0) null else rtl > ltr
    }

    private fun isRightToLeft(c: Char): Boolean = when (c) {
        in '\u0590'..'\u05FF', // Hebrew
        in '\u0600'..'\u06FF', // Arabic
        in '\u0700'..'\u074F', // Syriac
        in '\u0750'..'\u077F', // Arabic Supplement
        in '\u08A0'..'\u08FF', // Arabic Extended-A
        in '\uFB50'..'\uFDFF', // Arabic Presentation Forms-A
        in '\uFE70'..'\uFEFF', // Arabic Presentation Forms-B
        -> true
        else -> false
    }

    private fun isLeftToRight(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '\u00C0'..'\u024F' || c in '\u0370'..'\u058F'

    /** Inner characters of a run: what a path, URL, identifier, date, version or amount is made of. */
    private const val RUN_CHARS = "/._-:@#~+&=%\$\\"

    /** Punctuation that ends a sentence rather than a run, so it stays in the prose around it. */
    private const val RUN_TRAILING = "./:-_=+&?#~\\"

    /** A link reference definition, `[1]: https://…`: its address must not be touched. */
    private val LINK_DEFINITION = Regex("""^ {0,3}\[[^\]]+]:\s*\S.*""")

    /** A thematic break, `---` / `***` / `___`. */
    private val THEMATIC_BREAK = Regex("""^([-*_])( *\1){2,} *$""")

    /** An inline code span, any number of backticks. */
    private val CODE_SPAN = Regex("""(`+).*?\1""")

    /** How a bare URL starts, the ones GFM links on its own. */
    private val URL_STARTS = listOf("https://", "http://", "www.")

    /** Punctuation GFM leaves out of the end of an autolink. */
    private const val URL_TRAILING = "?!.,:*_~'\""

    /** The isolate characters: LRI, RLI, FSI, PDI. */
    private const val ISOLATES = "\u2066\u2067\u2068\u2069"
}

/**
 * [text] without the bidi isolate characters [Bidi] adds (U+2066…U+2069). What a user sees on screen
 * carries them; anything that hands that text to a shell, a clipboard or the gateway runs it through here.
 */
fun stripBidiIsolates(text: String): String = Bidi.stripIsolates(text)
