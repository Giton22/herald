package dev.hermeskotlin.core.rooms

import dev.hermeskotlin.core.rpc.FakeGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomsApiTest {

    /** Every room call the gateway was sent, as (method, params); the client's own handshake is left out. */
    private val requests = mutableListOf<Pair<String, JsonObject>>()

    /**
     * Answers every request the client sends with [answer]. Runs beside the test, so it must not throw: the
     * tests assert what was sent from their own threads after the call returns.
     */
    private suspend fun api(scope: CoroutineScope, answer: (String, JsonObject) -> String): RoomsApi {
        val gateway = FakeGateway(scope, reconnects = false)
        gateway.answer = { call ->
            if (call.method.startsWith("groups.")) requests += call.method to call.params
            answer(call.method, call.params)
        }
        return RoomsApi(gateway.start())
    }

    /** The one room request whose method is [method], with its params. */
    private fun sentTo(method: String): JsonObject {
        val (sentMethod, params) = requests.last { it.first == method }
        assertEquals(method, sentMethod)
        return params
    }

    @Test
    fun capabilitiesSayWhetherTheDriverRuns() = runTest {
        val api = api(backgroundScope) { method, _ ->
            when (method) {
                "groups.capabilities" -> """{"protocol_version":1,"driver":true,"persistent_process":true,"authority_gateway_id":"gw-1",
                    "room_link":{"status":"linked","target_install_id":"gw-1"},"features":["send","log"],
                    "methods":["groups.send","groups.log","groups.approve"],"max_log_limit":200}"""
                else -> "{}"
            }
        }
        val caps = api.capabilities()
        assertEquals(true, caps.driver)
        assertEquals(200, caps.maxLogLimit)
        assertTrue("groups.send" in caps.methods)
        assertEquals("groups.capabilities", requests.single().first)
    }

    @Test
    fun listBringsRoomsWithTheirRosters() = runTest {
        val api = api(backgroundScope) { method, _ ->
            when (method) {
                "groups.list" -> """{"rooms":[{"room_id":"r1","name":"Release room",
                    "members":[{"member_id":"ops","profile":"ops","handle":"ops","display_name":"Ops Bot"}],
                    "authority_gateway_id":"gw-1","authority_epoch":3,"revision":2,"created_at":1.0,"updated_at":2.0,"latest_seq":7}],
                    "next_offset":null}"""
                else -> "{}"
            }
        }
        val rooms = api.list()
        assertEquals(1, rooms.size)
        assertEquals("Release room", rooms[0].name)
        assertEquals("Ops Bot", rooms[0].members[0].label)
        assertEquals(7, rooms[0].latestSeq)
    }

    @Test
    fun logReadsAfterTheCursorAndParsesThePage() = runTest {
        val api = api(backgroundScope) { method, _ ->
            when (method) {
                "groups.log" -> """{"events":[
                    {"room_id":"r1","seq":6,"event_id":"user:6","kind":"message.user","actor":{"kind":"user","id":"desktop"},
                     "payload":{"text":"hello","thread_id":"thread-1"},"created_at":10.0},
                    {"room_id":"r1","seq":7,"event_id":"gateway:7","kind":"message.member","actor":{"kind":"member","id":"ops"},
                     "payload":{"text":"on it","member_id":"ops"},"created_at":11.0}],
                   "cursor":7,"latest_seq":9,"has_more":false,"authority":{"gateway_id":"gw-1","epoch":3}}"""
                else -> "{}"
            }
        }
        val page = api.log("r1", sinceSeq = 5, limit = 50)
        assertEquals(2, page.events.size)
        assertEquals(7, page.cursor)
        assertEquals(9, page.latestSeq)
        assertEquals("message.member", page.events[1].kind)
        val params = sentTo("groups.log")
        assertEquals(5, params["since_seq"]?.jsonPrimitive?.int)
        assertEquals(50, params["limit"]?.jsonPrimitive?.int)
    }

    @Test
    fun sendCarriesTextThreadAndEventId() = runTest {
        val api = api(backgroundScope) { method, _ ->
            when (method) {
                "groups.send" -> """{"event":{"room_id":"r1","seq":8,"event_id":"user:8","kind":"message.user",
                    "actor":{"kind":"user","id":"desktop"},"payload":{"text":"hi there","thread_id":"thread-1"},"created_at":12.0},
                    "client_event_id":"c-1","accepted":true,"driver_started":true}"""
                else -> "{}"
            }
        }
        val event = api.send("r1", text = "hi there", threadId = "thread-1", eventId = "c-1")
        assertEquals(8, event.seq)
        val params = sentTo("groups.send")
        assertEquals("c-1", params["event_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("hi there", params["payload"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull)
        assertEquals("thread-1", params["payload"]?.jsonObject?.get("thread_id")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun stateBringsThePendingActionsWithTheirCoordinates() = runTest {
        val api = api(backgroundScope) { method, _ ->
            when (method) {
                "groups.state" -> """{"room":{"room_id":"r1","name":"Room","members":[],
                    "authority_gateway_id":"gw-1","authority_epoch":1,"revision":1,"created_at":1.0,"updated_at":1.0},
                    "driver_status":{"running":true,"working":true,"blocked":true,"counts":{"queued":1},
                    "pending_actions":[
                        {"kind":"approval","member_id":"ops","task_id":"t-9","execution_generation":2,"request_id":"a-3",
                         "approval":{"command":"git push origin main","choices":["once","deny"]}},
                        {"kind":"retry","member_id":"scribe","task_id":"t-4"}],"peer_routes":[]}}"""
                else -> "{}"
            }
        }
        val state = api.state("r1")
        assertEquals(true, state.driverStatus?.working)
        val actions = state.driverStatus?.pendingActions.orEmpty()
        assertEquals(2, actions.size)
        assertEquals("t-9", actions[0].taskId)
        assertEquals(2, actions[0].executionGeneration)
        assertEquals("git push origin main", actions[0].approvalSummary)
        assertEquals("t-4", actions[1].taskId)
    }

    @Test
    fun approveSendsTheExactCoordinates() = runTest {
        val api = api(backgroundScope) { _, _ -> "{}" }
        api.approve("r1", memberId = "ops", taskId = "t-9", executionGeneration = 2, choice = "once", requestId = "a-3")
        val params = sentTo("groups.approve")
        assertEquals("r1", params["room_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("ops", params["member_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("t-9", params["task_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2, params["execution_generation"]?.jsonPrimitive?.int)
        assertEquals("once", params["choice"]?.jsonPrimitive?.contentOrNull)
        assertEquals("a-3", params["request_id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun retryAndStopNameTheExactTargets() = runTest {
        val api = api(backgroundScope) { _, _ -> "{}" }
        api.retry("r1", taskId = "t-4")
        api.stop("r1", cancelId = "cancel-7")
        val retry = sentTo("groups.retry")
        assertEquals("t-4", retry["task_id"]?.jsonPrimitive?.contentOrNull)
        val stop = sentTo("groups.stop")
        assertEquals("cancel-7", stop["cancel_id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun disbandAndRenameNameTheRoomAndTheirIds() = runTest {
        val api = api(backgroundScope) { method, _ ->
            when (method) {
                "groups.disband" -> """{"tombstone":{"room_id":"r1","disbanded_at":5.0,"idempotent":false}}"""
                "groups.rename" -> """{"room":{"room_id":"r1","name":"New name","members":[],
                    "authority_gateway_id":"gw-1","authority_epoch":1,"revision":2,"created_at":1.0,"updated_at":2.0}}"""
                else -> "{}"
            }
        }
        api.disband("r1", cancelId = "disband-1")
        val disband = sentTo("groups.disband")
        assertEquals("r1", disband["room_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("disband-1", disband["cancel_id"]?.jsonPrimitive?.contentOrNull)

        val renamed = api.rename("r1", "New name", eventId = "rename-1")
        assertEquals("New name", renamed.name)
        val rename = sentTo("groups.rename")
        assertEquals("rename-1", rename["event_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("New name", rename["name"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun aDeletedRoomIsLeftOutOfTheList() = runTest {
        val api = api(backgroundScope) { method, _ ->
            when (method) {
                "groups.list" -> """{"rooms":[
                    {"room_id":"r1","name":"Live","members":[],"authority_gateway_id":"gw-1","authority_epoch":1,"revision":1,"created_at":1.0,"updated_at":2.0},
                    {"room_id":"r2","name":"Gone","members":[],"authority_gateway_id":"gw-1","authority_epoch":1,"revision":3,"created_at":1.0,"updated_at":3.0,"disbanded_at":3.0}]}"""
                else -> "{}"
            }
        }
        assertEquals(listOf("r1"), api.list().map { it.roomId })
    }

    @Test
    fun createProposesTheRoster() = runTest {
        val api = api(backgroundScope) { method, _ ->
            when (method) {
                "groups.create" -> """{"room":{"room_id":"r-9","name":"Release room",
                    "members":[{"member_id":"ops","profile":"ops","handle":"ops"}],
                    "authority_gateway_id":"gw-1","authority_epoch":1,"revision":1,"created_at":1.0,"updated_at":1.0}}"""
                else -> "{}"
            }
        }
        val room = api.create(
            "r-9",
            "Release room",
            listOf(
                RoomMemberInput("ops", "ops", "ops"),
                RoomMemberInput("scribe", "scribe", "scribe", "Scribe"),
            ),
        )
        assertEquals("r-9", room.roomId)
        val params = sentTo("groups.create")
        assertEquals("r-9", params["room_id"]?.jsonPrimitive?.contentOrNull)
        val members = params["members"]?.jsonArray.orEmpty()
        assertEquals(2, members.size)
        assertEquals("ops", members[0].jsonObject["member_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Scribe", members[1].jsonObject["display_name"]?.jsonPrimitive?.contentOrNull)
    }
}
