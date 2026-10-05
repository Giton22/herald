package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.JsonObject

/**
 * A notice the gateway shows beside a chat rather than in it (`notification.show`): the credits tracker's
 * "Credit access paused", or "Still starting the agent" while a slow agent builds. A notice with the same
 * [key] replaces it, and `notification.clear` withdraws it. Desktop shows these as a toast or in its status bar.
 */
data class GatewayNotice(
    val key: String,
    val text: String,
    val level: Level,
    val kind: Kind,
    /** For [Kind.Timed]: how long it stays. */
    val ttlMillis: Long? = null,
) {
    enum class Level { Info, Warning, Error, Success }

    /**
     * [Sticky] stays until cleared or dismissed, [Timed] until [ttlMillis] passes, and [Agent] (about the
     * agent being built) until the turn ends, in case the gateway never sends the clear.
     */
    enum class Kind { Sticky, Timed, Agent }

    companion object {
        /** A leading bullet or mark ("• ", "✕ "), which the notice's icon stands for here. */
        private val LEADING_MARK = Regex("""^[•·✕✓⚠\s]+""")

        /** Reads a `notification.show` payload; [fallbackKey] is for a notice with neither a key nor an id. */
        fun parse(payload: JsonObject?, fallbackKey: String): GatewayNotice? {
            val text = payload.string("text")?.replace(LEADING_MARK, "")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val ttl = payload.double("ttl_ms")?.toLong()?.takeIf { it > 0 }
            return GatewayNotice(
                key = payload.string("key")?.takeIf { it.isNotEmpty() } ?: payload.string("id")?.takeIf { it.isNotEmpty() } ?: fallbackKey,
                text = text,
                level = when (payload.string("level")) {
                    "warn", "warning" -> Level.Warning
                    "error" -> Level.Error
                    "success" -> Level.Success
                    else -> Level.Info
                },
                kind = when (payload.string("kind")) {
                    "ttl" -> if (ttl != null) Kind.Timed else Kind.Sticky
                    "agent" -> Kind.Agent
                    else -> Kind.Sticky
                },
                ttlMillis = ttl,
            )
        }
    }
}

/** Adds [notice], or puts it in the place of the one with the same key. */
internal fun ChatState.showNotice(payload: JsonObject?): ChatState {
    val notice = GatewayNotice.parse(payload, fallbackKey = "notice-$keySeq") ?: return this
    val index = notices.indexOfFirst { it.key == notice.key }
    val next = if (index >= 0) notices.toMutableList().apply { set(index, notice) } else notices + notice
    return copy(notices = next, keySeq = keySeq + 1)
}

/** Drops the notices about the agent being built, once nothing is waiting on it any more. */
internal fun ChatState.withoutAgentNotices(): ChatState =
    if (notices.none { it.kind == GatewayNotice.Kind.Agent }) this else copy(notices = notices.filterNot { it.kind == GatewayNotice.Kind.Agent })

/** Removes the notice with [key]: `notification.clear`, the user's ×, or a timed notice running out. */
fun ChatState.withoutNotice(key: String?): ChatState =
    if (key == null || notices.none { it.key == key }) this else copy(notices = notices.filterNot { it.key == key })
