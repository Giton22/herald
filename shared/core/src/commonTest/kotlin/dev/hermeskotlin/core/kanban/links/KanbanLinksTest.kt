package dev.hermeskotlin.core.kanban.links

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** T10 (`docs/WIDGETS-SPEC.md` §11): publish/consume with the identity guard, as `InboxLinks` does it. */
class KanbanLinksTest {

    @Test
    fun publishAndConsume() = runTest {
        val links = KanbanLinks()
        assertNull(links.pending.value)
        val link = KanbanLink(board = "waypoint", taskId = "t_1")
        links.open(board = "waypoint", taskId = "t_1")
        assertEquals(link, links.pending.value)
        links.consume(link)
        assertNull(links.pending.value)
    }

    @Test
    fun anOlderConsumeNeverSwallowsANewerLink() = runTest {
        val links = KanbanLinks()
        val older = KanbanLink(board = "waypoint", taskId = "t_1")
        val newer = KanbanLink(board = null, taskId = null)
        links.open(board = "waypoint", taskId = "t_1")
        links.open()
        links.consume(older)
        assertEquals(newer, links.pending.value)
        links.consume(newer)
        assertNull(links.pending.value)
    }
}
