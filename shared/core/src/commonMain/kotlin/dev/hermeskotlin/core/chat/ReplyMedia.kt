package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.media.GatewayFile

/**
 * A picture or file a reply points at: a Markdown image (`![alt](src)`) or a `MEDIA:<path>` delivery
 * token. [source] is a gateway path (`/opt/data/...`), an `http(s)` URL or a `data:` URL.
 */
data class ReplyMedia(
    val source: String,
    val name: String,
    val isImage: Boolean,
    /** Made by `image_generate`: its address came from the tool, not from text the model wrote. */
    val generated: Boolean = false,
    /** An explicit inline widget directive, rather than an ordinary delivered file. */
    val preview: Boolean = false,
) {
    /** The file lives on the gateway's disk, so it has to be fetched through the dashboard. */
    val onGateway: Boolean get() = source.startsWith("/") || source.startsWith("file://") || source.startsWith("~")

    /** [source] as a gateway path, without a `file://` scheme. */
    val gatewayPath: String get() = source.removePrefix("file://")
}

/**
 * The address may hold one level of balanced brackets, as Wikipedia's `Foo_(bar)` pages do; failing
 * that, it runs to the first `)`, so a lone `(` (`a(1.png`) still makes an image. The alt text stays on
 * its line. Runs of plain characters are single loops, so a long `data:` address can't overflow the
 * matcher's stack.
 */
private val MARKDOWN_IMAGE = Regex(
    """!\[([^\]\n]*)\]\(\s*<?((?=[^\s\)>])[^\(\)\s<>]*(?:\([^\(\)\s]*\)[^\(\)\s<>]*)*|[^\)\s>]+)>?(?:\s+"[^"]*")?\s*\)""",
)

/**
 * Hermes' delivery directive, as a word of its own (not the end of `MULTIMEDIA:`); trailing sentence
 * punctuation is not part of the path, nor is code written against it (blanked out as [CODE_MASK]).
 */
private val MEDIA_TOKEN = Regex("""[ \t]*(?<![A-Za-z0-9_])MEDIA:([^\s]+?)(?=[.,;:!?)\]]*(?:\s|$|))""")

/**
 * A fence's opening line: the list or quote markers it sits in, the run of marks, then the info string.
 * The markers' width is how far the item's lines are indented.
 */
private val FENCE_OPEN = Regex("""^((?:[ \t]*(?:[-*+]|\d{1,9}[.)])[ \t]+|[ \t]*>[ \t]?)*+)[ \t]*(`{3,}|~{3,})(.*)$""")

/** A fence's closing line: only marks, after any indent or quote markers. */
private val FENCE_CLOSE = Regex("""^[ \t>]*(`{3,}|~{3,})[ \t]*$""")

private val BACKTICKS = Regex("`+")

/**
 * Where a paragraph ends: a blank line, or a line that starts a heading, list item or quote. Of numbered
 * items only a "1." breaks into a paragraph, as in CommonMark.
 */
private val PARAGRAPH_END = Regex("""\n(?=[ \t]*(?:\n|#{1,6}[ \t]|[-*+][ \t]|1[.)][ \t]|>))""")

/** A heading is a paragraph of its own line. */
private val HEADING = Regex("""(?m)^[ \t]*#{1,6}[ \t][^\n]*""")

private val QUOTED = Regex("""^[ \t]*>""")

/** What code is blanked out with while the patterns run; nothing matches it but "any character". */
private const val CODE_MASK = ''

/** Desktop's inline widget directive, alone on its line; shown here as its file. */
// Braces and brackets are escaped everywhere: Android's ICU regex rejects a bare `}` that the JVM allows.
private val PREVIEW_DIRECTIVE = Regex("""(?m)^[ \t]*::preview\{[^\}]*?file="([^"]+)"[^\}]*\}[ \t]*$""")

private val TRAILING_SPACE = Regex("""(?m)[ \t]+$""")

private val BLANK_LINES = Regex("""\n{3,}""")

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")

private fun isImagePath(source: String): Boolean =
    source.startsWith("data:image/") ||
        source.substringBefore('?').substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun nameOf(source: String, alt: String = ""): String =
    if (source.startsWith("data:")) alt.ifBlank { "image" } else source.substringBefore('?').substringAfterLast('/').ifBlank { alt.ifBlank { "file" } }

