package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The herald-push plugin's tool that draws an A2UI screen in the chat (experiment, Android only). */
const val SHOW_UI_TOOL = "phone_show_ui"

/**
 * A screen the agent drew with [SHOW_UI_TOOL]: A2UI messages (`createSurface`, `updateComponents`, …), one JSON
 * object each, in the order they apply. [surfaceIds] are the surfaces they make or change; [wait] says whether the
 * tool waits for the user's tap, so the screen takes taps only while it does.
 */
data class AgentScreen(val messages: List<String>, val surfaceIds: Set<String>, val wait: Boolean) {
    /** Each message's surface, read once: the chat goes over its screens again as a reply streams in. */
    internal val envelopes: List<A2uiEnvelope?> by lazy { messages.map(A2uiEnvelope::of) }

    /**
     * Why these messages make a layout Kyllene won't draw, in words for the agent; null when they don't. A
     * component that holds itself, or a screen nested too deep or with too many components, would take the app
     * down as it draws, every time the chat opens.
     */
    val layoutProblem: String? by lazy { layoutProblemOf(messages) }

    companion object {
        /** At most this many messages a call: a screen, not a stream. */
        const val MAX_MESSAGES = 100

        /** At most this many components on a surface, and nested this deep. */
        const val MAX_COMPONENTS = 500
        const val MAX_DEPTH = 24

        /**
         * The screen a call of tool [name] with [arguments] draws, or null when it isn't [SHOW_UI_TOOL] or its
         * messages aren't a list of objects. Takes the call through the deferred-tools bridge (`tool_call`) too,
         * and messages the model wrote as one JSON string (a list, or one object a line).
         */
        fun from(name: String, arguments: JsonElement?): AgentScreen? {
            // Every other tool is passed by before its arguments are read.
            if (name != SHOW_UI_TOOL && name != "tool_call") return null
            val args = objectOf(arguments)
            val call = if (name == SHOW_UI_TOOL) args else bridged(name, args) ?: return null
            if (call == null) return null
            val messages = when (val raw = call["messages"]) {
                is JsonArray -> raw.toList()
                is JsonPrimitive -> raw.contentOrNull?.let(::parseList)
                else -> null
            } ?: return null
            if (messages.isEmpty() || messages.size > MAX_MESSAGES || messages.any { it !is JsonObject }) return null
            val surfaces = messages.mapNotNullTo(LinkedHashSet()) { message ->
                (message as JsonObject).values.firstNotNullOfOrNull { body ->
                    ((body as? JsonObject)?.get("surfaceId") as? JsonPrimitive)?.takeIf { it.isString }?.content
                }
            }
            if (surfaces.isEmpty()) return null
            val wait = (call["wait"] as? JsonPrimitive)?.booleanOrNull ?: true
            return AgentScreen(messages.map { it.toString() }, surfaces, wait)
        }

        /** The arguments of a [SHOW_UI_TOOL] call made through the `tool_call` bridge, else null. */
        private fun bridged(name: String, args: JsonObject?): JsonObject? {
            if (name != "tool_call" || args == null) return null
            val entry = (args["calls"] as? JsonArray)?.singleOrNull() as? JsonObject ?: args
            if ((entry["name"] as? JsonPrimitive)?.contentOrNull != SHOW_UI_TOOL) return null
            return objectOf(entry["arguments"]) ?: objectOf(entry["args"]) ?: entry
        }

        private fun objectOf(element: JsonElement?): JsonObject? = when (element) {
            is JsonObject -> element
            is JsonPrimitive -> element.contentOrNull?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
            else -> null
        }

        /** Keys whose values name other components: children, a card's or button's child, a modal's parts. */
        private val CHILD_KEYS = setOf("child", "children", "trigger", "content", "componentId")

        private fun layoutProblemOf(messages: List<String>): String? {
            // Each surface's components, by id, as the messages leave them (a later update replaces a component).
            val surfaces = LinkedHashMap<String, LinkedHashMap<String, JsonObject>>()
            for (message in messages) {
                val update = (runCatching { Json.parseToJsonElement(message) }.getOrNull() as? JsonObject)
                    ?.get("updateComponents") as? JsonObject ?: continue
                val surface = (update["surfaceId"] as? JsonPrimitive)?.contentOrNull ?: continue
                val byId = surfaces.getOrPut(surface) { LinkedHashMap() }
                (update["components"] as? JsonArray)?.forEach { component ->
                    val id = ((component as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull ?: return@forEach
                    byId[id] = component
                }
                if (byId.size > MAX_COMPONENTS) return "Surface $surface has more than $MAX_COMPONENTS components; keep a screen small."
            }
            for ((surface, byId) in surfaces) {
                val children = byId.mapValues { (_, component) ->
                    component.entries.filter { (k, _) -> k != "id" && k != "component" }.flatMap { (k, v) -> childIds(k, v) }
                        .filter { it in byId }
                }
                // How deep each component's tree goes, from every component, so a cycle is found even away from
                // root. Stopped past the limit, so no layout takes this check deeper than that either.
                val heights = HashMap<String, Int>()
                val path = LinkedHashSet<String>()
                var problem: String? = null
                fun height(id: String): Int {
                    heights[id]?.let { return it }
                    if (id in path) {
                        problem = "Component $id on surface $surface contains itself (${(path.dropWhile { it != id } + id).joinToString(" → ")})."
                        return Int.MAX_VALUE
                    }
                    if (path.size > MAX_DEPTH) return Int.MAX_VALUE
                    path += id
                    var most = 0
                    for (child in children[id].orEmpty()) {
                        most = maxOf(most, height(child))
                        if (problem != null || most > MAX_DEPTH) break
                    }
                    path -= id
                    return (if (most == Int.MAX_VALUE) most else most + 1).also { heights[id] = it }
                }
                for (id in byId.keys) {
                    val deep = height(id) > MAX_DEPTH
                    problem?.let { return it }
                    if (deep) return "Components on surface $surface nest more than $MAX_DEPTH deep."
                }
            }
            return null
        }

        private fun childIds(key: String, value: JsonElement): List<String> = when (value) {
            is JsonPrimitive -> if (key in CHILD_KEYS && value.isString) listOf(value.content) else emptyList()
            // A list of ids, or of tabs and the like, each with a child.
            is JsonArray -> value.flatMap { childIds(key, it) }
            is JsonObject -> value.entries.flatMap { (k, v) -> childIds(k, v) }
        }

        private fun parseList(text: String): List<JsonElement>? {
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) return runCatching { Json.parseToJsonElement(trimmed) as? JsonArray }.getOrNull()
            return runCatching { trimmed.lines().filter { it.isNotBlank() }.map(Json::parseToJsonElement) }.getOrNull()
        }
    }
}
