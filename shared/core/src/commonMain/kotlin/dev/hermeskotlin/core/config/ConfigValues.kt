package dev.hermeskotlin.core.config

import dev.hermeskotlin.core.network.HermesJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** What a setting's row says it's set to. The app words the ones that aren't the value's own text. */
sealed interface ConfigSummary {
    /** The value as it is: its text, or its list joined. */
    data class Text(val text: String) : ConfigSummary

    data object NotSet : ConfigSummary

    /** An empty list or map. */
    data object None : ConfigSummary
    data object On : ConfigSummary
    data object Off : ConfigSummary

    /** A map of [count] entries. */
    data class Entries(val count: Int) : ConfigSummary
}

/** What a setting's row says it's set to: its text, its list joined, "Not set" for nothing. */
fun ConfigEntry.summary(): ConfigSummary {
    val value = value
    return when {
        value == null || value is JsonNull -> ConfigSummary.NotSet
        control == ConfigControl.Fallbacks -> joined(fallbackLines(value))
        value is JsonArray -> joined(value.map { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() })
        value is JsonObject -> if (value.isEmpty()) ConfigSummary.None else ConfigSummary.Entries(value.size)
        value is JsonPrimitive && value.booleanOrNull != null -> if (value.booleanOrNull == true) ConfigSummary.On else ConfigSummary.Off
        value is JsonPrimitive -> if (value.content.isEmpty()) ConfigSummary.NotSet else ConfigSummary.Text(value.content)
        else -> ConfigSummary.Text(value.toString())
    }
}

private fun joined(items: List<String>): ConfigSummary =
    items.joinToString(", ").let { if (it.isEmpty()) ConfigSummary.None else ConfigSummary.Text(it) }

/** The text a setting's editor opens with: one item per line for lists, indented JSON for anything structured. */
fun ConfigEntry.editText(): String {
    val value = value
    return when {
        value == null || value is JsonNull -> ""
        control == ConfigControl.Fallbacks -> fallbackLines(value).joinToString("\n")
        control == ConfigControl.Lines -> (value as? JsonArray)?.joinToString("\n") { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }.orEmpty()
        control == ConfigControl.Json -> prettyJson.encodeToString(JsonElement.serializer(), value)
        value is JsonPrimitive -> value.content
        else -> value.toString()
    }
}

/**
 * Reads what was typed into [entry]'s editor as the value to save, or says why it can't be: a number that
 * isn't one, a fallback line without its `provider:`, JSON that doesn't parse.
 */
fun parseConfigInput(entry: ConfigEntry, text: String): Result<JsonElement> = runCatching {
    when (entry.control) {
        ConfigControl.Number -> {
            val trimmed = text.trim()
            // Not 0, which is a speed or timeout of its own, and not null over a value: Hermes would read None, not its default.
            if (trimmed.isEmpty()) {
                if (entry.value == null || entry.value is JsonNull) JsonNull else throw IllegalArgumentException("Type a number.")
            }
            else trimmed.toLongOrNull()?.let(::JsonPrimitive)
                ?: trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::JsonPrimitive)
                ?: throw IllegalArgumentException("“$trimmed” isn't a number.")
        }
        ConfigControl.Lines -> JsonArray(lines(text).map(::JsonPrimitive))
        ConfigControl.Fallbacks -> {
            val unused = (entry.value as? JsonArray).orEmpty().toMutableList()
            JsonArray(lines(text).map { fallbackEntry(it, unused) })
        }
        ConfigControl.Json -> try {
            Json.parseToJsonElement(text.ifBlank { "null" })
        } catch (e: Exception) {
            throw IllegalArgumentException("That isn't valid JSON: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
        }
        // Text, and a name typed for a choice that takes one off the list.
        else -> JsonPrimitive(text.trim())
    }
}

/** True when [next] would empty a toolset list that had some: the agent would lose memory, the terminal, search and the rest. */
fun clearsToolsets(entry: ConfigEntry, next: JsonElement): Boolean =
    entry.key == "toolsets" && (entry.value as? JsonArray)?.isNotEmpty() == true && (next as? JsonArray)?.isEmpty() == true

/** Two values the gateway would store the same way: `30` and `30.0` are one number. */
fun sameConfigValue(a: JsonElement?, b: JsonElement?): Boolean {
    if (a == b) return true
    val x = a as? JsonPrimitive ?: return false
    val y = b as? JsonPrimitive ?: return false
    if (x.isString || y.isString) return false
    val n = x.doubleOrNull ?: return false
    return n == y.doubleOrNull
}

private fun lines(text: String): List<String> = text.lines().map(String::trim).filter(String::isNotEmpty)

/** `provider:model` for each fallback; an old `provider/model` string reads the same way. */
private fun fallbackLines(value: JsonElement): List<String> = (value as? JsonArray).orEmpty().mapNotNull(::fallbackLine)

private fun fallbackLine(item: JsonElement): String? = when (item) {
    is JsonObject -> {
        val provider = (item["provider"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val model = (item["model"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (provider.isEmpty()) model.ifEmpty { null } else "$provider:$model"
    }
    is JsonPrimitive -> item.contentOrNull?.replaceFirst('/', ':')
    else -> null
}

/**
 * One fallback from its `provider:model` line. A line read from the chain keeps that entry as it was, with
 * the routing written by hand beside it (`base_url`, `api_mode`, keys) or a form the phone wouldn't write
 * (a bare model name), so a save neither sends it to the public provider nor refuses it.
 */
private fun fallbackEntry(line: String, unused: MutableList<JsonElement>): JsonElement {
    // Each entry once, so two that read the same keep their own routing.
    unused.firstOrNull { fallbackLine(it)?.trim() == line }?.let {
        unused.remove(it)
        return it
    }
    val provider = line.substringBefore(':', "").trim()
    val model = line.substringAfter(':', "").trim()
    require(provider.isNotEmpty() && model.isNotEmpty()) { "Write each fallback as provider:model, like openrouter:anthropic/claude-sonnet-4.6. “$line” isn't." }
    return JsonObject(mapOf("provider" to JsonPrimitive(provider), "model" to JsonPrimitive(model)))
}

private val prettyJson = Json(HermesJson) { prettyPrint = true }