/** A generated picture, shown like one the reply delivered. */
fun GeneratedImage.asMedia(): ReplyMedia = ReplyMedia(source, nameOf(source), isImage = true, generated = true)

/**
 * Splits [text] into what to render as Markdown and the media it refers to. Every Markdown image is
 * taken out (a renderer can't load a gateway path anyway); `MEDIA:` tokens become media too, images
 * or files. Duplicates (the same path twice) are kept once.
 *
 * The [generated] pictures show on their own, so the text's mentions of them go (as images, `MEDIA:`
 * tokens or bare paths) and aren't loaded again: the model often repeats the sandbox's path, which the
 * gateway can't serve.
 */
fun extractReplyMedia(text: String, generated: List<GeneratedImage> = emptyList()): Pair<String, List<ReplyMedia>> {
    val echoes = generated.flatMap { it.echoes }.toSet()
    if ("![" !in text && "MEDIA:" !in text && "::preview" !in text && echoes.none { it in text }) return text to emptyList()
    val media = LinkedHashMap<String, ReplyMedia>()
    var cleaned = MARKDOWN_IMAGE.replaceOutsideCode(text.replace("\r\n", "\n")) { groups ->
        val source = groups[2]
        media.getOrPut(source) { ReplyMedia(source, nameOf(source, groups[1]), isImage = true) }
        ""
    }
    cleaned = PREVIEW_DIRECTIVE.replaceOutsideCode(cleaned) { groups ->
        val source = groups[1]
        val previous = media[source]
        val html = GatewayFile.fromLink(source)?.extension in setOf("html", "htm")
        media[source] = previous?.copy(isImage = previous.isImage && !html, preview = true)
            ?: ReplyMedia(source, nameOf(source), isImage = false, preview = true)
        ""
    }
    cleaned = MEDIA_TOKEN.replaceOutsideCode(cleaned) { groups ->
        val source = groups[1]
        media.getOrPut(source) { ReplyMedia(source, nameOf(source), isImagePath(source)) }
        ""
    }
    // A bare path, where it stands alone: not inside a link or code, as Desktop does it.
    echoes.forEach { echo ->
        cleaned = Regex("""(^|[\s\[\{])<?${Regex.escape(echo)}>?(?=[\]\}.,!?]*(?:\s|$))""", RegexOption.MULTILINE).replaceOutsideCode(cleaned) { groups ->
            // The space before it goes too, so no gap is left before the sentence's full stop.
            groups[1].takeUnless { it == " " || it == "\t" }.orEmpty()
        }
    }
    // Lines left empty by the removal shouldn't leave gaps in the reply; code keeps its blank lines.
    cleaned = TRAILING_SPACE.replaceOutsideCode(cleaned) { "" }
    cleaned = BLANK_LINES.replaceOutsideCode(cleaned) { "\n\n" }.trim()
    return cleaned to media.values.filterNot { it.gatewayPath in echoes || it.source in echoes }
}

/**
 * A link already written (group 1: a `[label](dest)` link or a `<...>` autolink), or else a bare `file://`
 * URI in prose (not inside a web address), trailing sentence punctuation left out of it.
 */
private val BARE_FILE_URI = Regex(
    """(\[[^\]\n]*]\([^)\n]*\)|<[^<>\s]+>)|(?<![<(\[\w/=#&?])file://[^\s<>()\[\]`]+?(?=[.,;:!?'"]*(?:\s|$))""",
)

/**
 * [text] with each bare `file://` URI outside code made a Markdown autolink (`<file:///x>`), so a tap
 * can open it like a `[name](file:///x)` link; Markdown only links web addresses written bare. One in a
 * link's label or in an autolink stays as it is: a link can't hold another.
 */
fun linkFileUris(text: String): String {
    if ("file://" !in text) return text
    return BARE_FILE_URI.replaceOutsideCode(text) { groups -> if (groups[1].isNotEmpty()) groups[0] else "<${groups[0]}>" }
}

/**
 * [text] with each match replaced by [transform] of its groups, its code left as it was. The pattern runs
 * on the whole text with the code blanked out: `^`, `$` and lookarounds see real lines and words, nothing
 * starts in a sample or runs into one, and an image whose alt text holds a code span is still one image.
 */
