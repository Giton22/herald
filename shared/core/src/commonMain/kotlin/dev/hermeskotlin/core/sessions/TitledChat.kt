package dev.hermeskotlin.core.sessions

import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A new chat titled [title] in [profile] (null: the gateway's launch profile), made the way Desktop makes one and
 * titled at once so its row exists before the first prompt; [options] adds `session.create` fields. Answers its
 * runtime id (which a chat opened on this socket resumes into) and stored id. A title another chat has is
 * [taken], with nothing left behind.
 */
internal suspend fun JsonRpcClient.createTitledChat(
    profile: String?,
    title: String,
    taken: () -> Exception,
    options: JsonObjectBuilder.() -> Unit = {},
): Pair<String, String> {
    val created = request(
        "session.create",
        buildJsonObject {
            profile?.let { put("profile", it) }
            put("title", title)
            options()
            put("source", CLIENT_SOURCE)
            put("cols", TERMINAL_COLUMNS)
        },
    ) as? JsonObject ?: throw RpcException(0, "Empty session.create reply")
    val runtime = (created["session_id"] as? JsonPrimitive)?.contentOrNull
    val stored = (created["stored_session_id"] as? JsonPrimitive)?.contentOrNull
    if (runtime == null || stored == null) throw RpcException(0, "session.create returned no session")
    try {
        request(
            "session.title",
            buildJsonObject {
                put("session_id", runtime)
                put("title", title)
            },
        )
    } catch (e: RpcException) {
        // Our runtime is of no use now. The gateway doesn't prune the empty row it made for a
        // non-"tui" source, so let go of the runtime and remove the row rather than leave a stray.
        runCatching { request("session.close", buildJsonObject { put("session_id", runtime) }) }
        runCatching {
            request(
                "session.delete",
                buildJsonObject {
                    put("session_id", stored)
                    profile?.let { put("profile", it) }
                },
            )
        }
        if (TITLE_TAKEN.containsMatchIn(e.message)) throw taken()
        throw e
    }
    return runtime to stored
}

/** Same surface hint as [dev.hermeskotlin.core.chat.ChatSession]: Markdown and pictures show here. */
private const val CLIENT_SOURCE = "desktop"
private const val TERMINAL_COLUMNS = 80
private val TITLE_TAKEN = Regex("already in use", RegexOption.IGNORE_CASE)
