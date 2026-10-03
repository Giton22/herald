package dev.hermeskotlin.core.chat

/*
 * A `/skill` turn is stored as a model-facing message that embeds the whole skill body. Bubbles show
 * the invocation instead (`/work fix the leak`), like Desktop's skillInvocationText; the markers match
 * agent/skill_commands.py.
 */

private const val INVOCATION_PREFIX = "[IMPORTANT: The user has invoked the "
private const val SINGLE_MARKER = "The full skill content is loaded below.]"
private const val SINGLE_INSTRUCTION = "The user has provided the following instruction alongside the skill invocation: "
private const val RUNTIME_NOTE = "\n\n[Runtime note:"
private const val BUNDLE_MARKER = " skill bundle,"
private const val BUNDLE_INSTRUCTION = "\nUser instruction: "
private const val BUNDLE_SKILL_BLOCK = "\n\n[Loaded as part of the "

/** The invocation a skill turn came from, or null when [text] is ordinary prose. */
fun skillInvocationText(text: String): String? {
    if (!text.startsWith(INVOCATION_PREFIX)) return null
    val name = text.removePrefix(INVOCATION_PREFIX).takeIf { it.startsWith('"') }
        ?.drop(1)?.substringBefore('"', missingDelimiterValue = "")?.trim()
    if (name.isNullOrEmpty()) return null
    val label = if (name.startsWith("/")) name else "/$name"
    val instruction = when {
        BUNDLE_MARKER in text -> text.between(BUNDLE_INSTRUCTION, BUNDLE_SKILL_BLOCK, fromEnd = false)
        // The instruction trails the body, which may quote the marker, so take the last one.
        SINGLE_MARKER in text -> text.between(SINGLE_INSTRUCTION, RUNTIME_NOTE, fromEnd = true)
        else -> ""
    }
    return if (instruction.isEmpty()) label else "$label ${instruction.replace(Regex("""\s+"""), " ")}"
}

private fun String.between(marker: String, end: String, fromEnd: Boolean): String {
    val index = if (fromEnd) lastIndexOf(marker) else indexOf(marker)
    if (index < 0) return ""
    val tail = substring(index + marker.length)
    val stop = tail.indexOf(end)
    return (if (stop >= 0) tail.substring(0, stop) else tail).trim()
}
