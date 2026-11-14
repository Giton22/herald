package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.cron.Routines
import dev.hermeskotlin.core.cron.isRoutineOf
import dev.hermeskotlin.core.cron.problem

/**
 * The Scheduled widget's ranking and row derivation (D11, `docs/WIDGETS-SPEC.md`), a pure function over
 * one cross-profile `CronApi.jobs` answer. Buckets: problems first, then upcoming (soonest next run),
 * then paused; `completed` jobs are excluded entirely.
 */
object ScheduledWidgetAssembler {

    /** Rows kept in the snapshot. */
    const val CAP = 12

    /**
     * @param bots the warm bot roster (empty cold); a routine's owner label comes from it when known,
     *   else from the job's `[bot:…]` tag or its cron-store profile, rendered as the plain profile name.
     */
    fun assemble(
        gatewayUrl: String,
        jobs: List<CronJob>,
        bots: List<Bot>,
        deviceNowSeconds: Long,
    ): ScheduledWidgetSnapshot {
        val open = jobs.filter { it.state != "completed" }
        val problems = open.filter { isProblem(it) }
            .sortedWith(compareByDescending<CronJob> { it.failureStamp }.thenBy { it.displayName })
        val upcoming = open.filter { !isProblem(it) && it.state == "scheduled" && it.nextRunEpochSeconds != null }
            .sortedWith(compareBy<CronJob> { it.nextRunEpochSeconds }.thenBy { it.id })
        val paused = (open - problems.toSet() - upcoming.toSet())
            .sortedWith(compareBy<CronJob, String>(String.CASE_INSENSITIVE_ORDER) { it.displayName }.thenBy { it.id })
        val rows = (problems + upcoming + paused)
            .take(CAP)
            .map { it.row(ownerLabel(it, bots)) }
        return ScheduledWidgetSnapshot(gatewayUrl = gatewayUrl, rows = rows, savedAtEpochSeconds = deviceNowSeconds)
    }

    /** The app's own failing-routine rule, verbatim (`BotsViewModel`): a quietly paused job isn't a problem. */
    fun isProblem(job: CronJob): Boolean = job.problem != null && (!job.paused || !job.pausedReason.isNullOrBlank())

    private fun ownerLabel(job: CronJob, bots: List<Bot>): String? =
        bots.firstOrNull { job.isRoutineOf(it.name) }?.label
            ?: Routines.taggedBot(job.name)
            ?: job.profile?.takeIf { !it.equals(Routines.DEFAULT_PROFILE, ignoreCase = true) }

    private fun CronJob.row(ownerLabel: String?) = ScheduledWidgetRow(
        jobId = id,
        profile = profile,
        displayName = displayName,
        routineOwnerLabel = ownerLabel,
        scheduleDisplay = scheduleDisplay.takeIf { it.isNotBlank() },
        nextRunEpochSeconds = nextRunEpochSeconds?.toLong(),
        state = state,
        problemSummary = problem,
        isProblem = isProblem(this),
        problemKind = when {
            !isProblem(this) -> null
            missedFire -> ScheduledProblemKind.Missed
            !lastDeliveryError.isNullOrBlank() -> ScheduledProblemKind.DeliveryFailed
            else -> ScheduledProblemKind.Failed
        },
    )

    /** The newest stamp a problem carries; problems without one sort below stamped ones. */
    private val CronJob.failureStamp: Double get() = missedFireEpochSeconds ?: lastRunEpochSeconds ?: 0.0
}
