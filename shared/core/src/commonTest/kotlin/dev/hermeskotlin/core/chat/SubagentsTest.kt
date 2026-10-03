package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.GatewayEvent
import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubagentsTest {

    private fun event(type: String, payload: String = "{}") =
        GatewayEvent(type, HermesJson.parseToJsonElement(payload), sessionId = "rt", seq = null)

    private val twoTasks = """{"tool_id":"d1","name":"delegate_task","args":{"tasks":[{"goal":"Read the docs"},{"goal":"Write tests"}]}}"""

    private val delegating = ChatState(running = true).reduce(event("tool.start", twoTasks))

    private fun ChatState.call() = messages.filterIsInstance<ChatMessage.Assistant>().single().tools.single { it.name == "delegate_task" }

    private fun ChatState.rows() = subagentRows(call(), subagents)

    @Test
    fun aDelegationListsItsTasksBeforeAnySubagentStarts() {
        val rows = delegating.rows()
        assertEquals(listOf("Read the docs", "Write tests"), rows.map { it.goal })
        assertTrue(rows.all { it.status == SubagentStatus.Running })
    }

    @Test
    fun liveEventsFillInEachTask() {
        val state = delegating
            .reduce(event("subagent.start", """{"subagent_id":"s1","goal":"Write tests","task_index":1,"model":"m"}"""))
            .reduce(event("subagent.tool", """{"subagent_id":"s1","goal":"Write tests","tool_name":"terminal","tool_preview":"gradle test"}"""))
            .reduce(event("subagent.complete", """{"subagent_id":"s1","goal":"Write tests","status":"completed","summary":"All green.","duration_seconds":12.5}"""))
        val row = state.rows()[1]
        assertEquals("s1", row.key)
        assertEquals(SubagentStatus.Done, row.status)
        assertEquals(listOf("terminal: gradle test"), row.activity)
        assertEquals("All green.", row.summary)
        assertEquals("m", row.model)
        assertNull(row.subagentId) // finished, nothing to stop
        assertEquals(SubagentStatus.Running, state.rows()[0].status)
    }

    @Test
    fun aRunningSubagentCanBeStopped() {
        val state = delegating.reduce(event("subagent.start", """{"subagent_id":"s1","goal":"Read the docs"}"""))
        assertEquals("s1", state.rows()[0].subagentId)
    }

    @Test
    fun aSubagentsOwnChildrenNestUnderIt() {
        val state = delegating
            .reduce(event("subagent.start", """{"subagent_id":"s1","goal":"Read the docs"}"""))
            .reduce(event("subagent.start", """{"subagent_id":"s2","parent_id":"s1","goal":"Skim chapter 2","depth":1}"""))
        val row = state.rows()[0]
        assertEquals(listOf("Skim chapter 2"), row.children.map { it.goal })
        assertEquals(2, state.rows().size)
    }

    @Test
    fun anUnknownEndIsAFailureNotAForeverSpinner() {
        val state = delegating.reduce(event("subagent.complete", """{"subagent_id":"s1","goal":"Read the docs","status":"weird"}"""))
        assertEquals(SubagentStatus.Failed, state.rows()[0].status)
    }

    @Test
    fun theResultSettlesTasksNoSubagentReported() {
        val state = delegating.reduce(
            event(
                "tool.complete",
                """{"tool_id":"d1","name":"delegate_task","result":{"results":[
                    {"task_index":0,"status":"completed","summary":"Done reading."},
                    {"task_index":1,"status":"timeout","summary":"Timed out"}]}}""",
            ),
        )
        val rows = state.rows()
        assertEquals(listOf(SubagentStatus.Done, SubagentStatus.Failed), rows.map { it.status })
        assertEquals("Done reading.", rows[0].summary)
    }

    @Test
    fun aBackgroundDelegationIsNotShownAsRunning() {
        val state = delegating.reduce(
            event("tool.complete", """{"tool_id":"d1","name":"delegate_task","result":{"status":"dispatched","goals":["Read the docs","Write tests"]}}"""),
        )
        assertTrue(state.rows().all { it.status == SubagentStatus.Unwatched })
    }

    @Test
    fun aStoredDelegationReadsItsArgumentsAndResult() {
        val args = """{"goal":"Find the bug"}"""
        val result = """{"results":[{"task_index":0,"status":"failed","summary":null,"error":"No access"}]}"""
        val rows = listOf(
            SessionMessage(id = 1, role = "assistant", content = JsonPrimitive(""), toolCalls = buildJsonArray {
                add(buildJsonObject {
                    put("id", "c1")
                    put("function", buildJsonObject { put("name", "delegate_task"); put("arguments", args) })
                })
            }),
            SessionMessage(id = 2, role = "tool", content = JsonPrimitive(result), toolCallId = "c1"),
        )
        val call = (historyToMessages(rows).single() as ChatMessage.Assistant).tools.single()
        val shown = subagentRows(call, emptyList()).single()
        assertEquals("Find the bug", shown.goal)
        assertEquals(SubagentStatus.Failed, shown.status)
        assertEquals("No access", shown.summary)
    }

    @Test
    fun aSnapshotListedBeforeTheTranscriptStillFindsItsTask() {
        val listed = ChatState().withSubagentSnapshots(
            listOf(HermesJson.parseToJsonElement("""{"subagent_id":"s9","goal":"Write tests","status":"running","last_tool":"terminal"}""") as JsonObject),
        )
        val state = listed.copy(messages = delegating.messages)
        val row = state.rows()[1]
        assertEquals("s9", row.key)
        assertEquals(listOf("terminal"), row.activity)
    }

    @Test
    fun aSecondDelegationDoesNotClaimTheFirstOnesSubagents() {
        val first = delegating
            .reduce(event("subagent.start", """{"subagent_id":"s1","goal":"Read the docs"}"""))
            .reduce(event("tool.complete", """{"tool_id":"d1","name":"delegate_task","result":{"results":[{"task_index":0,"status":"completed"},{"task_index":1,"status":"completed"}]}}"""))
            .reduce(event("tool.start", """{"tool_id":"d2","name":"delegate_task","args":{"goal":"Another job"}}"""))
            .reduce(event("subagent.start", """{"subagent_id":"s2","goal":"Another job"}"""))
        val second = first.messages.filterIsInstance<ChatMessage.Assistant>().single().tools.single { it.id == "d2" }
        assertEquals(listOf("s2"), subagentRows(second, first.subagents).map { it.key })
    }
}
