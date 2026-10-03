package dev.hermeskotlin.core.models

/**
 * Human model names from gateway ids, after Hermes Desktop's `formatModelPillLabel`:
 * `openai/gpt-6-sol` → "GPT-6-sol", `claude-opus-5-5` → "Opus 5.5", `glm-5.2` → "GLM 5.2".
 */
fun displayModelName(id: String): String {
    val base = id.trim().substringAfterLast('/')
    if (base.isEmpty()) return id
    return when {
        base.startsWith("claude-", ignoreCase = true) ->
            base.drop(7).replace(Regex("""(\d)-(?=\d)"""), "$1.").replace('-', ' ').titleCase().vendorCasing()
        base.startsWith("gpt-", ignoreCase = true) -> "GPT-" + base.drop(4)
        else -> base.replace('-', ' ').titleCase().vendorCasing()
    }
}

/** Desktop's provider labels where the slug reads badly. */
fun displayProviderName(slug: String, fallback: String? = null): String =
    PROVIDER_NAMES[slug.trim().lowercase()] ?: fallback?.takeIf { it.isNotBlank() } ?: slug

private val PROVIDER_NAMES = mapOf(
    "anthropic" to "Anthropic",
    "nous" to "Nous Portal",
    "openai-codex" to "ChatGPT subscription",
    "qwen-oauth" to "Qwen Code",
    "xai" to "xAI",
    "xai-oauth" to "xAI Grok",
)

private fun String.titleCase(): String = Regex("""\b[a-z]""").replace(this) { it.value.uppercase() }.trim()

private val VENDOR_CASING = listOf(
    "Deepseek" to "DeepSeek", "Glm" to "GLM", "Minimax" to "MiniMax", "Openai" to "OpenAI",
    "Ernie" to "ERNIE", "Mimo" to "MiMo", "Vl" to "VL", "Ai" to "AI",
)

private fun String.vendorCasing(): String {
    var cased = Regex("""\b([aA]?)(\d+(?:\.\d+)?)[bB]\b""").replace(this) { "${it.groupValues[1].uppercase()}${it.groupValues[2]}B" }
    for ((from, to) in VENDOR_CASING) cased = Regex("""\b""" + from + """\b""").replace(cased, to)
    return cased
}
