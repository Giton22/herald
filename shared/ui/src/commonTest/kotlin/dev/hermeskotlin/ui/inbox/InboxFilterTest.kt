package dev.hermeskotlin.ui.inbox

import dev.hermeskotlin.core.inbox.ChipMode
import dev.hermeskotlin.core.inbox.InboxApi
import dev.hermeskotlin.core.inbox.InboxPluginTooOld
import dev.hermeskotlin.core.inbox.InboxGroups
import dev.hermeskotlin.core.inbox.InboxLayout
import dev.hermeskotlin.core.inbox.InboxRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InboxFilterTest {

    private fun run(job: String, at: Long, label: String = job, status: String = InboxRun.OK, bot: String = "default") =
        InboxRun(bot = bot, job = job, run = "2026-10-12_00-00-${(at % 60).toString().padStart(2, '0')}", label = label, status = status, at = at)

    // Newest first, as the Inbox keeps them.
    private val runs = listOf(
        run("pulse", 500, "rubix-ticket-pulse"),
        run("brief", 400, "morning-briefing"),
        run("pulse", 300, "ticket pulse (old name)", status = InboxRun.FAILED),
        run("brief", 200, "morning-briefing"),
        run("brief", 100, "morning-briefing", bot = "siaulietis"),
    )

    @Test
    fun aChipPerJobTheOneThatReportedLastFirstNamedAsItIsNow() {
        val chips = jobChips(runs) { it.at > 250 }
        assertEquals(listOf("rubix-ticket-pulse", "morning-briefing", "morning-briefing"), chips.map { it.label })
        assertEquals(listOf(2, 2, 1), chips.map { it.runs })
        assertEquals(listOf(2, 1, 0), chips.map { it.unread })
        // The same job id in another profile is another job.
        assertEquals(listOf("default", "default", "siaulietis"), chips.map { it.filter.bot })
    }

    @Test
    fun filtersKeepTheirRunsInOrder() {
        assertEquals(runs, runs.filtered(InboxFilter.All))
        assertEquals(listOf(300L), runs.filtered(InboxFilter.Failed).map { it.at })
        assertEquals(listOf(400L, 200L), runs.filtered(InboxFilter.Job("default", "brief")).map { it.at })
    }

    @Test
    fun aJobThatLeftTheInboxShowsEverythingAgain() {
        val state = InboxUiState(runs = runs, filter = InboxFilter.Job("default", "gone"))
        assertEquals(InboxFilter.All, state.shownFilter)
        assertEquals(runs.size, state.shown.size)
        assertEquals(1, state.failed)
    }

    @Test
    fun groupChipsFollowTheUsersOrderThenOther() {
        val groups = InboxGroups().add("Personal").add("RUBIX").add("Empty")
            .assign("default", "pulse", "RUBIX").assign("default", "brief", "Personal")
        val chips = groupChips(runs, groups) { it.at > 250 }
        // "Empty" has no runs; the siaulietis briefing is in no group.
        assertEquals(listOf("Personal", "RUBIX", "Other"), chips.map { it.label })
        assertEquals(listOf(2, 2, 1), chips.map { it.runs })
        assertEquals(listOf(1, 2, 0), chips.map { it.unread })
        assertEquals(listOf(400L, 200L), runs.filtered(InboxFilter.Group("Personal"), groups).map { it.at })
        assertEquals(listOf(100L), runs.filtered(InboxFilter.Group(null), groups).map { it.at })
    }

    @Test
    fun withoutGroupsThereIsNoOtherChip() {
        assertEquals(emptyList(), groupChips(runs, InboxGroups()) { false })
    }

    @Test
    fun aFilterFromTheOtherSplitOrARemovedGroupShowsEverything() {
        val groups = InboxGroups().add("RUBIX").assign("default", "pulse", "RUBIX")
        val byJobs = InboxUiState(runs = runs, groups = groups, filter = InboxFilter.Group("RUBIX"))
        assertEquals(InboxFilter.All, byJobs.shownFilter)
        val byGroups = byJobs.copy(groups = groups.copy(mode = ChipMode.Groups))
        assertEquals(InboxFilter.Group("RUBIX"), byGroups.shownFilter)
        assertEquals(listOf(500L, 300L), byGroups.shown.map { it.at })
        assertEquals(InboxFilter.All, byGroups.copy(groups = byGroups.groups.remove("RUBIX")).shownFilter)
        assertEquals(InboxFilter.All, byGroups.copy(filter = InboxFilter.Job("default", "brief")).shownFilter)
    }

    @Test
    fun aRenamedJobsChipShowsTheNewNameAndKeepsItsOwn() {
        val groups = InboxGroups().renameJob("default", "pulse", "Ticket pulse", own = "rubix-ticket-pulse")
        val chip = jobChips(runs, groups) { false }.first()
        assertEquals("Ticket pulse", chip.label)
        assertEquals("rubix-ticket-pulse", chip.own)
        assertTrue(chip.renamed)
        assertFalse(jobChips(runs) { false }.first().renamed)
        assertEquals("Ticket pulse", InboxUiState(runs = runs, groups = groups).nameOf(runs.first()))
    }

    @Test
    fun aFolderPerJobTheOneThatReportedLastFirst() {
        val folders = inboxFolders(runs, InboxGroups()) { it.at > 250 }
        assertEquals(listOf("rubix-ticket-pulse", "morning-briefing", "morning-briefing"), folders.map { it.label })
        assertEquals(listOf(500L, 400L, 100L), folders.map { it.newest.at })
        assertEquals(listOf(2, 2, 1), folders.map { it.runs })
        assertEquals(listOf(1, 0, 0), folders.map { it.failed })
    }

    @Test
    fun splitByGroupTheFoldersAreTheGroups() {
        val groups = InboxGroups(mode = ChipMode.Groups).add("Personal").assign("default", "brief", "Personal")
        val folders = inboxFolders(runs, groups) { false }
        // Other holds the ticket pulse, which reported last.
        assertEquals(listOf("Other", "Personal"), folders.map { it.label })
        assertEquals(listOf(InboxFilter.Group(null), InboxFilter.Group("Personal")), folders.map { it.filter })
        // Groups made but split by job: folders per job.
        assertEquals(3, inboxFolders(runs, groups.copy(mode = ChipMode.Jobs)) { false }.size)
    }

    @Test
    fun theInboxOpensOnFoldersUnlessOneIsOpenOrItsSetToOneList() {
        val state = InboxUiState(runs = runs)
        assertEquals(InboxLayout.Folders, state.groups.layout)
        assertTrue(state.atFolders)
        assertFalse(state.copy(filter = InboxFilter.Job("default", "brief")).atFolders)
        assertFalse(state.copy(groups = InboxGroups(layout = InboxLayout.List)).atFolders)
    }

    @Test
    fun everyFilterHasAKeyOfItsOwnWhateverAGroupIsCalled() {
        val filters = listOf(
            InboxFilter.All, InboxFilter.Failed, InboxFilter.Group(null), InboxFilter.Group("null"), InboxFilter.Group("Other"),
            InboxFilter.Job("default", "brief"), InboxFilter.Job("siaulietis", "brief"),
        )
        assertEquals(filters.size, filters.map { it.key }.toSet().size)
    }

    @Test
    fun theFailedFilterFallsBackOnceNoRunFailed() {
        val state = InboxUiState(runs = runs, filter = InboxFilter.Failed)
        assertEquals(InboxFilter.Failed, state.shownFilter)
        val fine = state.copy(runs = runs.filter { !it.failed })
        assertEquals(InboxFilter.All, fine.shownFilter)
    }

    @Test
    fun chipsShowWhileAFilterIsPickedSoThereIsAWayBack() {
        assertFalse(showsChips(chips = 1, failed = 0, selected = InboxFilter.All))
        assertTrue(showsChips(chips = 1, failed = 0, selected = InboxFilter.Job("default", "brief")))
        assertTrue(showsChips(chips = 2, failed = 0, selected = InboxFilter.All))
        assertTrue(showsChips(chips = 1, failed = 1, selected = InboxFilter.All))
    }

    @Test
    fun aRenamedGroupKeepsItsFilter() {
        assertEquals(InboxFilter.Group("Bar"), InboxFilter.Group("RUBIX").afterRename("RUBIX", "Bar"))
        assertEquals(InboxFilter.Group("Home"), InboxFilter.Group("Home").afterRename("RUBIX", "Bar"))
        assertEquals(InboxFilter.Group(null), InboxFilter.Group(null).afterRename("RUBIX", "Bar"))
        assertEquals(InboxFilter.All, InboxFilter.All.afterRename("RUBIX", "Bar"))
    }

    @Test
    fun aPageBackAddsOnlyRunsNotThereAndPagesFromTheOldestTime() {
        assertEquals(100L, pageCursor(runs))
        assertEquals(null, pageCursor(emptyList()))
        // The page repeats the oldest second the list had: those runs are there already.
        val page = listOf(runs.last(), run("brief", 90), run("pulse", 90), run("pulse", 90))
        val (added, more) = pageIn(runs, page, limit = 4)
        assertEquals(listOf(90L, 90L), added.map { it.at })
        assertTrue(more)
        // A short page is the end; so is a full one that brought nothing new.
        assertFalse(pageIn(runs, page.take(2), limit = 4).more)
        assertFalse(pageIn(runs, runs.take(4), limit = 4).more)
    }

    @Test
    fun aSaveErrorSaysWhatWentWrong() {
        assertTrue(saveErrorText(InboxPluginTooOld(InboxApi.ALL_JOBS_PLUGIN_VERSION)).contains(InboxApi.ALL_JOBS_PLUGIN_VERSION))
        assertTrue(saveErrorText(InboxPluginTooOld(InboxApi.MIN_PLUGIN_VERSION)).contains(InboxApi.TOO_OLD))
        // Anything else is said as it is, not as an old plugin.
        val offline = saveErrorText(IllegalStateException("Not connected to the gateway."))
        assertTrue(offline.contains("Not connected"))
        assertFalse(offline.contains(InboxApi.ALL_JOBS_PLUGIN_VERSION))
        assertEquals("Couldn't save that.", saveErrorText(IllegalStateException()))
    }
}
