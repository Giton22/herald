package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.kanban.links.KanbanLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * T11 (`docs/WIDGETS-SPEC.md` §11): the sidebar's Kanban-link effect opens the page (the composable's
 * `kanbanOpen` flag) and forwards board+task; the taken callback consumes the bus; the holder clears
 * once the drawer reports opened.
 */
class KanbanLinksConsumptionTest {

    @Test
    fun aTaskLinkForwardsBoardAndTaskAndClearsAfterOpened() {
        val links = PendingWidgetLinks()
        links.onKanbanLink(KanbanLink(board = "waypoint", taskId = "t_1"))
        assertEquals("waypoint" to "t_1", links.kanbanTask)
        links.onKanbanTaskOpened()
        assertNull(links.kanbanTask)
    }

    @Test
    fun aBoardOnlyLinkOpensThePageWithNothingPending() {
        val links = PendingWidgetLinks()
        links.onKanbanLink(KanbanLink(board = null, taskId = null))
        assertNull(links.kanbanTask)
    }

    @Test
    fun aNewerLinkReplacesThePendingTask() {
        val links = PendingWidgetLinks()
        links.onKanbanLink(KanbanLink(board = "waypoint", taskId = "t_1"))
        links.onKanbanLink(KanbanLink(board = "other", taskId = "t_2"))
        assertEquals("other" to "t_2", links.kanbanTask)
    }
}
