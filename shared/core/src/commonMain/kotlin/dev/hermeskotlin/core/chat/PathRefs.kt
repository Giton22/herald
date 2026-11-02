package dev.hermeskotlin.core.chat

/** The context references the gateway expands in a prompt that point at the agent's files. */
enum class PathRefKind(val prefix: String) {
    File("@file:"),
    Folder("@folder:"),
}

/**
 * An `@file:` or `@folder:` reference being typed: where it starts and ends in the text (the whole word, also
 * past the cursor, so a pick replaces all of it), and the path typed after the prefix up to the cursor.
 */
data class PathRefQuery(val start: Int, val end: Int, val kind: PathRefKind, val path: String) {
    /** The folder the path is in, as typed (`src/`, `~/`, or empty for where the agent works). */
    val folder: String get() = path.substringBeforeLast('/', "").let { if (it.isEmpty() && !path.startsWith("/")) "" else "$it/" }
}

/**
 * The `@file:…` or `@folder:…` word the cursor at [cursor] is in, when it's at the start of the text or after
 * whitespace; null otherwise, and for a quoted path (one with spaces), which completion leaves alone.
 */
fun pathRefQuery(text: String, cursor: Int): PathRefQuery? {
    if (cursor !in 0..text.length) return null
    var start = cursor
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    val word = text.substring(start, cursor)
    val kind = PathRefKind.entries.firstOrNull { word.startsWith(it.prefix) } ?: return null
    val path = word.removePrefix(kind.prefix)
    if (path.startsWith('"')) return null
    var end = cursor
    while (end < text.length && !text[end].isWhitespace()) end++
    return PathRefQuery(start, end, kind, path)
}

/**
 * What a pick of [entry] puts in place of this reference: the full reference, and a space after a file. A folder
 * stays open to go on into, as a shell's completion does; a space ends it.
 */
fun PathRefQuery.completion(entry: String, dir: Boolean): String =
    kind.prefix + folder + entry + if (dir) "/" else " "
