package dev.hermeskotlin.core.rooms

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The gateway calls behind Rooms, over the same dashboard socket everything else uses: the hosted-room
 * system the gateway serves for every client (`groups.*`). Room log events are read with a cursor and
 * the transcript is rebuilt from them, so a missed stretch of socket costs nothing.
 */
class RoomsApi(private val connection: GatewayConnection) {

    /** `groups.capabilities`: whether this gateway hosts rooms at all (`driver`), and its limits. */
    suspend fun capabilities(): RoomCapabilities =
        decode(client().request("groups.capabilities", JsonObject(emptyMap())), RoomCapabilities.serializer(), "room capabilities")

    /** `groups.list`: rooms this gateway hosts, most recently changed first. */
    suspend fun list(): List<Room> {
        val reply = client().request("groups.list", JsonObject(emptyMap())) as? JsonObject ?: return emptyList()
        val rows = reply["rooms"] ?: return emptyList()
        // A deleted room can linger as a tombstone; it's gone as far as the user is concerned.
        return runCatching { HermesJson.decodeFromJsonElement(ListSerializer(Room.serializer()), rows) }
            .getOrDefault(emptyList())
            .filter { it.disbandedAt == null }
    }

    /**
     * `groups.create`: makes the room and its roster, idempotently by [roomId]. Members are local bots
     * (2-6): the gateway validates the roster (unique handles, no reserved words) and says so plainly.
     */
    suspend fun create(roomId: String, name: String, members: List<RoomMemberInput>): Room {
        val reply = client().request(
            "groups.create",
            buildJsonObject {
                put("room_id", roomId)
                put("name", name)
                putJsonArray("members") {
                    members.forEach { member ->
                        add(buildJsonObject {
                            put("member_id", member.memberId)
                            put("profile", member.profile)
                            put("handle", member.handle)
                            member.displayName?.let { put("display_name", it) }
                        })
                    }
                }
            },
        ) as? JsonObject
        val room = reply?.get("room") ?: throw RpcException(0, "The gateway didn't say what it made.")
        return decode(room, Room.serializer(), "a room")
    }

    /** `groups.state`: the room plus how its driver is doing and what waits on the user. */
    suspend fun state(roomId: String): RoomState {
        val reply = client().request("groups.state", buildJsonObject { put("room_id", roomId) }) as? JsonObject
            ?: throw RpcException(0, "The gateway didn't answer for the room.")
        return decode(reply, RoomState.serializer(), "a room state")
    }

    /** `groups.log`: everything after [sinceSeq], up to [limit] events. */
    suspend fun log(roomId: String, sinceSeq: Int, limit: Int): RoomLogPage {
        val reply = client().request(
            "groups.log",
            buildJsonObject {
                put("room_id", roomId)
                put("since_seq", sinceSeq)
                put("limit", limit)
            },
        ) as? JsonObject ?: return RoomLogPage()
        return runCatching { HermesJson.decodeFromJsonElement(RoomLogPage.serializer(), reply) }
            .getOrDefault(RoomLogPage())
    }

    /**
     * `groups.send`: one message.user, idempotent by [eventId], so a retry after a lost answer never
     * double-posts. [threadId] names the conversation line it belongs to.
     */
    suspend fun send(roomId: String, text: String, threadId: String, eventId: String): RoomEvent {
        val reply = client().request(
            "groups.send",
            buildJsonObject {
                put("room_id", roomId)
                put("event_id", eventId)
                putJsonObject("payload") {
                    put("text", text)
                    put("thread_id", threadId)
                }
            },
            timeoutMs = SEND_TIMEOUT_MS,
        ) as? JsonObject
        val event = reply?.get("event") ?: throw RpcException(0, "The gateway didn't take the message.")
        return decode(event, RoomEvent.serializer(), "a message")
    }

    /** `groups.stop`: asks the whole room to stop; identified by [cancelId] so a retry is the same ask. */
    suspend fun stop(roomId: String, cancelId: String) {
        client().request(
            "groups.stop",
            buildJsonObject {
                put("room_id", roomId)
                put("cancel_id", cancelId)
            },
        )
    }

    /**
     * `groups.disband`: deletes the room for good, for every client: its work is stopped first.
     * Identified by [cancelId] so a retry after a lost answer is the same ask.
     */
    suspend fun disband(roomId: String, cancelId: String) {
        client().request(
            "groups.disband",
            buildJsonObject {
                put("room_id", roomId)
                put("cancel_id", cancelId)
            },
            timeoutMs = SEND_TIMEOUT_MS,
        )
    }

    /** `groups.rename`: renames the room, idempotently by [eventId]; the renamed room comes back. */
    suspend fun rename(roomId: String, name: String, eventId: String): Room {
        val reply = client().request(
            "groups.rename",
            buildJsonObject {
                put("room_id", roomId)
                put("event_id", eventId)
                put("name", name)
            },
        ) as? JsonObject
        val room = reply?.get("room") ?: throw RpcException(0, "The gateway didn't say what it renamed.")
        return decode(room, Room.serializer(), "a room")
    }

    /** `groups.retry`: gives the failed turn [taskId] another go. */
    suspend fun retry(roomId: String, taskId: String) {
        client().request(
            "groups.retry",
            buildJsonObject {
                put("room_id", roomId)
                put("task_id", taskId)
            },
        )
    }

    /**
     * `groups.approve`: answers one pending approval with the coordinates the room's state carried —
     * only `once` or `deny` are accepted here, whichever the user picked. The whole tuple has to match
     * what's pending, so a stale answer is refused rather than misapplied.
     */
    suspend fun approve(
        roomId: String,
        memberId: String,
        taskId: String,
        executionGeneration: Int,
        choice: String,
        requestId: String,
    ) {
        client().request(
            "groups.approve",
            buildJsonObject {
                put("room_id", roomId)
                put("member_id", memberId)
                put("task_id", taskId)
                put("execution_generation", executionGeneration)
                put("choice", choice)
                put("request_id", requestId)
            },
            timeoutMs = SEND_TIMEOUT_MS,
        )
    }

    private fun <T> decode(element: JsonElement, serializer: KSerializer<T>, what: String): T =
        runCatching { HermesJson.decodeFromJsonElement(serializer, element) }
            .getOrElse { throw RpcException(0, "The gateway's answer wasn't $what.") }

    private fun client(): JsonRpcClient = (connection.state.value as? ConnectionState.Connected)?.client
        ?: throw RpcException(0, "Not connected to the gateway.")

    private companion object {
        /**
         * Sending into a room can make the driver wake members right there; give it longer than a plain
         * read, so a busy gateway doesn't look like a lost message.
         */
        const val SEND_TIMEOUT_MS = 60_000L
    }
}
