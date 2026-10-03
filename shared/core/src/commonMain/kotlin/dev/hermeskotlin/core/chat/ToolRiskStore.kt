package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Tool calls whose output was flagged, by call id. The gateway only says so live (`tool.output_risk`);
 * the transcript doesn't keep it, so this device remembers what it saw to show it again on reload.
 */
class ToolRiskStore(private val store: KeyValueStore) {

    private val lock = Mutex()
    private var cache: LinkedHashMap<String, ToolRisk>? = null

    suspend fun remember(callId: String, risk: ToolRisk) = lock.withLock {
        val all = load()
        all.remove(callId)
        all[callId] = risk
        // Oldest first: past the cap, the flags least likely to be looked at again go.
        while (all.size > MAX_ENTRIES) all.remove(all.keys.first())
        store.put(KEY, HermesJson.encodeToString(SERIALIZER, all))
    }

    suspend fun all(): Map<String, ToolRisk> = lock.withLock { load().toMap() }

    private suspend fun load(): LinkedHashMap<String, ToolRisk> = cache ?: LinkedHashMap(
        store.get(KEY)?.let { runCatching { HermesJson.decodeFromString(SERIALIZER, it) }.getOrNull() }.orEmpty(),
    ).also { cache = it }

    private companion object {
        const val KEY = "chat.toolRisk.v1"
        const val MAX_ENTRIES = 500
        val SERIALIZER = MapSerializer(String.serializer(), ToolRisk.serializer())
    }
}

/** Puts remembered flags back on the tool calls they belong to. */
internal fun List<ChatMessage>.withRisks(risks: Map<String, ToolRisk>): List<ChatMessage> {
    if (risks.isEmpty()) return this
    return map { message ->
        if (message is ChatMessage.Assistant && message.tools.any { it.risk == null && it.id in risks }) {
            message.copy(tools = message.tools.map { it.copy(risk = it.risk ?: risks[it.id]) })
        } else {
            message
        }
    }
}
