package dev.hermeskotlin.ui.preview

import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.inbox.ChipMode
import dev.hermeskotlin.core.inbox.Follow
import dev.hermeskotlin.core.inbox.Following
import dev.hermeskotlin.core.inbox.InboxGroups
import dev.hermeskotlin.core.inbox.InboxLayout
import dev.hermeskotlin.core.inbox.InboxRun
import dev.hermeskotlin.ui.inbox.InboxActions
import dev.hermeskotlin.ui.inbox.InboxFilter
import dev.hermeskotlin.ui.inbox.InboxUiState
import dev.hermeskotlin.ui.inbox.JobChip
import kotlin.time.Clock

/** The Inbox from sample data: a homelab's jobs reporting over a few days, nothing real. */
internal object InboxSamples {

    private const val BRIEFING = "brief"
    private const val BACKUP = "backup"
    private const val DIGEST = "digest"
    private const val SITE = "uptime"

    private val briefing = """
        # ☀️ Morning briefing

        **Weather:** 9°C and clear, showers after 18:00. Take a jacket.

        ## 📅 Today
        - 10:00 Call with the plumber about the boiler
        - 14:30 Pick up the parcel from the locker on Elm Street
        - 19:00 Dinner at Marta's

        ## 📬 Overnight
        - 3 new emails, none urgent
        - The homelab backups finished at 04:12

        ## ✅ To do
        - [ ] Renew the car insurance (due Friday)
        - [ ] Order more coffee
    """.trimIndent()

    private val backup = """
        **Backups:** all 6 finished.

        | Host | Size | Took |
        |---|---|---|
        | nas | 412 GB | 41 min |
        | homeassistant | 2.1 GB | 3 min |
        | photos | 88 GB | 12 min |

        Disk on the NAS is at 71%.
    """.trimIndent()

    private val digest = """
        📬 **7 emails today**

        - The library: your reserved book is ready to collect
        - Electricity bill for September, €48.20
        - Newsletter: 5 things to try in the garden this autumn
    """.trimIndent()

    private const val SITE_DOWN = "The site didn't answer: timeout after 30 s on https://blog.example.com. It was up again at the next check."

    /** Newest first: four jobs over three days, one run failed, two unread. */
    fun runs(now: Long = Clock.System.now().toEpochMilliseconds() / 1000): List<InboxRun> {
        val hour = 3600L
        val day = 24 * hour
        fun run(job: String, label: String, agoSeconds: Long, text: String, failed: Boolean = false): InboxRun {
            val at = now - agoSeconds
            return InboxRun(
                bot = "default",
                job = job,
                run = "2026-10-${(13 - agoSeconds / day).toString().padStart(2, '0')}_${(at / hour % 24).toString().padStart(2, '0')}-00-00",
                label = label,
                status = if (failed) InboxRun.FAILED else InboxRun.OK,
                at = at,
                text = text,
            )
        }
        return listOf(
            run(BRIEFING, "morning-briefing", 1 * hour, briefing),
            run(BACKUP, "nightly-backup", 5 * hour, backup),
            run(DIGEST, "email-digest", day - 2 * hour, digest),
            run(SITE, "site-check", day + 3 * hour, SITE_DOWN, failed = true),
            run(BRIEFING, "morning-briefing", day + hour, briefing.replace("9°C and clear", "6°C and foggy")),
            run(BACKUP, "nightly-backup", day + 5 * hour, backup),
            run(DIGEST, "email-digest", 2 * day - 2 * hour, digest),
            run(BRIEFING, "morning-briefing", 2 * day + hour, briefing),
        )
    }

    private val renamed = InboxGroups().renameJob("default", BRIEFING, "☀️ Morning briefing", own = "morning-briefing")

    /** The Inbox's front page: a folder per job, two reports unread. */
    fun folders(): InboxUiState {
        val runs = runs()
        return InboxUiState(runs = runs, read = runs.drop(2).map { it.key }.toSet(), loading = false, groups = renamed)
    }

    /** One folder opened: the briefing's reports, by day. */
    fun folder(): InboxUiState = folders().copy(filter = InboxFilter.Job("default", BRIEFING))

    /** The newest briefing, read in full. */
    fun report(): InboxRun = runs().first()

    /** The jobs page: every job into the Inbox, the briefing notifying, the backup kept out, failures on. */
    fun jobs(): InboxUiState = folders().copy(
        choosing = true,
        jobs = listOf(
            CronJob(BACKUP, name = "nightly-backup", scheduleDisplay = "0 4 * * *"),
            CronJob(DIGEST, name = "email-digest", scheduleDisplay = "0 21 * * *"),
            CronJob(BRIEFING, name = "morning-briefing", scheduleDisplay = "0 8 * * *"),
            CronJob(SITE, name = "site-check", scheduleDisplay = "every 30m"),
        ),
        jobsLoading = false,
        followingLoaded = true,
        following = Following(default = Follow.Quiet, failures = true)
            .with(BRIEFING, Follow.Notify)
            .with(BACKUP, Follow.Off),
    )

    /** The folders and groups page, split by two groups. */
    fun groups(): InboxUiState = folders().copy(
        grouping = true,
        groups = renamed.copy(layout = InboxLayout.Folders, mode = ChipMode.Groups)
            .add("Home").add("Servers")
            .assign("default", BRIEFING, "Home").assign("default", DIGEST, "Home")
            .assign("default", BACKUP, "Servers").assign("default", SITE, "Servers"),
    )
}

internal object PreviewInboxActions : InboxActions {
    override fun open(run: InboxRun) {}
    override fun closeRun() {}
    override fun markAllRead() {}
    override fun setFilter(filter: InboxFilter) {}
    override fun chooseJobs() {}
    override fun closeJobs() {}
    override fun setFollow(job: CronJob, follow: Follow) {}
    override fun setFailures(on: Boolean) {}
    override fun setAll(follow: Follow) {}
    override fun openGroups() {}
    override fun closeGroups() {}
    override fun setLayout(layout: InboxLayout) {}
    override fun setChipMode(mode: ChipMode) {}
    override fun addGroup(name: String) {}
    override fun renameGroup(from: String, to: String) {}
    override fun removeGroup(name: String) {}
    override fun assignGroup(job: InboxFilter.Job, group: String?) {}
    override fun renameJob(chip: JobChip, name: String) {}
    override fun loadMore() {}
}
