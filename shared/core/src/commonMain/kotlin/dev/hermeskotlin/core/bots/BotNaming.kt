package dev.hermeskotlin.core.bots

/** A letter or digit with its accents taken off (`é` → `e`), or as it was when it has none to lose. */
internal expect fun stripAccents(ch: String): String

/** NFC, so a letter typed as base + combining mark is one character. */
internal expect fun composeNfc(text: String): String

/** What the Hermes CLI accepts as a profile id (hermes_cli/profiles.py `_PROFILE_ID_RE`). */
val PROFILE_ID = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

/**
 * The profile id for a bot named [name], Desktop's `slugifyProfileName` (labels.ts): accented Latin folds
 * to its base letters (`Résumé` → `resume`); any other letter or digit becomes a `u<hex>` token so a CJK
 * name still makes an id (`小助手` → `u5c0f-u52a9-u624b`); everything else separates. At most 64
 * characters, cut at a token boundary so no `u<hex>` is split.
 */
fun slugifyProfileName(name: String): String {
    val folded = buildString {
        var i = 0
        val text = composeNfc(name)
        while (i < text.length) {
            val cp = text.codePointAtCompat(i)
            val ch = text.substring(i, i + charCount(cp))
            i += ch.length
            if (!ch.isLetterOrDigitCompat()) {
                append(ch)
                continue
            }
            val base = stripAccents(ch)
            if (base.isNotEmpty() && base.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) append(base) else append("-u${cp.toString(16)}-")
        }
    }
    val slug = folded.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").replace(Regex("-+"), "-").trim('-')
    if (slug.length <= 64) return slug
    val cut = slug.take(64)
    val boundary = if (slug[64] == '-') 64 else cut.lastIndexOf('-')
    return (if (boundary > 0) cut.take(boundary) else cut).trimEnd('-')
}

/**
 * Splits what was typed as a bot's name into its profile id and title, as Desktop does: a name that
 * can't be an id as typed (non-ASCII) is kept as the title when no title was given.
 */
fun botIdentity(name: String, title: String): Pair<String, String> {
    val entered = name.trim()
    val slug = slugifyProfileName(entered)
    val shownTitle = title.trim().ifEmpty { if (entered.any { it.code !in 0x20..0x7e }) entered else "" }
    return slug to shownTitle
}

/**
 * The persona a new bot starts with when no SOUL is written for it (Desktop's soul.ts). The messaging
 * protocol isn't added: a gateway that reports `bot_mode_protocol` teaches every Bot Chat itself.
 */
fun composeSoul(displayName: String, profile: String, title: String, description: String): String = buildString {
    appendLine("# $displayName")
    appendLine()
    if (title.isNotBlank()) appendLine("**Role:** ${title.trim()}")
    if (description.isNotBlank()) appendLine("**Mission:** ${description.trim()}")
    if (title.isNotBlank() || description.isNotBlank()) appendLine()
    appendLine("You are $displayName, a persistent named agent (profile `$profile`) on this machine.")
    append("You keep your own memory, skills, and conversation history across sessions.")
}

/** The name a bot without a title goes by: its id with dashes as spaces, words capitalised. */
fun displayNameFor(profile: String, title: String): String =
    title.trim().ifEmpty { profile.replace(Regex("[-_]+"), " ").trim().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } } }

private fun String.codePointAtCompat(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
        return ((high.code - 0xD800) shl 10) + (this[index + 1].code - 0xDC00) + 0x10000
    }
    return high.code
}

private fun charCount(codePoint: Int) = if (codePoint >= 0x10000) 2 else 1

private fun String.isLetterOrDigitCompat(): Boolean = length == 2 || this[0].isLetterOrDigit()
