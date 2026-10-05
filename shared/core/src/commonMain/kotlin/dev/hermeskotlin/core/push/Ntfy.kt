package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.network.HermesJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/** One event of an ntfy subscription stream (`/<topic>/json`). Only `message` events carry a body. */
@Serializable
data class NtfyEvent(val id: String = "", val time: Long = 0, val event: String = "", val topic: String = "", val message: String? = null)

/**
 * The ntfy server between gateway and phone (https://docs.ntfy.sh): the phone receives with a long-lived
 * JSON stream, an outbound connection, so it works off the tailnet. Uses its own HTTP client: requests here
 * must never carry the gateway's cookies.
 */
class NtfyClient(private val client: HttpClient) {

    /**
     * The messages of [topic] from [sinceSeconds] (the unix time of the last one read; ntfy keeps messages
     * for hours) or all it still holds. Ends when the stream does; the caller reconnects.
     */
    fun subscribe(server: String, topic: String, sinceSeconds: Long?): Flow<NtfyEvent> = flow {
        client.prepareGet(topicUrl(server, topic) + "/json") {
            // A time, not the last message's id: ntfy doesn't say what an id that has left its cache means,
            // and that is exactly the case after a long stretch offline. The message at that very second
            // comes again and the replay guard drops it. ntfy has no "now"; it answers 400.
            parameter("since", sinceSeconds?.toString() ?: "all")
            // The stream is meant to stay open; ntfy keeps it alive every ~45 s.
            timeout {
                requestTimeoutMillis = Long.MAX_VALUE
                socketTimeoutMillis = STREAM_IDLE_MS
            }
        }.execute { response ->
            check(response.status.isSuccess()) { "ntfy answered ${response.status.value}" }
            val channel = response.bodyAsChannel()
            while (true) {
                // Bounded: the server isn't trusted, and an endless line must not fill memory.
                val line = channel.readUTF8Line(MAX_LINE) ?: break
                if (line.isBlank()) continue
                val event = runCatching { HermesJson.decodeFromString(NtfyEvent.serializer(), line) }.getOrNull() ?: continue
                if (event.event == "message" && event.topic == topic) emit(event)
            }
        }
    }

    private fun topicUrl(server: String, topic: String): String {
        require(TOPIC.matches(topic)) { "not a push topic" }
        return server.trimEnd('/') + "/" + topic.encodeURLPathPart()
    }

    companion object {
        const val DEFAULT_SERVER = "https://ntfy.sh"
        const val MAX_BODY = 4096
        private const val MAX_LINE = 64 * 1024
        private const val STREAM_IDLE_MS = 120_000L
        private val TOPIC = Regex("^hp-[0-9a-f]{32}$")

        /** A server Herald will use: https only, since topics and timing pass through it. */
        fun validServer(url: String): Boolean = Regex("^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?/?$").matches(url.trim())
    }
}

/**
 * Drops push messages that are stale, from the future, or seen before (a replayed envelope). [seen] is the
 * set of recent ids, kept by the caller across restarts.
 */
class PushGuard(private val maxAgeSeconds: Long, private val now: () -> Long) {

    private val seen = LinkedHashMap<String, Long>()

    fun remember(ids: Map<String, Long>) {
        seen.putAll(ids)
    }

    /** The ids to persist: those still inside the age window. */
    fun snapshot(): Map<String, Long> = seen.filterValues { now() - it <= maxAgeSeconds + PushMessage.MAX_SKEW_SECONDS }

    /** True when [message] is fresh and new; it is then remembered so it can't be accepted twice. */
    fun accept(message: PushMessage): Boolean {
        val t = now()
        if (message.ts > t + PushMessage.MAX_SKEW_SECONDS) return false
        if (t - message.ts > maxAgeSeconds) return false
        if (message.id.isBlank() || message.id in seen) return false
        seen[message.id] = message.ts
        // Ids older than the window can't be replayed anyway: forget them.
        if (seen.size > MAX_REMEMBERED) seen.keys.take(seen.size - MAX_REMEMBERED).forEach(seen::remove)
        return true
    }

    private companion object {
        const val MAX_REMEMBERED = 2_000
    }
}
