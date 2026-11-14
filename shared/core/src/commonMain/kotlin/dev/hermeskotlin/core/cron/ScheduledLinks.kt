package dev.hermeskotlin.core.cron

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A Scheduled page to open from outside the app's screens: the list, one job's runs, or a bot's Routines. */
data class ScheduledLink(val jobId: String?, val profile: String?)

/** Hands a Scheduled destination asked for from outside the UI — a widget tap, a `hermes://scheduled` link —
 * to the screens. Only the latest is kept; [consume] clears it by identity so a newer link is never swallowed. */
class ScheduledLinks {
    private val _pending = MutableStateFlow<ScheduledLink?>(null)
    val pending: StateFlow<ScheduledLink?> = _pending.asStateFlow()

    fun open(jobId: String? = null, profile: String? = null) {
        _pending.value = ScheduledLink(jobId, profile)
    }

    /** Takes [link] once it's opened, unless a newer one came meanwhile. */
    fun consume(link: ScheduledLink) = _pending.update { if (it == link) null else it }
}
