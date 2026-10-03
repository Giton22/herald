package dev.hermeskotlin.core.chat

/**
 * A picture or file a reply points at: a Markdown image (`![alt](src)`) or a `MEDIA:<path>` delivery
 * token. [source] is a gateway path (`/opt/data/...`), an `http(s)` URL or a `data:` URL.
 */
data class ReplyMedia(val source: String, val name: String, val isImage: Boolean) {
    /** The file lives on the gateway's disk, so it has to be fetched through the dashboard. */
    val onGateway: Boolean get() = source.startsWith("/") || source.startsWith("file://") || source.startsWith("~")

    /** [source] as a gateway path, without a `file://` scheme. */
    val gatewayPath: String get() = source.removePrefix("file://")
}

private val MARKDOWN_IMAGE = Regex("""!\[([^\]]*)]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)""")

/** Hermes' delivery directive; trailing sentence punctuation is not part of the path. */
private val MEDIA_TOKEN = Regex("""[ \t]*MEDIA:(\S+?)(?=[.,;:!?)\]]*(?:\s|$))""")

/** Desktop's inline widget directive, alone on its line; shown here as its file. */
private val PREVIEW_DIRECTIVE = Regex("""(?m)^[ \t]*::preview\{[^}]*?file="([^"]+)"[^}]*}[ \t]*$""")

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")

private fun isImagePath(source: String): Boolean =
    source.startsWith("data:image/") ||
        source.substringBefore('?').substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun nameOf(source: String, alt: String = ""): String =
    if (source.startsWith("data:")) alt.ifBlank { "image" } else source.substringBefore('?').substringAfterLast('/').ifBlank { alt.ifBlank { "file" } }

/**
 * Splits [text] into what to render as Markdown and the media it refers to. Every Markdown image is
 * taken out (a renderer can't load a gateway path anyway); `MEDIA:` tokens become media too, images
 * or files. Duplicates (the same path twice) are kept once.
 */
fun extractReplyMedia(text: String): Pair<String, List<ReplyMedia>> {
    if ("![" !in text && "MEDIA:" !in text && "::preview" !in text) return text to emptyList()
    val media = LinkedHashMap<String, ReplyMedia>()
    var cleaned = MARKDOWN_IMAGE.replace(text) { match ->
        val source = match.groupValues[2]
        media.getOrPut(source) { ReplyMedia(source, nameOf(source, match.groupValues[1]), isImage = true) }
        ""
    }
    cleaned = PREVIEW_DIRECTIVE.replace(cleaned) { match ->
        val source = match.groupValues[1]
        media.getOrPut(source) { ReplyMedia(source, nameOf(source), isImage = false) }
        ""
    }
    cleaned = MEDIA_TOKEN.replace(cleaned) { match ->
        val source = match.groupValues[1]
        media.getOrPut(source) { ReplyMedia(source, nameOf(source), isImagePath(source)) }
        ""
    }
    // Lines left empty by the removal shouldn't leave gaps in the reply.
    cleaned = cleaned.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
    return cleaned to media.values.toList()
}
