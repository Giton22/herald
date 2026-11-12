package dev.hermeskotlin.core.cron

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** T10 (`docs/WIDGETS-SPEC.md` §11): publish/consume with the identity guard, as `RoomLinks` does it. */
class ScheduledLinksTest {

    @Test
    fun publishAndConsume() = runTest {
        val links = ScheduledLinks()
        assertNull(links.pending.value)
        val link = ScheduledLink(jobId = "job-1", profile = null)
        links.open(jobId = "job-1")
        assertEquals(link, links.pending.value)
        links.consume(link)
        assertNull(links.pending.value)
    }

    @Test
    fun theProfileOnlyShapeRidesTheSameBus() = runTest {
        val links = ScheduledLinks()
        val routines = ScheduledLink(jobId = null, profile = "researcher")
        links.open(profile = "researcher")
        assertEquals(routines, links.pending.value)
    }

    @Test
    fun anOlderConsumeNeverSwallowsANewerLink() = runTest {
        val links = ScheduledLinks()
        val older = ScheduledLink(jobId = "job-1", profile = null)
        val newer = ScheduledLink(jobId = null, profile = null)
        links.open(jobId = "job-1")
        links.open()
        links.consume(older)
        assertEquals(newer, links.pending.value)
    }
}
