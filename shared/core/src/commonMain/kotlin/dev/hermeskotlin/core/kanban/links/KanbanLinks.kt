package dev.hermeskotlin.core.kanban.links

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Hands a Kanban destination asked for from outside the UI — a widget tap, a `hermes://kanban` link — to
 * the screens. Only the latest is kept; [consume] clears it by identity so a newer link is never swallowed.
 *
 * Lives in the `links` subpackage because [dev.hermeskotlin.core.kanban.KanbanLinks] is the task link
 * graph model; the bus keeps the spec's (D20) class name and shape either way.
 */
data class KanbanLink(val board: String?, val taskId: String?)

class KanbanLinks {
    private val _pending = MutableStateFlow<KanbanLink?>(null)
    val pending: StateFlow<KanbanLink?> = _pending.asStateFlow()

    fun open(board: String? = null, taskId: String? = null) {
        _pending.value = KanbanLink(board, taskId)
    }

    /** Takes [link] once it's opened, unless a newer one came meanwhile. */
    fun consume(link: KanbanLink) = _pending.update { if (it == link) null else it }
}
