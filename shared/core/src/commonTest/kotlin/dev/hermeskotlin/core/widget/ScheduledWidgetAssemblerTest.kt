package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.cron.CronJob
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T5 (`docs/WIDGETS-SPEC.md` §11): the three buckets and their sorts, the `isProblem` rule, the owner labels. */
class ScheduledWidgetAssemblerTest {

    private fun job(
        id: String,
        name: String = "Job $id",
        state: String = "scheduled",
        nextRunAt: String? = null,
        lastStatus: String? = null,
        lastError: String? = null,
        missedAt: String? = null,
        pausedReason: String? = null,
        profile: String? = null,
    ) = CronJob(
        id = id,
        name = name,
        state = state,
        nextRunAt = nextRunAt,
        lastRunAt = null,
        lastStatus = lastStatus,
        lastError = lastError,
        fireError = missedAt?.let { buildJsonObject { put("at", it); put("detail", "the runner never started") } },
        pausedReason = pausedReason,
        profile = profile,
    )

    private fun assemble(vararg jobs: CronJob, bots: List<Bot> = emptyList()) =
        ScheduledWidgetAssembler.assemble("https://gw.example.ts.net", jobs.toList(), bots, deviceNowSeconds = 1)

    @Test
    fun problemsComeFirstNewestFailureThenName() {
        val snapshot = assemble(
            job("ok", nextRunAt = "2026-11-12T09:00:00Z"),
            job("failed-old", lastStatus = "error", lastError = "boom"),
            job("missed-new", missedAt = "2026-11-11T20:00:00Z"),
            job("missed-older", missedAt = "2026-11-10T20:00:00Z"),
        )
        assertEquals(listOf("missed-new", "missed-older", "failed-old", "ok"), snapshot.rows.map { it.jobId })
        assertTrue(snapshot.rows.first().isProblem)
        assertEquals("Missed its last scheduled run: the runner never started", snapshot.rows.first().problemSummary)
    }

    @Test
    fun upcomingSortsBySoonestNextRunThenId() {
        val snapshot = assemble(
            job("late", nextRunAt = "2026-11-13T09:00:00Z"),
            job("b", nextRunAt = "2026-11-12T09:00:00Z"),
            job("a", nextRunAt = "2026-11-12T09:00:00Z"),
        )
        assertEquals(listOf("a", "b", "late"), snapshot.rows.map { it.jobId })
        assertEquals("2026-11-12T09:00:00Z".epoch(), snapshot.rows.first().nextRunEpochSeconds)
    }

    @Test
    fun pausedSortsByNameCaseInsensitiveThenId() {
        val snapshot = assemble(
            job("z", state = "paused", name = "zeta"),
            job("a", state = "paused", name = "Alpha"),
            job("b", state = "paused", name = "alpha"),
        )
        assertEquals(listOf("a", "b", "z"), snapshot.rows.map { it.jobId })
    }

    @Test
    fun bucketsConcatenateProblemsThenUpcomingThenPaused() {
        val snapshot = assemble(
            job("paused", state = "paused"),
            job("soon", nextRunAt = "2026-11-12T09:00:00Z"),
            job("bad", lastStatus = "error", lastError = "boom"),
        )
        assertEquals(listOf("bad", "soon", "paused"), snapshot.rows.map { it.jobId })
    }

    @Test
    fun completedJobsAreExcludedAndRowsCap() {
        assertTrue(assemble(job("done", state = "completed")).rows.isEmpty())
        val many = (1..20).map { job("j$it", nextRunAt = "2026-11-1${it % 10}T09:00:00Z") }
        assertEquals(ScheduledWidgetAssembler.CAP, assemble(*many.toTypedArray()).rows.size)
    }

    @Test
    fun aQuietlyPausedJobIsNotAProblemButASchedulerPauseWithAReasonIs() {
        // The user paused it; the old failure on record doesn't make it a problem.
        val quiet = job("q", state = "paused", lastStatus = "error", lastError = "old failure")
        assertFalse(ScheduledWidgetAssembler.isProblem(quiet))

        // The scheduler paused it for a reason: that is a problem.
        val scheduler = job("s", state = "paused", lastStatus = "error", pausedReason = "three failures in a row")
        assertTrue(ScheduledWidgetAssembler.isProblem(scheduler))
    }

    @Test
    fun ownerLabelFromRosterThenTagThenProfile() {
        val researcher = Bot(name = "researcher")
        val fromRoster = assemble(
            job("j1", name = "[bot:researcher] Nightly", profile = "researcher"),
            bots = listOf(researcher),
        )
        assertEquals("Researcher", fromRoster.rows.single().routineOwnerLabel)

        // Cold: no roster, so the [bot:…] tag names the owner, rendered as the plain profile name.
        val fromTag = assemble(job("j2", name = "[bot:researcher] Nightly", profile = "researcher"))
        assertEquals("researcher", fromTag.rows.single().routineOwnerLabel)

        // A non-tagged job in a non-launch store: the store's profile is the owner.
        val fromProfile = assemble(job("j3", name = "Inbox triage", profile = "work"))
        assertEquals("work", fromProfile.rows.single().routineOwnerLabel)

        // The launch store hides its default profile: no owner label.
        val launch = assemble(job("j4", name = "Inbox triage"))
        assertNull(launch.rows.single().routineOwnerLabel)
    }

    private fun String.epoch(): Long = kotlin.time.Instant.parse(this).epochSeconds
}
