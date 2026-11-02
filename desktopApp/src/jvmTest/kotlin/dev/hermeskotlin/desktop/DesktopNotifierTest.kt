package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.ClarifyQuestion
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.core.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopNotifierTest {

    private val prefs = AppSettings(notifyRequests = true, notifyReplies = true)
    private val question = InputRequest.Clarify("q1", listOf(ClarifyQuestion(null, "Which branch?\nmore")))

    private fun running(title: String? = "Release") = ChatState(title = title, storedSessionId = "s1", running = true)

    private fun done(text: String, outcome: TurnOutcome? = null) = running().copy(
        running = false,
        messages = listOf(ChatMessage.Assistant("a1", text = text, outcome = outcome)),
    )

    @Test
    fun aQuestionIsToldOnceWhileAway() {
        val notified = mutableSetOf<String>()
        val waiting = running().copy(inputRequests = listOf(question))
        val first = noticesFor(running(), waiting, focused = false, prefs, notified)
        assertEquals(listOf(DesktopNotice("Hermes has a question · Release", "Which branch?", "s1", "Release", question)), first)
        assertTrue(noticesFor(waiting, waiting, focused = false, prefs, notified).isEmpty())
    }

    @Test
    fun aQuestionSeenInTheWindowIsNotToldLater() {
        val notified = mutableSetOf<String>()
        val waiting = running().copy(inputRequests = listOf(question))
        assertTrue(noticesFor(running(), waiting, focused = true, prefs, notified).isEmpty())
        assertTrue(noticesFor(waiting, waiting, focused = false, prefs, notified).isEmpty())
    }

    @Test
    fun aFinishedReplyIsToldByItsFirstLine() {
        val notices = noticesFor(running(), done("\nAll green.\nDetails follow"), focused = false, prefs, mutableSetOf())
        assertEquals(listOf(DesktopNotice("Release", "All green.", "s1", "Release")), notices)
    }

    @Test
    fun aFailureSaysSo() {
        val notices = noticesFor(running(), done("boom", TurnOutcome.Error), focused = false, prefs, mutableSetOf())
        assertEquals("Hermes ran into a problem", notices.single().title)
    }

    @Test
    fun nothingWhileFocusedStoppedOrSwitchedOff() {
        assertTrue(noticesFor(running(), done("hi"), focused = true, prefs, mutableSetOf()).isEmpty())
        assertTrue(noticesFor(running(), done("hi", TurnOutcome.Interrupted), focused = false, prefs, mutableSetOf()).isEmpty())
        assertTrue(noticesFor(running(), done("hi"), focused = false, prefs.copy(notifyReplies = false), mutableSetOf()).isEmpty())
        val waiting = running().copy(inputRequests = listOf(question))
        assertTrue(noticesFor(running(), waiting, focused = false, prefs.copy(notifyRequests = false), mutableSetOf()).isEmpty())
    }
}
