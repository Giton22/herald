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
    fun thinkingIsTheLiveGlanceNotTheLog() {
        val thinking = delegating
            .reduce(event("subagent.start", """{"subagent_id":"s1","goal":"Read the docs"}"""))
            .reduce(event("subagent.thinking", """{"subagent_id":"s1","text":"(o_o) pondering..."}"""))
            .reduce(event("subagent.progress", """{"subagent_id":"s1","text":"[set 1] terminal"}"""))
        assertEquals("(o_o) pondering...", thinking.rows()[0].thinking)
        assertTrue(thinking.rows()[0].activity.isEmpty())
        val tooling = thinking.reduce(event("subagent.tool", """{"subagent_id":"s1","tool_name":"terminal","tool_preview":"ls"}"""))
        assertNull(tooling.rows()[0].thinking)
        assertEquals(listOf("terminal: ls"), tooling.rows()[0].activity)
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
    fun aBackgroundReportSettlesItsCardAndShowsAsALine() {
        val args = """{"tasks":[{"goal":"Run date"},{"goal":"List files"}]}"""
        val dispatched = """{"status":"dispatched","mode":"background","count":2,"delegation_id":"deleg_ab12","goals":["Run date","List files"]}"""
        val report = listOf(
            "[ASYNC DELEGATION BATCH COMPLETE — deleg_ab12]",
            "A background fan-out unit finished.",
            "",
            "Role: leaf   Model: m   Total duration: 9.1s",
            "",
            "--- ✓ TASK 1/2: Run date  (status=completed, api_calls=2, 7.0s) ---",
            "Sat Oct  3 11:26:48 UTC 2026",
            "Full live transcript (complete tool/assistant trace): /opt/data/task-0.log",
            "",
            "--- ✗ TASK 2/2: List files  (status=timeout, 9.1s) ---",
            "(no summary — status=timeout)",
        ).joinToString("\n")
        val rows = listOf(
            SessionMessage(id = 1, role = "user", content = JsonPrimitive("Delegate two things")),
            SessionMessage(id = 2, role = "assistant", content = JsonPrimitive(""), toolCalls = buildJsonArray {
                add(buildJsonObject {
                    put("id", "c1")
                    put("function", buildJsonObject { put("name", "delegate_task"); put("arguments", args) })
                })
            }),
            SessionMessage(id = 3, role = "tool", content = JsonPrimitive(dispatched), toolCallId = "c1"),
            SessionMessage(id = 4, role = "assistant", content = JsonPrimitive("Both are running.")),
            SessionMessage(id = 5, role = "user", content = JsonPrimitive(report)),
        )
        val messages = historyToMessages(rows)
        val call = messages.filterIsInstance<ChatMessage.Assistant>().first().tools.single()
        val shown = subagentRows(call, emptyList())
        assertEquals(listOf(SubagentStatus.Done, SubagentStatus.Failed), shown.map { it.status })
        assertEquals("Sat Oct  3 11:26:48 UTC 2026", shown[0].summary)
        assertNull(shown[1].summary) // "(no summary — status=timeout)" only repeats the status
        val line = messages.last() as ChatMessage.Notice
        assertEquals("2 background tasks finished, 1 failed", line.text)
        assertTrue(line.stored)
        assertTrue(messages.none { it is ChatMessage.User && it.text.startsWith("[ASYNC") })
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
