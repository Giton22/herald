package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
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

    @Test
    fun thePlanStepIsTheOneInHand() {
        assertEquals(2, planStep(plan(TodoStatus.Completed, TodoStatus.InProgress, TodoStatus.Pending)))
        assertNull(planStep(plan(TodoStatus.Completed, TodoStatus.Completed)))
        assertNull(planStep(null))
    }

    @Test
    fun theTurnStartedWithThePromptItAnswers() {
        val state = ChatState(
            running = true,
            messages = listOf(
                ChatMessage.User("u1", "first", timestamp = 100.0),
                ChatMessage.Assistant("a1", timestamp = 110.0),
                ChatMessage.User("u2", "second", timestamp = 200.0),
                ChatMessage.Assistant("a2", streaming = true),
                ChatMessage.User("u3", "later", queued = true, timestamp = 250.0),
            ),
        )

        assertEquals(200.0, turnStartedAt(state))
        assertNull(turnStartedAt(state.copy(running = false)))
        // A turn whose reply hasn't started streaming here can't be timed.
        assertNull(turnStartedAt(state.copy(messages = state.messages.take(3))))
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
