package dev.hermeskotlin.core.threads

import dev.hermeskotlin.core.bots.parseBotRoster
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.profiles.UI_META_ATTEMPTS
import dev.hermeskotlin.core.profiles.writeUiMeta
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import dev.hermeskotlin.core.sessions.createTitledChat
import dev.hermeskotlin.core.sessions.putStartInstruction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A thread's chat couldn't take the title asked for: another chat in the profile has it. */
class ThreadTitleTakenException(val title: String) : Exception("A chat named $title already exists.")

/**
 * The gateway calls behind threads, over the dashboard socket: the list kept in the profile's `ui_meta`
 * ([ThreadMeta]), and a new chat for a thread started fresh.
 */
class ThreadsApi(private val connection: GatewayConnection) {

    /** The threads of the profile [name] (the gateway's own name for it, `default` included). */
    suspend fun threads(name: String): List<ChatThread> = ThreadMeta.parse(stored(name).block)

    /**
     * Applies [edit] to the profile [name]'s threads and saves the result. The list is read fresh and written with
     * the revision it was read at, so an edit made meanwhile on another device isn't overwritten: a lost race
     * reads again and applies [edit] to that. Answers the list as saved.
     */
    suspend fun change(name: String, edit: (List<ChatThread>) -> List<ChatThread>): List<ChatThread> {
        repeat(UI_META_ATTEMPTS) {
            val stored = stored(name)
            val before = ThreadMeta.parse(stored.block)
            val after = edit(before)
            if (after == before) return before
            val block = ThreadMeta.write(after, stored.block)
            if (client().writeUiMeta(name, ThreadMeta.KEY, block, stored.revision, refused = "The gateway didn't save the threads.")) return after
        }
        throw RpcException(0, "The threads changed elsewhere at the same time. Try again.")
    }

    /**
     * A new chat titled [title] in [profile] (null: the gateway's launch profile), running in [cwd] when given and
     * telling the agent [instruction] first, out of sight. Answers its runtime id and stored id. A title another chat
     * has is a [ThreadTitleTakenException], with nothing left behind.
     */
    suspend fun startChat(profile: String?, title: String, cwd: String?, instruction: String? = null): Pair<String, String> =
        client().createTitledChat(profile, title, taken = { ThreadTitleTakenException(title) }) {
            cwd?.let { put("cwd", it) }
            putStartInstruction(instruction)
        }

    /** Lets go of the runtime [runtime] of a chat about to be deleted; a failure is ignored. */
    suspend fun close(runtime: String) {
        runCatching { client().request("session.close", buildJsonObject { put("session_id", runtime) }) }
    }

    /** The `ui_meta` block and its revision as `profiles.list` has them for the profile [name]. */
    private suspend fun stored(name: String): Stored {
        val roster = parseBotRoster(client().request("profiles.list", buildJsonObject { put("include_sessions", false) }))
        val row = roster.bots.firstOrNull { it.name == name } ?: throw RpcException(0, "No profile named $name.")
        val revision = row.uiMetaRevisions?.let { revisions ->
            (revisions[ThreadMeta.KEY] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull() ?: 0
        }
        return Stored(row.uiMeta?.get(ThreadMeta.KEY) as? JsonObject, revision)
    }

    private class Stored(val block: JsonObject?, val revision: Int?)

    private fun client(): JsonRpcClient = (connection.state.value as? ConnectionState.Connected)?.client
        ?: throw RpcException(0, "Not connected to the gateway.")
}
