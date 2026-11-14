package dev.hermeskotlin.android

import dev.hermeskotlin.core.chat.AppLink
import dev.hermeskotlin.core.chat.ChatLinks
import dev.hermeskotlin.core.cron.ScheduledLink
import dev.hermeskotlin.core.cron.ScheduledLinks
import dev.hermeskotlin.core.kanban.links.KanbanLink
import dev.hermeskotlin.core.kanban.links.KanbanLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * B2: `hermes://kanban`, `hermes://kanban/<board>/<task>`, `hermes://scheduled`,
 * `hermes://scheduled/<job>` and `hermes://now` route to their own link buses — the chat bus
 * stays chat-only. Each bus keeps only its latest link, so a pending value asserts the routing.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = dev.hermeskotlin.android.widget.WidgetTestApp::class)
class RouteAppLinkTest {

    private fun route(link: AppLink): Triple<ChatLinks, KanbanLinks, ScheduledLinks> {
        val chats = ChatLinks()
        val kanban = KanbanLinks()
        val scheduled = ScheduledLinks()
        routeAppLink(link, chats, kanban, scheduled)
        return Triple(chats, kanban, scheduled)
    }

    @Test
    fun kanbanLinkOpensThePinnedBoard() {
        val (_, kanban, scheduled) = route(AppLink.Kanban)
        assertEquals(KanbanLink(null, null), kanban.pending.value)
        assertNull(scheduled.pending.value)
    }

    @Test
    fun kanbanTaskLinkOpensThatBoardAndTask() {
        val (_, kanban, _) = route(AppLink.KanbanTask("waypoint", "t-42"))
        assertEquals(KanbanLink("waypoint", "t-42"), kanban.pending.value)
    }

    @Test
    fun scheduledLinkOpensThePage() {
        val (_, kanban, scheduled) = route(AppLink.Scheduled)
        assertEquals(ScheduledLink(null, null), scheduled.pending.value)
        assertNull(kanban.pending.value)
    }

    @Test
    fun scheduledJobLinkOpensThatJob() {
        val (_, _, scheduled) = route(AppLink.ScheduledJob("job-9"))
        assertEquals(ScheduledLink("job-9", null), scheduled.pending.value)
    }

    @Test
    fun nowLinkIsANoOpThatOpensTheApp() {
        val (chats, kanban, scheduled) = route(AppLink.Now)
        assertNull(chats.pending.value)
        assertNull(kanban.pending.value)
        assertNull(scheduled.pending.value)
    }

    @Test
    fun chatLinksStillGoToTheChatBus() {
        val (chats, kanban, scheduled) = route(AppLink.Session("s-1"))
        assertEquals("s-1", chats.pending.value?.storedSessionId)
        assertNull(kanban.pending.value)
        assertNull(scheduled.pending.value)
    }
}
