package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.kanban.KanbanBoardMeta
import dev.hermeskotlin.core.kanban.KanbanBoardResponse
import dev.hermeskotlin.core.kanban.KanbanBoardsResponse
import dev.hermeskotlin.core.kanban.KanbanStale
import dev.hermeskotlin.core.kanban.KanbanTask
import dev.hermeskotlin.core.kanban.stale

/**
 * The Tasks widget's ranking and row derivation (D10, `docs/WIDGETS-SPEC.md`): the boards list for the
 * header counts and board choice, one board response for the rows, the response's own `now` for
 * staleness — never the phone's clock.
 */
object KanbanWidgetAssembler {

    /** Rows kept in the snapshot. */
    const val CAP = 12

    /** The four header-count keys, in strip order. */
    val COUNT_KEYS = listOf("running", "blocked", "review", "ready")

    /** Statuses a row may have, ranked: everything else (`triage`, `scheduled`, `done`, `archived`, unknown) is excluded. */
    private val STATUS_RANK = mapOf("blocked" to 0, "review" to 1, "running" to 2, "ready" to 3, "todo" to 4)

    /**
     * Which board a refresh reads: the saved pin while the gateway still has it, then the gateway's
     * `current`, then the first board — the Kanban page's order without its "already shown" step. The
     * widget never writes the pin back; the page owns it.
     */
    fun resolveBoard(boards: KanbanBoardsResponse, pin: String?): KanbanBoardMeta? {
        val all = boards.boards
        return pin?.let { wanted -> all.firstOrNull { it.slug == wanted } }
            ?: all.firstOrNull { it.slug == boards.current }
            ?: all.firstOrNull()
    }

    /**
     * Builds the snapshot from what the REST reads returned. [boards] is the `boards()` answer (its
     * chosen board's counts feed the header); [board] is that board's `board()` answer, or null when the
     * read failed; [boardSlug] is the slug actually read (never the pin's alone — a deleted pin falls
     * back and the fallback is what lands here). [serverNowSeconds] is the board response's own `now`,
     * the only clock staleness may use; [deviceNowSeconds] is when the snapshot is written.
     */
    fun assemble(
        gatewayUrl: String,
        boards: KanbanBoardsResponse?,
        board: KanbanBoardResponse?,
        boardSlug: String?,
        serverNowSeconds: Long,
        deviceNowSeconds: Long,
    ): KanbanWidgetSnapshot {
        val meta = boardSlug?.let { slug -> boards?.boards?.firstOrNull { it.slug == slug } }
        val rows = board?.columns.orEmpty()
            .flatMap { it.tasks }
            .filter { it.status in STATUS_RANK }
            .sortedWith(rowOrder(serverNowSeconds))
            .take(CAP)
            .map { task -> task.row(task.stale(serverNowSeconds)) }
        return KanbanWidgetSnapshot(
            gatewayUrl = gatewayUrl,
            boardSlug = boardSlug ?: "",
            boardName = meta?.name?.takeIf { it.isNotBlank() } ?: boardSlug ?: "",
            counts = counts(meta, board),
            rows = rows,
            savedAtEpochSeconds = deviceNowSeconds,
        )
    }

    /**
     * The header counts, always carrying [COUNT_KEYS]: the chosen board's `KanbanBoardMeta.counts`
     * (absent → 0). When the boards list carried no counts at all, they're counted from the board
     * response's columns instead, so a cheap `boards()`-only refresh can still update the header.
     */
    private fun counts(meta: KanbanBoardMeta?, board: KanbanBoardResponse?): Map<String, Int> {
        val fromMeta = meta?.counts.orEmpty()
        val byColumn = board?.columns.orEmpty().associate { it.name to it.tasks.size }
        return COUNT_KEYS.associateWith { key -> fromMeta[key] ?: if (fromMeta.isEmpty()) byColumn[key] ?: 0 else 0 }
    }

    /** Status rank → priority desc → stale tier (Red > Amber > None) → oldest first → id. */
    private fun rowOrder(serverNowSeconds: Long) = compareBy<KanbanTask> { STATUS_RANK[it.status] ?: Int.MAX_VALUE }
        .thenByDescending { it.priority }
        .thenByDescending { tierRank(it.stale(serverNowSeconds)) }
        .thenBy { it.createdAt ?: Long.MAX_VALUE }
        .thenBy { it.id }

    private fun tierRank(tier: KanbanStale): Int = when (tier) {
        KanbanStale.Red -> 2
        KanbanStale.Amber -> 1
        KanbanStale.None -> 0
    }

    private fun KanbanTask.row(tier: KanbanStale) = KanbanWidgetRow(
        taskId = id,
        title = title,
        status = status,
        assignee = assignee,
        priority = priority,
        stale = when (tier) {
            KanbanStale.None -> KanbanStaleTier.None
            KanbanStale.Amber -> KanbanStaleTier.Amber
            KanbanStale.Red -> KanbanStaleTier.Red
        },
        warningCount = warnings?.count ?: 0,
    )
}
