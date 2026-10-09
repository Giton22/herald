package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.Attachment
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.TodoItem
import dev.hermeskotlin.core.chat.TodoList
import dev.hermeskotlin.core.chat.TodoStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatStatusTest {

    private fun plan(vararg statuses: TodoStatus) =
        TodoList(statuses.mapIndexed { i, s -> TodoItem("$i", "Step $i", s) }, revision = 1)

    private fun status(state: ChatState, connected: Boolean = true, place: String? = "homelab") =
        chatStatus(state, connected, connectionLabel = "Reconnecting…", place = place)

    @Test
    fun beingOfflineOutranksEverything() {
        val state = ChatState(running = true, inputRequests = listOf(InputRequest.Clarify("r1", emptyList())))

        assertEquals(BarStatus("Reconnecting…", StatusTone.Trouble), status(state, connected = false))
    }

    @Test
    fun openingThenAnAnswerThenWork() {
        val asking = ChatState(running = true, inputRequests = listOf(InputRequest.Clarify("r1", emptyList())))

        assertEquals(BarStatus("Opening…", StatusTone.Busy), status(asking.copy(attachment = Attachment.Attaching)))
        assertEquals(BarStatus("Needs your answer", StatusTone.Waiting), status(asking))
        assertEquals(BarStatus("Working", StatusTone.Busy), status(ChatState(running = true)))
    }

    @Test
    fun aLivePlanNamesTheStepInHand() {
        val state = ChatState(
            running = true,
            todos = plan(TodoStatus.Completed, TodoStatus.InProgress, TodoStatus.Pending, TodoStatus.Cancelled),
            todosLive = true,
        )

        // The cancelled step counts on neither side.
        assertEquals(BarStatus("Working · step 2 of 3", StatusTone.Busy), status(state))
    }

    @Test
    fun aPlanWithNothingLeftIsJustWork() {
        val allDone = ChatState(running = true, todos = plan(TodoStatus.Completed, TodoStatus.Completed), todosLive = true)
        val allCancelled = ChatState(running = true, todos = plan(TodoStatus.Cancelled), todosLive = true)
        val empty = ChatState(running = true, todos = TodoList(emptyList(), revision = 2), todosLive = true)

        listOf(allDone, allCancelled, empty).forEach { assertEquals(BarStatus("Working", StatusTone.Busy), status(it)) }
    }

    @Test
    fun aPlanFromAnEarlierTurnIsNotCounted() {
        val state = ChatState(running = true, todos = plan(TodoStatus.Completed, TodoStatus.InProgress), todosLive = false)

        assertEquals(BarStatus("Working", StatusTone.Busy), status(state))
    }

    @Test
    fun idleSaysWhereHermesRuns() {
        assertEquals(BarStatus("Hermes · homelab", StatusTone.Ok), status(ChatState()))
        assertEquals(BarStatus("Hermes", StatusTone.Ok), status(ChatState(), place = null))
    }

    @Test
    fun theGreetingNamesThePlaceAndProfile() {
        assertEquals("Hermes on homelab · work profile", greetingPlace("homelab", "work"))
        assertEquals("Hermes on homelab · default profile", greetingPlace("homelab", null))
        assertEquals("Hermes · default profile", greetingPlace(null, null))
    }
}
