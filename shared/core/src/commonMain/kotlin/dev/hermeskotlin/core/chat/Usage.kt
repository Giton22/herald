package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.JsonObject

/**
 * The live agent's running token totals (tui_gateway `_get_usage`), from `session.info`, `session.usage`
 * ticks and `message.complete`. They count from when the gateway built the agent, not from the start
 * of the chat, so they are only meaningful as differences.
 */
data class SessionUsage(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val calls: Long = 0,
    /** How full the model's context window is right now. */
    val contextUsed: Long? = null,
    val contextMax: Long? = null,
    val contextPercent: Int? = null,
    val cacheHitPercent: Int? = null,
) {
    /** What one turn used: these totals minus the ones it started from; null when the agent was rebuilt in between. */
    operator fun minus(start: SessionUsage): TurnUsage? {
        val turn = TurnUsage(input - start.input, output - start.output, reasoning - start.reasoning, calls - start.calls)
        return turn.takeIf { it.input >= 0 && it.output >= 0 && it.reasoning >= 0 && it.calls >= 0 }
    }

    companion object {
        fun parse(usage: JsonObject?): SessionUsage? {
            if (usage == null || usage.isEmpty()) return null
            fun long(key: String) = usage.double(key)?.toLong()
            return SessionUsage(
                input = long("input") ?: 0,
                output = long("output") ?: 0,
                reasoning = long("reasoning") ?: 0,
                calls = long("calls") ?: 0,
                contextUsed = long("context_used"),
                contextMax = long("context_max"),
                contextPercent = usage.int("context_percent"),
                cacheHitPercent = usage.int("cache_hit_pct"),
            )
        }
    }
}

/** Tokens one reply took. */
data class TurnUsage(val input: Long, val output: Long, val reasoning: Long, val calls: Long) {
    val any: Boolean get() = input > 0 || output > 0
}

/** "950", "12.3k", "1.2M". */
fun compactCount(n: Long): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> oneDecimal(n / 1_000.0) + "k"
    else -> oneDecimal(n / 1_000_000.0) + "M"
}

private fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toLong()
    return if (tenths % 10 == 0L || tenths >= 1_000) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}