private fun Regex.replaceOutsideCode(text: String, transform: (List<String>) -> String): String {
    val masked = maskCode(text)
    val out = StringBuilder(text.length)
    var from = 0
    findAll(masked).forEach { match ->
        out.append(text, from, match.range.first)
        out.append(transform(match.groups.map { group -> group?.range?.let(text::substring).orEmpty() }))
        from = match.range.last + 1
    }
    return out.append(text, from, text.length).toString()
}

/** [text] with every character of its code, line breaks too, replaced by [CODE_MASK]. */
private fun maskCode(text: String): String {
    val code = codeRanges(text)
    if (code.isEmpty()) return text
    val chars = text.toCharArray()
    code.forEach { range -> range.forEach { chars[it] = CODE_MASK } }
    return chars.concatToString()
}

/**
 * Fenced code blocks (one still streaming runs to the end) and code spans. What's in them is a sample,
 * shown as typed: the gateway delivers no `MEDIA:` path from code either (`gateway/platforms/base.py`
 * `_code_spans`).
 *
 * As in CommonMark: a fence opens at the start of a line, after any list or quote markers, and closes on
 * a line of its own with at least as many of the same marks, so a ```` fence can show a ``` one; a ```
 * mid-sentence opens nothing. A fence in a quote or list item ends with it too, closed or not. Spans are
 * looked for only outside fences.
 */
private fun codeRanges(text: String): List<IntRange> {
    val code = ArrayList<IntRange>()
    var fence: String? = null
    var quoted = false
    var indent = 0
    var fenceStart = 0
    var prose = 0
    var lineStart = 0
    while (lineStart <= text.length) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val containerEnds = if (quoted) {
            !QUOTED.containsMatchIn(line)
        } else {
            line.isNotBlank() && columnOf(line.takeWhile { it == ' ' || it == '\t' }) < indent
        }
        if (fence != null && containerEnds) {
            code += fenceStart until lineStart - 1
            fence = null
            prose = lineStart - 1
        }
        if (fence == null) {
            val open = FENCE_OPEN.find(line)
            // A ``` fence's info string holds no backtick: "```ls```" is a span.
            if (open != null && !(open.groupValues[2][0] == '`' && '`' in open.groupValues[3])) {
                addSpans(text, prose, lineStart, code)
                fence = open.groupValues[2]
                quoted = '>' in open.groupValues[1]
                indent = if (quoted) 0 else columnOf(open.groupValues[1])
                fenceStart = lineStart
            }
        } else {
            val marks = FENCE_CLOSE.find(line)?.groupValues?.get(1)
            if (marks != null && marks[0] == fence[0] && marks.length >= fence.length) {
                code += fenceStart until lineEnd
                fence = null
                prose = lineEnd
            }
        }
        lineStart = lineEnd + 1
    }
    if (fence != null) code += fenceStart until text.length else addSpans(text, prose, text.length, code)
    return code
}

/** The column after [prefix], a tab moving to the next multiple of 4, as in CommonMark. */
private fun columnOf(prefix: String): Int = prefix.fold(0) { column, c -> if (c == '\t') column + 4 - column % 4 else column + 1 }

/** The code spans of [text] from [from] to [to]: a run of backticks to the next run as long, in one paragraph. */
private fun addSpans(text: String, from: Int, to: Int, code: MutableList<IntRange>) {
    val part = text.substring(from, to)
    if ('`' !in part) return
    val runs = BACKTICKS.findAll(part).map { it.range }.toList()
    val breaks = (PARAGRAPH_END.findAll(part).map { it.range.first } + HEADING.findAll(part).map { it.range.last + 1 })
        .sorted().toList()
    var nextBreak = 0
    var i = 0
    while (i < runs.size) {
        val open = runs[i]
        while (nextBreak < breaks.size && breaks[nextBreak] < open.last) nextBreak++
        val paragraphEnd = breaks.getOrElse(nextBreak) { part.length }
        val close = (i + 1 until runs.size).asSequence()
            .takeWhile { runs[it].first < paragraphEnd }
            .firstOrNull { runs[it].last - runs[it].first == open.last - open.first }
        if (close == null) {
            i++
        } else {
            code += from + open.first..from + runs[close].last
            i = close + 1
        }
    }
}
