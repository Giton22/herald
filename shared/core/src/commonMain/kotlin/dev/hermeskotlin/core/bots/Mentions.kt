package dev.hermeskotlin.core.bots

/**
 * An `@word` being typed: where it starts and ends in the text (the whole word, also past the cursor, so a
 * pick replaces all of it), and what's typed after the `@` up to the cursor.
 */
data class MentionQuery(val start: Int, val end: Int, val query: String)

/**
 * The `@word` the cursor at [cursor] is in, when it's at the start of the text or after a space (so an
 * e-mail address doesn't count), or null.
 */
fun mentionQuery(text: String, cursor: Int): MentionQuery? {
    if (cursor !in 0..text.length) return null
    var start = cursor
    while (start > 0 && !text[start - 1].isWhitespace() && text[start - 1] != '@') start--
    if (start == 0 || text[start - 1] != '@') return null
    val at = start - 1
    if (at > 0 && !text[at - 1].isWhitespace()) return null
    val query = text.substring(start, cursor)
    if (query.any { !it.isHandleChar() }) return null
    var end = cursor
    while (end < text.length && text[end].isHandleChar()) end++
    return MentionQuery(at, end, query)
}

private fun Char.isHandleChar() = isLetterOrDigit() || this == '-' || this == '_'

/** The tag a bot answers to: `@hermes` for the primary bot, else its profile name (Bot Mode's handles). */
val Bot.handle: String get() = if (name == Bot.DEFAULT) "hermes" else name

/** Bots matching [query] by handle or name, best first; [self] (the bot whose chat this is) is left out. */
fun List<Bot>.mentionable(query: String, self: String?): List<Bot> {
    val q = query.lowercase()
    return filter { it.name != self && !it.meta.hidden }
        .filter { q.isEmpty() || it.handle.startsWith(q) || it.label.lowercase().split(' ').any { word -> word.startsWith(q) } || it.handle.contains(q) }
        .sortedWith(compareByDescending<Bot> { it.handle.startsWith(q) }.thenBy { it.label.lowercase() })
        .take(MAX_MENTIONS)
}

private const val MAX_MENTIONS = 6
