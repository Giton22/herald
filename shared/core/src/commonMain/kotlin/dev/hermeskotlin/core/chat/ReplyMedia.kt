package dev.hermeskotlin.core.chat

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
) {
    /** The file lives on the gateway's disk, so it has to be fetched through the dashboard. */
    val onGateway: Boolean get() = source.startsWith("/") || source.startsWith("file://") || source.startsWith("~")

    /** [source] as a gateway path, without a `file://` scheme. */
    val gatewayPath: String get() = source.removePrefix("file://")
}

/**
 * The address may hold one level of balanced brackets, as Wikipedia's `Foo_(bar)` pages do; failing
 * that, it runs to the first `)`, so a lone `(` (`a(1.png`) still makes an image.
 */
private val MARKDOWN_IMAGE =
    Regex("""!\[([^\]]*)\]\(\s*<?((?:[^\(\)\s<>]|\([^\(\)\s]*\))+|[^\)\s>]+)>?(?:\s+"[^"]*")?\s*\)""")

/**
 * Hermes' delivery directive, as a word of its own (not the end of `MULTIMEDIA:`); trailing sentence
 * punctuation is not part of the path.
 */
private val MEDIA_TOKEN = Regex("""[ \t]*(?<![A-Za-z0-9_])MEDIA:(\S+?)(?=[.,;:!?)\]]*(?:\s|$))""")

/**
 * Fenced code blocks (one still streaming runs to the end) and code spans. What's in them is a sample,
 * shown as typed: the gateway delivers no `MEDIA:` path from code either (`gateway/platforms/base.py`
 * `_code_spans`).
 *
 * As in CommonMark: a fence opens at the start of a line and closes on a line of its own with at least
 * as many of the same marks, so a ```` fence can show a ``` one; a ``` mid-sentence opens nothing. A
 * span closes on the same number of backticks and stops at a blank line.
 */
private val CODE = Regex(
    """(?m)^[ \t]*((`|~)\2{2,})[^`\n]*\n[\s\S]*?(?:^[ \t]*\1\2*[ \t]*$|\z)""" +
        """|(?<!`)(`+)(?!`)(?:[^\n]|\n(?![ \t]*\n))+?(?<!`)\3(?!`)""",
)

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
    var cleaned = MARKDOWN_IMAGE.replaceOutsideCode(text) { match ->
        val source = match.groupValues[2]
        media.getOrPut(source) { ReplyMedia(source, nameOf(source, match.groupValues[1]), isImage = true) }
        ""
    }
    cleaned = PREVIEW_DIRECTIVE.replaceOutsideCode(cleaned) { match ->
        val source = match.groupValues[1]
        media.getOrPut(source) { ReplyMedia(source, nameOf(source), isImage = false) }
        ""
    }
    cleaned = MEDIA_TOKEN.replaceOutsideCode(cleaned) { match ->
        val source = match.groupValues[1]
        media.getOrPut(source) { ReplyMedia(source, nameOf(source), isImagePath(source)) }
        ""
    }
    // A bare path, where it stands alone: not inside a link or code, as Desktop does it.
    echoes.forEach { echo ->
        cleaned = Regex("""(^|[\s\[\{])<?${Regex.escape(echo)}>?(?=[\]\}.,!?]*(?:\s|$))""", RegexOption.MULTILINE).replaceOutsideCode(cleaned) { match ->
            // The space before it goes too, so no gap is left before the sentence's full stop.
            match.groupValues[1].takeUnless { it == " " || it == "\t" }.orEmpty()
        }
    }
    // Lines left empty by the removal shouldn't leave gaps in the reply; code keeps its blank lines.
    cleaned = TRAILING_SPACE.replaceOutsideCode(cleaned) { "" }
    cleaned = BLANK_LINES.replaceOutsideCode(cleaned) { "\n\n" }.trim()
    return cleaned to media.values.filterNot { it.gatewayPath in echoes || it.source in echoes }
}

/**
 * [text] with each match that starts outside its [CODE] replaced by [transform]; code stays as it was.
 * The match is made on the whole text, so `^`, `$` and lookarounds see real lines and words, and an
 * image whose alt text holds a code span is still one image.
 */
private fun Regex.replaceOutsideCode(text: String, transform: (MatchResult) -> CharSequence): String {
    val code = CODE.findAll(text).map { it.range }.toList()
    return replace(text) { match -> if (code.any { match.range.first in it }) match.value else transform(match) }
}
