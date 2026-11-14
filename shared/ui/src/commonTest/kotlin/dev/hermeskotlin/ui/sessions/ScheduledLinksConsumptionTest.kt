package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.cron.ScheduledLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * T12 (`docs/WIDGETS-SPEC.md` §11): the sidebar's Scheduled-link effect forwards the job id, or the
 * profile-only (Routines) shape; the holder clears once the page reports opened.
 */
class ScheduledLinksConsumptionTest {

    @Test
    fun aJobLinkForwardsTheIdAndClearsAfterOpened() {
        val links = PendingWidgetLinks()
        links.onScheduledLink(ScheduledLink(jobId = "job-1", profile = null))
        assertEquals("job-1", links.scheduledJob)
        assertNull(links.scheduledProfile)
        links.onScheduledJobOpened()
        assertNull(links.scheduledJob)
    }

    @Test
    fun aProfileOnlyLinkRoutesToTheRoutinesShape() {
        val links = PendingWidgetLinks()
        links.onScheduledLink(ScheduledLink(jobId = null, profile = "researcher"))
        assertEquals("researcher", links.scheduledProfile)
        assertNull(links.scheduledJob)
        links.onScheduledJobOpened()
        assertNull(links.scheduledProfile)
    }

    @Test
    fun aJobLinkClearsAnEarlierProfileOnlyLink() {
        val links = PendingWidgetLinks()
        links.onScheduledLink(ScheduledLink(jobId = null, profile = "researcher"))
        links.onScheduledLink(ScheduledLink(jobId = "job-2", profile = null))
        assertEquals("job-2", links.scheduledJob)
        assertNull(links.scheduledProfile)
    }
}
