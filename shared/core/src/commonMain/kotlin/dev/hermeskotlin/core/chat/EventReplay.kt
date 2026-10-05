package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.rpc.GatewayEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Reads a `session.events.since` reply (tui_gateway/event_replay.py). The gateway stamps every event of a runtime
 * session with a `seq` counting up from 1 and keeps the last ~512 in a ring, so a client that lost the socket asks
 * for everything after the last seq it applied and carries on, instead of reloading the chat.
 */
internal object EventReplay {
    /**
     * The events after [from] for [runtimeId], oldest first, when the reply proves none is missing; null when the
     * gap can't be trusted and the chat must be reloaded instead:
     * - `truncated`: the ring dropped events past [from] (too long away, or a frame too big to keep);
     * - another `epoch` than [epoch], the run the seqs were counted in: the gateway restarted and counts anew;
     * - `latest_seq` below [from], the same restart seen without an epoch;
     * - a hole in the seqs, or an event that isn't for [runtimeId].
     *
     * Events past the last one returned may still be on their way live: the gateway reads `latest_seq` after the
     * ring, so it can be ahead of the events by what was stamped in between.
     */
    fun parse(reply: JsonObject?, runtimeId: String, from: Long, epoch: String?): List<GatewayEvent>? {
        reply ?: return null
        val latest = (reply["latest_seq"] as? JsonPrimitive)?.longOrNull ?: return null
        val events = reply["events"] as? JsonArray ?: return null
        if (reply.boolean("truncated") != false) return null
        if (epoch != null && reply.string("epoch").let { it != null && it != epoch }) return null
        if (latest < from) return null
        var cursor = from
        val missed = mutableListOf<GatewayEvent>()
        for (element in events) {
            val event = element as? JsonObject ?: return null
            if (event.string("session_id") != runtimeId) return null
            val seq = (event["seq"] as? JsonPrimitive)?.longOrNull ?: return null
            // Already applied: the ring can hand back the seq asked from when nothing came after it.
            if (seq <= cursor) continue
            if (seq != cursor + 1) return null
            cursor = seq
            missed += GatewayEvent(type = event.string("type") ?: return null, payload = event["payload"], sessionId = runtimeId, seq = seq)
        }
        return missed
    }
}
