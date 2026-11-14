package dev.hermeskotlin.ui.sessions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.hermeskotlin.core.cron.ScheduledLink
import dev.hermeskotlin.core.kanban.links.KanbanLink

/**
 * The sidebar's Kanban/Scheduled link bookkeeping (D21, `docs/WIDGETS-SPEC.md`): a link opens its page
 * (the composable's own flag) and leaves the task/job/profile here pending until the page reports it
 * opened; a task or job the board no longer has clears there too, silently. Split out of the composable
 * so the routing is unit-tested without a UI harness.
 */
internal class PendingWidgetLinks {
    /** The task to open, as (board, taskId); "" names whatever board resolves. */
    var kanbanTask by mutableStateOf<Pair<String, String>?>(null)
        private set

    var scheduledJob by mutableStateOf<String?>(null)
        private set

    /** The Routines page's bot, for the profile-only Scheduled link. */
    var scheduledProfile by mutableStateOf<String?>(null)
        private set

    fun onKanbanLink(link: KanbanLink) {
        link.taskId?.let { kanbanTask = (link.board ?: "") to it }
    }

    fun onScheduledLink(link: ScheduledLink) {
        if (link.jobId != null) {
            scheduledJob = link.jobId
            scheduledProfile = null
        } else {
            scheduledJob = null
            scheduledProfile = link.profile
        }
    }

    fun onKanbanTaskOpened() {
        kanbanTask = null
    }

    fun onScheduledJobOpened() {
        scheduledJob = null
        scheduledProfile = null
    }
}
