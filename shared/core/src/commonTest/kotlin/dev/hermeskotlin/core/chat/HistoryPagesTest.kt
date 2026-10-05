package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.sessions.SessionMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryPagesTest {

    private fun row(id: Long, role: String = if (id % 2 == 1L) "user" else "assistant") = SessionMessage(id = id, role = role)

    private fun ids(rows: List<SessionMessage>?) = rows?.map { it.id }

    @Test
    fun aFullPageStartsAtItsFirstPrompt() {
        val page = listOf(row(4, "tool"), row(5, "assistant"), row(6, "user"), row(7, "assistant"))

        assertEquals(listOf(6L, 7L), ids(page.fromFirstTurn()))
    }

    @Test
    fun aPageWithoutAPromptIsKeptWhole() {
        val page = listOf(row(4, "assistant"), row(5, "tool"), row(6, "assistant"))

        assertEquals(listOf(4L, 5L, 6L), ids(page.fromFirstTurn()))
    }

    @Test
    fun aHiddenPromptIsNoTurnStart() {
        val page = listOf(row(4, "assistant"), SessionMessage(id = 5, role = "user", displayKind = "hidden"), row(6, "assistant"), row(7, "user"))

        assertEquals(listOf(7L), ids(page.fromFirstTurn()))
    }

    @Test
    fun theNewestPageReplacesTheTailItOverlaps() {
        val loaded = (1L..6L).map { row(it) }

        assertEquals((1L..8L).toList(), ids(loaded.withNewest((5L..8L).map { row(it) })))
    }

    @Test
    fun rowsAnUndoTookAreDropped() {
        val loaded = (1L..6L).map { row(it) }

        // Rows 5 and 6 were undone; the page reaches back to 3 and ends there.
        assertEquals((1L..4L).toList(), ids(loaded.withNewest((3L..4L).map { row(it) })))
    }

    @Test
    fun aPageThatDoesntReachTheLoadedRowsCantBeJoined() {
        assertNull((1L..6L).map { row(it) }.withNewest((9L..12L).map { row(it) }))
        assertEquals(listOf(9L), ids(emptyList<SessionMessage>().withNewest(listOf(row(9)))))
    }

    @Test
    fun aShortNewestPageIsTheWholeTranscript() {
        val loaded = (1L..100L).map { row(it) }

        assertEquals(listOf(1L, 2L), ids(loaded.afterNewestPage(listOf(row(1), row(2)))))
    }
}
