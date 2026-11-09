package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.Attachment
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.TodoItem
import dev.hermeskotlin.core.chat.TodoList
import dev.hermeskotlin.core.chat.TodoStatus
import dev.hermeskotlin.ui.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals

/** The status line's choice of words; the words themselves are the catalogue's. */
class ChatStatusTest {

    private fun plan(vararg statuses: TodoStatus) =
        TodoList(statuses.mapIndexed { i, s -> TodoItem("$i", "Step $i", s) }, revision = 1)

    private fun status(state: ChatState, connected: Boolean = true, place: String? = "homelab") =
        chatStatusPick(state, connected, connectionLabel = "Reconnecting…", place = place)

    private val working = ChatStatusPick(Words.Of(Res.string.chat_status_working), StatusTone.Busy)

    @Test
    fun beingOfflineOutranksEverything() {
        val state = ChatState(running = true, inputRequests = listOf(InputRequest.Clarify("r1", emptyList())))

        assertEquals(ChatStatusPick(Words.Said("Reconnecting…"), StatusTone.Trouble), status(state, connected = false))
    }

    @Test
    fun openingThenAnAnswerThenWork() {
        val asking = ChatState(running = true, inputRequests = listOf(InputRequest.Clarify("r1", emptyList())))

        assertEquals(ChatStatusPick(Words.Of(Res.string.chat_screen_opening), StatusTone.Busy), status(asking.copy(attachment = Attachment.Attaching)))
        assertEquals(ChatStatusPick(Words.Of(Res.string.chat_status_needs_answer), StatusTone.Waiting), status(asking))
        assertEquals(working, status(ChatState(running = true)))
    }

    @Test
    fun aLivePlanNamesTheStepInHand() {
        val state = ChatState(
            running = true,
            todos = plan(TodoStatus.Completed, TodoStatus.InProgress, TodoStatus.Pending, TodoStatus.Cancelled),
            todosLive = true,
        )

        // The cancelled step counts on neither side.
        assertEquals(ChatStatusPick(Words.Of(Res.string.chat_status_working_step, listOf(2, 3)), StatusTone.Busy), status(state))
    }

    @Test
    fun aPlanWithNothingLeftIsJustWork() {
        val allDone = ChatState(running = true, todos = plan(TodoStatus.Completed, TodoStatus.Completed), todosLive = true)
        val allCancelled = ChatState(running = true, todos = plan(TodoStatus.Cancelled), todosLive = true)
        val empty = ChatState(running = true, todos = TodoList(emptyList(), revision = 2), todosLive = true)

        listOf(allDone, allCancelled, empty).forEach { assertEquals(working, status(it)) }
    }

    @Test
    fun aPlanFromAnEarlierTurnIsNotCounted() {
        val state = ChatState(running = true, todos = plan(TodoStatus.Completed, TodoStatus.InProgress), todosLive = false)

        assertEquals(working, status(state))
    }

    @Test
    fun idleSaysWhereHermesRuns() {
        assertEquals(ChatStatusPick(Words.Said("Hermes · homelab"), StatusTone.Ok), status(ChatState()))
        assertEquals(ChatStatusPick(Words.Said("Hermes"), StatusTone.Ok), status(ChatState(), place = null))
    }

    @Test
    fun theGreetingNamesThePlaceAndProfile() {
        assertEquals(Words.Of(Res.string.chat_greeting_place_profile, listOf("homelab", "work")), greetingPlace("homelab", "work"))
        assertEquals(Words.Of(Res.string.chat_greeting_place_default, listOf("homelab")), greetingPlace("homelab", null))
        assertEquals(Words.Of(Res.string.chat_greeting_profile, listOf("work")), greetingPlace(null, "work"))
        assertEquals(Words.Of(Res.string.chat_greeting_default), greetingPlace(null, null))
    }
}
