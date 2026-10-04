package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.TodoItem
import dev.hermeskotlin.core.chat.TodoList
import dev.hermeskotlin.core.chat.TodoStatus
import dev.hermeskotlin.core.chat.ToolActivity
import kotlin.test.Test
import kotlin.test.assertEquals

class CurrentActionTest {

    private val plan = TodoList(
        listOf(
            TodoItem("1", "Check the disks", TodoStatus.Completed),
            TodoItem("2", "Measure storage growth", TodoStatus.InProgress),
        ),
        revision = 1,
    )

    private fun running(tool: ToolActivity? = null, todos: TodoList? = null, status: String? = null) = ChatState(
        running = true,
        status = status,
        todos = todos,
        todosLive = todos != null,
        messages = listOf(ChatMessage.Assistant("a1", streaming = true, tools = listOfNotNull(tool))),
    )

    @Test
    fun aRunningToolComesFirstInPlainWords() {
        val tool = ToolActivity("t1", "terminal", detail = "df -h\nmore", running = true)

        assertEquals("Running a command: df -h", currentAction(running(tool, plan, "musing…")))
    }

    @Test
    fun aPromptQueuedBehindTheReplyDoesNotHideItsTool() {
        val tool = ToolActivity("t1", "terminal", detail = "sleep 25", running = true)
        val state = running(tool, plan).let { it.copy(messages = it.messages + ChatMessage.User("u2", "Then say hi", queued = true)) }

        assertEquals("Running a command: sleep 25", currentAction(state))
    }

    @Test
    fun withoutAToolThePlanStepThenTheStatusThenThinking() {
        assertEquals("Measure storage growth", currentAction(running(todos = plan, status = "musing…")))
        assertEquals("musing…", currentAction(running(status = "musing…")))
        assertEquals("Thinking…", currentAction(running()))
    }

    @Test
    fun aPlanLeftFromAnEarlierTurnIsNotTheStepInHand() {
        // Stopped mid-plan, then asked something else: that turn hasn't planned anything.
        val state = running(todos = plan, status = "musing…").copy(todosLive = false)

        assertEquals("musing…", currentAction(state))
    }

    @Test
    fun aQuestionWaitingOnTheUserOutranksEverything() {
        val tool = ToolActivity("t1", "terminal", running = true)
        val state = running(tool).copy(inputRequests = listOf(InputRequest.Clarify("r1", emptyList())))

        assertEquals("Waiting for your answer", currentAction(state))
    }
}
