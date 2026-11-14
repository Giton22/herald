package dev.hermeskotlin.core.kanban

import kotlin.test.Test
import kotlin.test.assertEquals

/** The relocated staleness thresholds (D15), byte-for-byte against the old `shared/ui` fixtures. */
class StalenessTest {

    private fun task(status: String, createdAge: Long? = null, startedAge: Long? = null, createdAt: Long? = null, startedAt: Long? = null) =
        KanbanTask(
            id = "t_1",
            title = "T",
            status = status,
            createdAt = createdAt,
            startedAt = startedAt,
            age = if (createdAge == null && startedAge == null) null else KanbanAge(createdAge, startedAge, null),
        )

    @Test
    fun staleUsesCreatedAgeExceptWhileRunning() {
        val hour = 3600L
        // Ready: amber 1h, red 24h (dashboard STALENESS).
        assertEquals(KanbanStale.Amber, task("ready", createdAge = hour + 1).stale())
        assertEquals(KanbanStale.None, task("ready", createdAge = hour - 1).stale())
        assertEquals(KanbanStale.Red, task("ready", createdAge = 24 * hour).stale())
        // Running: amber 10m, red 60m, on the started clock.
        assertEquals(KanbanStale.Amber, task("running", startedAge = 10 * 60 + 1).stale())
        assertEquals(KanbanStale.Red, task("running", startedAge = 60 * 60).stale())
        assertEquals(KanbanStale.None, task("running", createdAge = 5 * hour, startedAge = 59).stale())
        // Blocked: amber 1h, red 24h. Todo: amber 7d, red 30d.
        assertEquals(KanbanStale.Amber, task("blocked", createdAge = hour + 1).stale())
        assertEquals(KanbanStale.None, task("todo", createdAge = 6 * 24 * hour).stale())
        assertEquals(KanbanStale.Amber, task("todo", createdAge = 7 * 24 * hour).stale())
        // Any other status never tints.
        assertEquals(KanbanStale.None, task("done", createdAge = 90 * 24 * hour).stale())
        assertEquals(KanbanStale.None, task("ready", createdAge = null).stale())
    }

    @Test
    fun staleAgainstAServerClockMatchesTheAgeBasedTier() {
        val hour = 3600L
        val now = 1_800_000_000L
        // Same thresholds, computed from raw timestamps against the response's own `now`.
        assertEquals(KanbanStale.Amber, task("ready", createdAt = now - hour - 1).stale(now))
        assertEquals(KanbanStale.None, task("ready", createdAt = now - hour + 1).stale(now))
        assertEquals(KanbanStale.Red, task("ready", createdAt = now - 24 * hour).stale(now))
        assertEquals(KanbanStale.Amber, task("running", startedAt = now - 10 * 60 - 1).stale(now))
        assertEquals(KanbanStale.Red, task("running", startedAt = now - 60 * 60).stale(now))
        assertEquals(KanbanStale.None, task("running", createdAt = now - 5 * hour, startedAt = now - 59).stale(now))
        assertEquals(KanbanStale.Amber, task("blocked", createdAt = now - hour - 1).stale(now))
        assertEquals(KanbanStale.None, task("todo", createdAt = now - 6 * 24 * hour).stale(now))
        assertEquals(KanbanStale.Amber, task("todo", createdAt = now - 7 * 24 * hour).stale(now))
        // Missing timestamps and other statuses never tint; a future clock doesn't either.
        assertEquals(KanbanStale.None, task("ready").stale(now))
        assertEquals(KanbanStale.None, task("done", createdAt = now - 90 * 24 * hour).stale(now))
        assertEquals(KanbanStale.None, task("ready", createdAt = now + hour).stale(now))
    }
}
