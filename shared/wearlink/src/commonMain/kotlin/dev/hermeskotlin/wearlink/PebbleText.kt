package dev.hermeskotlin.wearlink

/*
 * A Pebble counts text in UTF-8 bytes and draws it plain: these measure, cut and flatten a reply for it. Cuts fall
 * between characters, never inside one, so every piece is text of its own.
 */

/** [text]'s length in UTF-8 bytes. */
fun utf8Size(text: String): Int {
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        bytes += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                i++
                4
            }
            else -> 3
        }
        i++
    }
    return bytes
}

/** The most of [text], from char [from], that fits in [maxBytes] UTF-8 bytes, ending between two characters. */
fun takeUtf8(text: String, maxBytes: Int, from: Int = 0): String {
    var bytes = 0
    var i = from
    while (i < text.length) {
        val c = text[i]
        val pair = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
        val size = when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            pair -> 4
            else -> 3
        }
        if (bytes + size > maxBytes) break
        bytes += size
        i += if (pair) 2 else 1
    }
    return text.substring(from, i)
}

/** [text] cut to [maxBytes] UTF-8 bytes, at a word where it can be, with "..." where it was cut. */
fun clipUtf8(text: String, maxBytes: Int): String {
    if (utf8Size(text) <= maxBytes) return text
    val kept = takeUtf8(text, maxBytes - ELLIPSIS.length)
    val cut = kept.lastIndexOf(' ').takeIf { it > kept.length * 3 / 4 } ?: kept.length
    return kept.substring(0, cut).trimEnd() + ELLIPSIS
}

/** The char index where [text]'s first [bytes] UTF-8 bytes end; [bytes] must end between two characters. */
fun charIndexOfUtf8(text: String, bytes: Int): Int = takeUtf8(text, bytes).length

/** Pebble fonts have no "…"; three dots read the same. */
private const val ELLIPSIS = "..."

/**
 * A reply's Markdown as the watch shows it: plain lines, without the marks that only mean something rendered.
 * Code keeps its text, links keep their words, lists keep a dash.
 */
fun plainText(markdown: String): String {
    var fenced = false
    val lines = markdown.replace("\r\n", "\n").lines().mapNotNull { line ->
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> null.also { fenced = !fenced }
            // A code block's lines are kept as written: its stars, underscores and hashes are code.
            fenced -> line
            HEADING.matches(trimmed) -> plainInline(trimmed.replace(HEADING_MARK, ""))
            trimmed.startsWith(">") -> plainInline(trimmed.removePrefix(">").trimStart())
            RULE.matches(trimmed) -> ""
            else -> plainInline(line.replace(BULLET, "$1- "))
        }
    }
    return lines.joinToString("\n").replace(BLANK_LINES, "\n\n").trim()
}

/**
 * [line] without its inline marks, but for `code`, which keeps its text as written. Links and pictures go first, as
 * their words can be code too.
 */
private fun plainInline(line: String): String =
    line.replace(IMAGE, "$1").replace(LINK, "$1").split('`').mapIndexed { index, part ->
        if (index % 2 == 1) part else part.replace("**", "").replace("__", "").replace(EMPHASIS, "$1")
    }.joinToString("")

private val HEADING = Regex("^#{1,6}\\s.*")
private val HEADING_MARK = Regex("^#{1,6}\\s+")
private val RULE = Regex("^([-*_])(\\s*\\1){2,}\\s*$")
private val BULLET = Regex("^(\\s*)[*+]\\s")
private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
private val EMPHASIS = Regex("(?<![\\w*])\\*(\\S[^*\\n]*?)\\*(?![\\w*])")
private val BLANK_LINES = Regex("\\n{3,}")
