package dev.hermeskotlin.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTurnTest {

    private fun reply(vararg tools: ToolActivity, streaming: Boolean = true) =
        ChatMessage.Assistant(key = "reply", tools = tools.toList(), streaming = streaming)

    private fun running(vararg tools: ToolActivity) = ChatState(running = true, messages = listOf(reply(*tools)))

    @Test
    fun noTurnRunsSoThereIsNothingToShow() {
        assertNull(ChatState(messages = listOf(reply(ToolActivity("1", "terminal", running = true)))).liveTurn())
    }

    @Test
    fun aTurnThatOnlyThinks() {
        val live = ChatState(running = true, messages = listOf(reply())).liveTurn()!!
        assertEquals(LiveTurnKind.Thinking, live.kind)
        assertEquals(0, live.tools)
        assertNull(live.tool)
        assertNull(live.detail)
        assertNull(live.status)
    }

    @Test
    fun aRunningToolSaysWhatTheGatewaySaysItDoes() {
        val live = running(ToolActivity("1", "terminal", detail = "Running the tests", running = true)).liveTurn()!!
        assertEquals(LiveTurnKind.Tool, live.kind)
        assertEquals("terminal", live.tool)
        assertEquals("Running the tests", live.detail)
        assertEquals(1, live.tools)
    }

    @Test
    fun aToolWithoutADetailUsesItsFirstInputLine() {
        val live = running(ToolActivity("1", "terminal", input = "  ./gradlew test\n./gradlew check", running = true)).liveTurn()!!
        assertEquals("./gradlew test", live.detail)
    }

    @Test
    fun aToolGivenJsonHasNoDetailLine() {
        assertNull(running(ToolActivity("1", "patch", input = """{"path":"build.gradle"}""", running = true)).liveTurn()!!.detail)
        assertNull(running(ToolActivity("1", "patch", input = "  [1, 2, 3]", running = true)).liveTurn()!!.detail)
    }

    @Test
    fun aMultiLineDetailGivesItsFirstLine() {
        val live = running(ToolActivity("1", "terminal", detail = "  Running the tests \nsecond line", running = true)).liveTurn()!!
        assertEquals("Running the tests", live.detail)
    }

    @Test
    fun aLongInputLineIsClipped() {
        val line = "x".repeat(200)
        val live = running(ToolActivity("1", "terminal", input = "$line\nmore", running = true)).liveTurn()!!
        assertEquals(161, live.detail!!.length)
        assertTrue(live.detail.startsWith("x".repeat(160)))
        assertTrue(live.detail.endsWith("…"))
    }

    @Test
    fun theCountIncludesFinishedToolsOfTheTurn() {
        val live = running(
            ToolActivity("1", "read_file", running = false),
            ToolActivity("2", "terminal", running = true),
        ).liveTurn()!!
        assertEquals(2, live.tools)
        assertEquals("terminal", live.tool)
    }

    @Test
    fun waitingOnTheUserBeatsARunningTool() {
        val state = running(ToolActivity("1", "terminal", running = true)).copy(
            inputRequests = listOf(InputRequest.Approval("req", "rm -rf build", "Delete the build folder", null, ApprovalChoice.entries)),
        )
        val live = state.liveTurn()!!
        assertEquals(LiveTurnKind.Waiting, live.kind)
    }

    @Test
    fun theGatewayStatusLineIsUsedWhenNothingElseIs() {
        val live = ChatState(running = true, status = "Compacting the conversation", messages = listOf(reply())).liveTurn()!!
        assertEquals(LiveTurnKind.Status, live.kind)
        assertEquals("Compacting the conversation", live.status)
    }

    @Test
    fun thePlanStepIsThereOnlyWhileThePlanIsLiveAndUnfinished() {
        val plan = TodoList(
            items = listOf(
                TodoItem("1", "Read the issue", TodoStatus.Completed),
                TodoItem("2", "Write the code", TodoStatus.InProgress),
                TodoItem("3", "Test it", TodoStatus.Pending),
            ),
            revision = 1,
        )
        val live = ChatState(running = true, messages = listOf(reply()), todos = plan, todosLive = true).liveTurn()!!
        assertEquals(2, live.step)
        assertEquals(3, live.steps)

        assertNull(ChatState(running = true, messages = listOf(reply()), todos = plan, todosLive = false).liveTurn()!!.step)
        val finished = plan.copy(items = plan.items.map { it.copy(status = TodoStatus.Completed) })
        assertNull(ChatState(running = true, messages = listOf(reply()), todos = finished, todosLive = true).liveTurn()!!.step)
    }

    @Test
    fun theLivePlansStepBeatsTheGatewayStatus() {
        val plan = TodoList(
            items = listOf(
                TodoItem("1", "Read the issue", TodoStatus.Completed),
                TodoItem("2", "Implement the fix", TodoStatus.InProgress),
                TodoItem("3", "Test it", TodoStatus.Pending),
            ),
            revision = 1,
        )
        val live = ChatState(
            running = true,
            status = "Compacting the conversation",
            messages = listOf(reply()),
            todos = plan,
            todosLive = true,
        ).liveTurn()!!
        assertEquals(LiveTurnKind.Plan, live.kind)
        assertEquals("Implement the fix", live.stepText)
        assertEquals(2, live.step)

        val stalePlan = ChatState(
            running = true,
            status = "Compacting the conversation",
            messages = listOf(reply()),
            todos = plan,
            todosLive = false,
        ).liveTurn()!!
        assertEquals(LiveTurnKind.Status, stalePlan.kind)
        assertNull(stalePlan.stepText)
    }

    @Test
    fun onlyTheLastFourToolsAreOnTheCardOldestFirst() {
        val live = running(
            ToolActivity("1", "read_file", running = false),
            ToolActivity("2", "terminal", running = false),
            ToolActivity("3", "patch", running = false),
            ToolActivity("4", "web_extract", running = true),
            ToolActivity("5", "browser", running = false, failed = true),
            ToolActivity("6", "write_file", running = false),
        ).liveTurn()!!
        assertEquals(6, live.tools)
        assertEquals(listOf("patch", "web_extract", "browser", "write_file"), live.recent.map { it.name })
        assertEquals(
            listOf(LiveToolState.Done, LiveToolState.Running, LiveToolState.Failed, LiveToolState.Done),
            live.recent.map { it.state },
        )
    }

    @Test
    fun aFailedToolIsMarkedFailed() {
        val live = running(ToolActivity("1", "web_extract", detail = "timed out", running = false, failed = true)).liveTurn()!!
        assertEquals(LiveToolState.Failed, live.recent.single().state)
        assertEquals("timed out", live.recent.single().line)
    }

    @Test
    fun theToolLinesComeFromTheDetailAndThenANonJsonInput() {
        val live = running(
            ToolActivity("1", "read_file", detail = "LiveTurn.kt", running = false),
            ToolActivity("2", "terminal", input = "  ./gradlew test\n./gradlew check", running = true),
        ).liveTurn()!!
        assertEquals(listOf("read_file", "terminal"), live.recent.map { it.name })
        assertEquals(listOf("LiveTurn.kt", "./gradlew test"), live.recent.map { it.line })
        assertEquals(listOf(LiveToolState.Done, LiveToolState.Running), live.recent.map { it.state })
    }

    @Test
    fun theToolLinesSpanAMidTurnCorrection() {
        val first = ChatMessage.Assistant(
            key = "first",
            tools = listOf(ToolActivity("1", "read_file", detail = "LiveTurn.kt", running = false)),
            streaming = false,
        )
        val state = ChatState(
            running = true,
            messages = listOf(
                ChatMessage.User(key = "prompt", text = "Fix it"),
                first,
                ChatMessage.Notice(key = "notice", text = "Correction received"),
                reply(ToolActivity("2", "terminal", detail = "gradlew assembleDebug", running = true)),
            ),
        )
        val live = state.liveTurn()!!
        assertEquals(listOf("read_file", "terminal"), live.recent.map { it.name })
        assertEquals(listOf("LiveTurn.kt", "gradlew assembleDebug"), live.recent.map { it.line })
    }

    @Test
    fun theToolLinesSpanACorrectionAppendedAsAPrompt() {
        val sealed = ChatMessage.Assistant(
            key = "sealed",
            tools = listOf(ToolActivity("1", "read_file", detail = "LiveTurn.kt", running = false)),
            streaming = false,
        )
        val state = ChatState(
            running = true,
            messages = listOf(
                ChatMessage.User(key = "prompt", text = "Fix it"),
                sealed,
                ChatMessage.User(key = "steer", text = "Also update the test"),
                reply(ToolActivity("2", "terminal", detail = "gradlew assembleDebug", running = true)),
            ),
            correctedReplyKey = "sealed",
        )
        val live = state.liveTurn()!!
        assertEquals(2, live.tools)
        assertEquals(listOf("read_file", "terminal"), live.recent.map { it.name })
        assertEquals(listOf("LiveTurn.kt", "gradlew assembleDebug"), live.recent.map { it.line })
    }

    @Test
    fun theCountSpansAMidTurnCorrection() {
        val first = ChatMessage.Assistant(
            key = "first",
            tools = listOf(ToolActivity("1", "read_file", running = false)),
            streaming = false,
        )
        val state = ChatState(
            running = true,
            messages = listOf(
                ChatMessage.User(key = "prompt", text = "Fix it"),
                first,
                ChatMessage.Notice(key = "notice", text = "Correction received"),
                reply(ToolActivity("2", "terminal", running = true)),
            ),
        )
        val live = state.liveTurn()!!
        assertEquals(2, live.tools)
        assertEquals("terminal", live.tool)
    }
}
