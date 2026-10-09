package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.Subagent
import dev.hermeskotlin.core.chat.SubagentStatus
import dev.hermeskotlin.core.chat.TodoItem
import dev.hermeskotlin.core.chat.TodoList
import dev.hermeskotlin.core.chat.TodoStatus
import dev.hermeskotlin.core.chat.ToolActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveTaskTest {

    private fun plan(vararg statuses: TodoStatus) =
        TodoList(statuses.mapIndexed { i, s -> TodoItem("$i", "Step $i", s) }, revision = 1)

    @Test
    fun aRunningToolSplitsIntoWhatAndWhere() {
        val tool = ToolActivity("t1", "read_file", detail = "/opt/backup/backup.sh\nmore", running = true)
        val state = ChatState(running = true, messages = listOf(ChatMessage.Assistant("a1", streaming = true, tools = listOf(tool))))

        assertEquals(LiveStep("Reading a file", "/opt/backup/backup.sh"), currentStep(state))
        assertEquals("Reading a file: /opt/backup/backup.sh", currentAction(state))
    }

    private fun delegating(vararg statuses: SubagentStatus): ChatState {
        val tool = ToolActivity("t1", "delegate_task", running = true)
        return ChatState(
            running = true,
            messages = listOf(ChatMessage.Assistant("a1", streaming = true, tools = listOf(tool))),
            subagents = statuses.mapIndexed { i, s -> Subagent("s$i", toolId = "t1", goal = "Task $i", status = s, taskIndex = i) },
        )
    }

    @Test
    fun aDelegationCountsItsSubagents() {
        val state = delegating(SubagentStatus.Done, SubagentStatus.Running, SubagentStatus.Running)

        val step = currentStep(state)
        assertEquals(listOf(SubagentStatus.Done, SubagentStatus.Running, SubagentStatus.Running), step.team)
        assertEquals("1 of 3 done", step.teamProgress)
        assertEquals("Working with subagents · 1 of 3 done", currentAction(state))
        assertEquals("Working · 2 subagents", chatStatus(state, connected = true, connectionLabel = "", place = null).text)
    }

    @Test
    fun aSubagentThatFailedIsFinishedNotDone() {
        assertEquals("2 of 2 finished", currentStep(delegating(SubagentStatus.Done, SubagentStatus.Failed)).teamProgress)
    }

    @Test
    fun subagentsOfAnEarlierTurnDoNotCountNow() {
        // A live subagent whose end never arrived, from a delegation no longer running.
        val state = delegating(SubagentStatus.Running).copy(messages = listOf(ChatMessage.Assistant("a2", streaming = true)))

        assertNull(currentStep(state).teamProgress)
        assertEquals("Working", chatStatus(state, connected = true, connectionLabel = "", place = null).text)
    }

    @Test
    fun thePlanStepIsTheOneInHand() {
        assertEquals(2, planStep(plan(TodoStatus.Completed, TodoStatus.InProgress, TodoStatus.Pending)))
        assertNull(planStep(plan(TodoStatus.Completed, TodoStatus.Completed)))
        assertNull(planStep(null))
    }

    @Test
    fun theRunningTurnIsItsStreamingReply() {
        val state = ChatState(
            running = true,
            messages = listOf(
                ChatMessage.User("u1", "first"),
                ChatMessage.Assistant("a1"),
                ChatMessage.User("u2", "second"),
                ChatMessage.Assistant("a2", streaming = true),
                ChatMessage.User("u3", "later", queued = true),
            ),
        )

        assertEquals("a2", runningTurnKey(state))
        assertNull(runningTurnKey(state.copy(running = false)))
        // A turn whose reply hasn't opened here yet isn't timed.
        assertNull(runningTurnKey(state.copy(messages = state.messages.take(3))))
    }

    @Test
    fun aBlankDescriptionOrJsonListDoesNotHideOrBecomeTheDetail() {
        assertEquals(LiveStep("Ran", "uptime"), toolDone(ToolActivity("1", "terminal", detail = " ", input = "\nuptime")))
        assertEquals(LiveStep("Ran with terminal"), toolDone(ToolActivity("2", "terminal", input = """["a","b"]""")))
    }

    @Test
    fun finishedStepsAreSaidInThePast() {
        assertEquals(LiveStep("Ran", "df -h /mnt/nas"), toolDone(ToolActivity("1", "terminal", detail = "df -h /mnt/nas")))
        assertEquals(LiveStep("Read", "backup.log"), toolDone(ToolActivity("2", "read_file", detail = "backup.log")))
        assertEquals(LiveStep("Searched with web search"), toolDone(ToolActivity("3", "web_search")))
        // Without a description, a plain input says what it worked on; JSON arguments don't.
        assertEquals(LiveStep("Ran", "ls -la"), toolDone(ToolActivity("5", "terminal", input = "ls -la")))
        assertEquals(LiveStep("Ran with terminal"), toolDone(ToolActivity("6", "terminal", input = """{"command":"ls"}""")))
        assertEquals(LiveStep("Used some tool", "x"), toolDone(ToolActivity("4", "some_tool", detail = "x")))
    }

    @Test
    fun runningTimeReadsLikeAClock() {
        assertEquals("0:42", runningTime(42))
        assertEquals("12:05", runningTime(725))
        assertEquals("1:02:03", runningTime(3723))
        assertEquals("0:00", runningTime(-3))
    }
}
