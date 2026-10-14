package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.projects.Project
import dev.hermeskotlin.core.projects.ProjectIndex
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.threads.ChatThread
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadSectionsTest {

    private val site = Project("p-site", "website", "/srv/site", sessionCount = 2, sessionIds = setOf("site-chat", "site-old"))
    private val index = ProjectIndex(listOf(site))
    private val misc = ChatThread("t1", "Misc", "misc")
    private val web = ChatThread("t2", "Website", "site-old")
    private val rows = listOf(
        SessionSummary("misc", title = "Misc", pinned = true),
        // The website thread's chat was compressed into a new id.
        SessionSummary("site-tip", title = "Website", pinned = true, lineageIds = listOf("site-old", "site-tip")),
        SessionSummary("pinned", title = "A pinned chat", pinned = true),
        SessionSummary("plain", title = "A chat"),
    )

    @Test
    fun threadChatsShowOnlyInTheThreadsSection() {
        val sections = threadSections(listOf(misc, web), rows, rows, project = null, index = index)
        assertEquals(listOf("misc", "site-tip"), sections.threads.map { it.session.id })
        assertEquals(listOf("pinned", "plain"), sections.others.map { it.id })
        assertEquals(listOf("pinned"), sections.others.filter { it.pinned }.map { it.id })
    }

    @Test
    fun aThreadWhoseChatIsntListedYetStillShows() {
        val later = ChatThread("t3", "Later", "not-loaded")
        val row = threadSections(listOf(later), rows, rows, project = null, index = index).threads.single()
        assertEquals("not-loaded", row.session.id)
        assertEquals("Later", row.session.displayTitle)
    }

    @Test
    fun aPickedProjectShowsOnlyItsThreads() {
        val projectRows = listOf(SessionSummary("site-tip", lineageIds = listOf("site-old", "site-tip")), SessionSummary("site-chat"))
        val sections = threadSections(listOf(misc, web), projectRows, rows, project = site, index = index)
        assertEquals(listOf("t2"), sections.threads.map { it.thread.id })
        assertEquals(listOf("site-chat"), sections.others.map { it.id })
    }
}
