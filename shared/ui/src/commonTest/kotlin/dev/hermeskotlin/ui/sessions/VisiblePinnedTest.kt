package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.threads.ChatThread
import kotlin.test.Test
import kotlin.test.assertEquals

class VisiblePinnedTest {

    private val pinned = listOf("idle", "open", "running", "waiting", "unread", "draft").map { SessionSummary(id = it, pinned = true) }
    private val statuses = mapOf(
        "running" to RowStatus(running = true),
        "waiting" to RowStatus(waiting = Waiting.Question),
        "unread" to RowStatus(unread = true),
        "idle" to RowStatus(),
    )

    @Test
    fun openShowsEveryPinnedChat() {
        assertEquals(pinned, visiblePinned(pinned, collapsed = false, selectedId = null, statuses, drafts = emptySet()))
    }

    @Test
    fun foldedKeepsTheOpenChatAndThoseWithSomethingGoingOn() {
        val shown = visiblePinned(pinned, collapsed = true, selectedId = "open", statuses, drafts = setOf("draft"))
        assertEquals(listOf("open", "running", "waiting", "unread", "draft"), shown.map { it.id })
    }

    @Test
    fun foldedWithNothingGoingOnShowsNone() {
        assertEquals(emptyList(), visiblePinned(pinned, collapsed = true, selectedId = null, emptyMap(), emptySet()))
    }

    private val threads = pinned.map { ThreadRow(ChatThread(id = "t-${it.id}", name = it.id, sessionId = it.id), it) }

    @Test
    fun openShowsEveryThread() {
        assertEquals(threads, visibleThreads(threads, collapsed = false, selectedId = null, statuses, drafts = emptySet()))
    }

    @Test
    fun foldedKeepsTheOpenThreadAndThoseWithSomethingGoingOn() {
        val shown = visibleThreads(threads, collapsed = true, selectedId = "open", statuses, drafts = setOf("draft"))
        assertEquals(listOf("open", "running", "waiting", "unread", "draft"), shown.map { it.session.id })
    }

    @Test
    fun foldedKeepsAThreadOpenByTheIdItKeeps() {
        // The thread's chat was compressed into a new session; the open chat is still the one the thread keeps.
        val compressed = ThreadRow(ChatThread(id = "t", name = "Notes", sessionId = "old"), SessionSummary(id = "new"))
        assertEquals(listOf(compressed), visibleThreads(listOf(compressed), collapsed = true, selectedId = "old", emptyMap(), emptySet()))
    }
}
