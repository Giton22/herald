package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

const val IMAGE_GENERATE_TOOL = "image_generate"

/**
 * The tool a stored call ran. With deferred tools on, the transcript keeps the `tool_call` bridge
 * (tools/tool_search.py) and the tool it invoked is in its arguments, `{"calls":[{"name":…}]}` or
 * the older `{"name":…}`. A single local entry runs as that tool, so its result is that tool's.
 */
internal fun storedToolName(name: String, arguments: JsonElement?): String {
    if (name != "tool_call") return name
    val args = when (arguments) {
        is JsonObject -> arguments
        is JsonPrimitive -> arguments.contentOrNull?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
        else -> null
    } ?: return name
    val entry = (args["calls"] as? JsonArray)?.singleOrNull() as? JsonObject ?: args
    return (entry["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: name
}

/**
 * The paths of the chat's generated pictures that the gateway can't serve (a sandbox's), to take out
 * of every reply: a reply after a mid-turn correction may repeat one the reply before made. The path
 * that loads stays, so a later reply can show the picture again.
 */
fun unservableEchoes(messages: List<ChatMessage>): List<GeneratedImage> =
    messages.asSequence().filterIsInstance<ChatMessage.Assistant>()
        .flatMap { reply -> reply.tools.mapNotNull { it.generatedImage } }
        .map { it.copy(echoes = it.echoes - it.source) }
        .filter { it.echoes.isNotEmpty() }
        .toList()

/**
 * The picture an `image_generate` call made (tools/image_generation_tool.py), shown in the reply as
 * Desktop does, whether or not the model names it in its text. [source] is the path the gateway can
 * serve (`host_image`, else `image`); [echoes] are every form the model may repeat it in, the path
 * inside a sandboxed terminal (`agent_visible_image`) among them, to take out of the text.
 */
data class GeneratedImage(val source: String, val echoes: List<String>) {
    companion object {
        private val DISPLAY_KEYS = listOf("host_image", "image")
        private val ECHO_KEYS = listOf("host_image", "image", "agent_visible_image")

        /** From a call's result: the live event's object or a stored row's JSON text. Null for a failure. */
        fun from(result: JsonElement?): GeneratedImage? {
            val obj = when (result) {
                is JsonObject -> result
                is JsonPrimitive -> result.contentOrNull
                    ?.let { runCatching { Json.parseToJsonElement(ToolDetails.unwrapUntrusted(it).trim()) }.getOrNull() } as? JsonObject
                else -> null
            } ?: return null
            if ((obj["success"] as? JsonPrimitive)?.booleanOrNull == false) return null
            fun fields(keys: List<String>) = keys.mapNotNull { key ->
                (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            }
            val source = fields(DISPLAY_KEYS).firstOrNull() ?: return null
            return GeneratedImage(source, fields(ECHO_KEYS).distinct())
        }
    }
}
