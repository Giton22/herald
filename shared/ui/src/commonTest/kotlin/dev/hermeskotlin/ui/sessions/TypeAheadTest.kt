package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.sessions.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TypeAheadTest {

    private val chats = listOf(
        SessionSummary("1", title = "Budget review"),
        SessionSummary("2", title = "brainstorm names"),
        SessionSummary("3", title = "Calendar sync"),
        SessionSummary("4", title = "Backup plan"),
    )

    @Test
    fun lettersTypedTogetherFindATitlesStart() {
        val typeAhead = TypeAhead()
        assertEquals("1", typeAhead.next('b', chats, now = 0)?.id)
        assertEquals("4", typeAhead.next('a', chats, now = 200)?.id)
        assertEquals("4", typeAhead.next('c', chats, now = 400)?.id)
    }

    @Test
    fun theSameLetterStepsThroughItsTitlesAndWraps() {
        val typeAhead = TypeAhead()
        assertEquals("1", typeAhead.next('b', chats, now = 0)?.id)
        assertEquals("2", typeAhead.next('b', chats, now = 100)?.id)
        assertEquals("4", typeAhead.next('b', chats, now = 200)?.id)
        assertEquals("1", typeAhead.next('b', chats, now = 300)?.id)
    }

    @Test
    fun aPauseStartsOver() {
        val typeAhead = TypeAhead()
        assertEquals("3", typeAhead.next('c', chats, now = 0)?.id)
        // "cb" matches nothing; a second later "b" is a new search.
        assertNull(typeAhead.next('b', chats, now = 300))
        assertEquals("1", typeAhead.next('b', chats, now = 2_000)?.id)
    }
}
