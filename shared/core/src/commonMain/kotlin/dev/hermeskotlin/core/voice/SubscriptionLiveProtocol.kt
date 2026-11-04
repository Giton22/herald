package dev.hermeskotlin.core.voice

import kotlinx.serialization.json.*

/** Subscription wire format, isolated from the public API dialect. */
class SubscriptionLiveProtocol {
    private val json = Json { ignoreUnknownKeys = true }
    private val streamed = mutableMapOf<String, String>()
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Translate only observed provider events; delegation content is not a user transcript. */
    fun receive(raw: String): List<LiveEvent> {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyList()
        fun transcript(role: String, text: String): LiveEvent = LiveEvent.Transcript(
            LiveFragment(if (role == "user") LiveFragment.Speaker.User else LiveFragment.Speaker.Assistant, text, 0, 0))
        return when (obj.text("type")) {
            "session.started", "session.updated" -> listOf(LiveEvent.Started)
            "input_transcript.added", "output_transcript.added" -> {
                val role = if (obj.text("type") == "input_transcript.added") "user" else "assistant"
                val text = (obj["item"] as? JsonObject)?.text("text") ?: return emptyList()
                streamed[role] = streamed[role].orEmpty() + text
                listOf(transcript(role, text))
            }
            "turn.done" -> {
                val turn = obj["turn"] as? JsonObject ?: return emptyList()
                val role = turn.text("role")?.takeIf { it == "user" || it == "assistant" } ?: return emptyList()
                val text = turn.text("transcript")
                val before = streamed.remove(role).orEmpty()
                // A corrected final cannot safely be appended to stock's already displayed fragments.
                val suffix = if (text != null && text.startsWith(before)) text.substring(before.length) else ""
                buildList {
                    if (role == "assistant" && suffix.isNotEmpty()) add(transcript(role, suffix))
                    if (role == "user") add(LiveEvent.UserFinal(text))
                }
            }
            "delegation.created" -> {
                val item = obj["item"] as? JsonObject ?: return emptyList()
                val id = item.text("id")?.takeIf { it.isNotEmpty() } ?: return emptyList()
                if (item.text("type") != "delegation" || item.text("target") != "client" || item["content"] !is JsonArray) emptyList()
                else listOf(LiveEvent.Delegation(id))
            }
            "error" -> {
                val error = obj["error"] as? JsonObject
                if (error?.text("code") == "context_injection_incomplete") emptyList()
                else listOf(LiveEvent.Failure("Subscription voice provider error."))
            }
            "session.closed" -> listOf(LiveEvent.Closed("closed", null))
            else -> emptyList()
        }
    }

    /** Translate stock appends; local microphone mute is handled by LiveCall.setMuted. */
    fun send(raw: String): List<String> {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyList()
        val type = obj.text("type")
        if (type == "session.close") return listOf(raw)
        val channel = when (type) {
            "session.commentary.append" -> "speakable"
            "session.thinking.append" -> "commentary"
            // This transport has no matching instruction/mute event; never send an API dialect command.
            else -> return emptyList()
        }
        val content = obj.text("content") ?: return emptyList()
        val delegation = obj.text("delegation_id")
        return subscriptionChunks(content).map { chunk ->
            buildJsonObject {
                put("type", if (delegation == null) "session.context.append" else "delegation.context.append")
                put("channel", channel)
                putJsonArray("content") { add(buildJsonObject { put("type", "input_text"); put("text", chunk) }) }
                if (delegation != null) put("delegation_item_id", delegation)
            }.toString()
        }
    }
}

/** Preserve scalar boundaries and all interior whitespace, with at most 500 UTF-8 bytes. */
fun subscriptionChunks(text: String): List<String> {
    val clean = text.trim()
    if (clean.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    val current = StringBuilder()
    var bytes = 0
    var i = 0
    while (i < clean.length) {
        val n = if (clean[i].isHighSurrogate() && i + 1 < clean.length && clean[i + 1].isLowSurrogate()) 2 else 1
        val scalar = clean.substring(i, i + n)
        val size = scalar.encodeToByteArray().size
        if (bytes + size > 500) { out += current.toString(); current.clear(); bytes = 0 }
        current.append(scalar); bytes += size; i += n
    }
    if (current.isNotEmpty()) out += current.toString()
    return out
}
