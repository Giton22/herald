package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** What a tool call was given and what it gave back, readable on a phone. */
internal object ToolDetails {

    private val pretty = Json { prettyPrint = true }

    /** The call's arguments: a lone text argument (a command, a query, a path) as itself, else indented JSON. */
    fun input(args: JsonElement?, argsText: String? = null): String? {
        argsText?.trim()?.takeIf { it.isNotEmpty() }?.let { return clip(it) }
        val obj = args as? JsonObject ?: return (args as? JsonPrimitive)?.contentOrNull?.let { fromJsonText(it) { parsed -> input(parsed) } }
        if (obj.isEmpty()) return null
        val single = obj.values.singleOrNull() as? JsonPrimitive
        if (single != null && single.isString) return clip(single.content)
        return clip(pretty.encodeToString(JsonElement.serializer(), obj))
    }

    /**
     * The result: the terminal's output or a result's main text when there is one, else indented JSON.
     * A stored tool row is JSON text, so text is parsed first.
     */
    fun output(result: JsonElement?, resultText: String? = null): String? {
        resultText?.trim()?.takeIf { it.isNotEmpty() }?.let { return clip(it) }
        return when (result) {
            null, JsonNull -> null
            is JsonPrimitive -> result.contentOrNull?.let { fromJsonText(it) { parsed -> output(parsed) } }
            is JsonObject -> MAIN_TEXT
                .firstNotNullOfOrNull { key -> (result[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() } }
                ?.let { text ->
                    // Keep an error or exit code next to the output they explain.
                    val code = (result["exit_code"] as? JsonPrimitive)?.intOrNull?.takeIf { it != 0 }
                    clip(if (code != null) "$text\n\nExit code $code" else text)
                }
                ?: clip(pretty.encodeToString(JsonElement.serializer(), result))
            else -> clip(pretty.encodeToString(JsonElement.serializer(), result))
        }
    }

    /** A result that reports a failure: an error, `success: false` or a non-zero exit code. */
    fun failed(result: JsonElement?): Boolean {
        val obj = when (result) {
            is JsonObject -> result
            is JsonPrimitive -> result.contentOrNull?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
            else -> null
        } ?: return false
        val error = obj["error"]
        if (error != null && error != JsonNull && (error as? JsonPrimitive)?.contentOrNull?.isNotBlank() != false) return true
        if ((obj["success"] as? JsonPrimitive)?.booleanOrNull == false) return true
        return ((obj["exit_code"] as? JsonPrimitive)?.intOrNull ?: 0) != 0
    }

    /** JSON text through [format]; anything else as it is. */
    private fun fromJsonText(text: String, format: (JsonElement) -> String?): String? {
        val trimmed = text.trim().takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.first() == '{' || trimmed.first() == '[') {
            runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()?.let { parsed ->
                if (parsed !is JsonPrimitive) return format(parsed)
            }
        }
        return clip(trimmed)
    }

    /** Huge outputs would stall the list; the agent has the rest. */
    private fun clip(text: String): String =
        if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS).trimEnd() + "\n… (${text.length - MAX_CHARS} more characters)"

    private val MAIN_TEXT = listOf("output", "content", "result", "text", "stdout", "message", "error")

    private const val MAX_CHARS = 8_000
}
