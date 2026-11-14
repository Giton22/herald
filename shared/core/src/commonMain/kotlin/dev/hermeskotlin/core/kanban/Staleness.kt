package dev.hermeskotlin.core.kanban

/**
 * Staleness tiers, the dashboard's (`STALENESS` in the Kanban bundle): amber then red seconds past the
 * created clock (the running clock once started). Any other status never tints. Shared by the Kanban
 * page and the home-screen widget (D15, `docs/WIDGETS-SPEC.md`).
 */
val KANBAN_STALENESS: Map<String, Pair<Long, Long>> = mapOf(
    "ready" to (1 * 60 * 60L to 24 * 60 * 60L),
    "running" to (10 * 60L to 60 * 60L),
    "blocked" to (1 * 60 * 60L to 24 * 60 * 60L),
    "todo" to (7 * 24 * 60 * 60L to 30 * 24 * 60 * 60L),
)

enum class KanbanStale { None, Amber, Red }

/**
 * The tier from the gateway-computed ages (`KanbanAge`), the way the Kanban page reads it: the response
 * already carries how old each clock is, against the gateway's own clock.
 */
fun KanbanTask.stale(): KanbanStale {
    val (amber, red) = KANBAN_STALENESS[status] ?: return KanbanStale.None
    val age = if (status == "running") age?.startedAgeSeconds else age?.createdAgeSeconds
    if (age == null || age < 0) return KanbanStale.None
    return when {
        age >= red -> KanbanStale.Red
        age >= amber -> KanbanStale.Amber
        else -> KanbanStale.None
    }
}

/**
 * The tier against an explicit server clock ([nowSeconds], the board response's own `now`), for callers
 * that only hold the raw timestamps: the widget path, where the phone's clock must never decide staleness.
 */
fun KanbanTask.stale(nowSeconds: Long): KanbanStale {
    val (amber, red) = KANBAN_STALENESS[status] ?: return KanbanStale.None
    val clock = if (status == "running") startedAt ?: createdAt else createdAt
    if (clock == null) return KanbanStale.None
    val age = nowSeconds - clock
    if (age < 0) return KanbanStale.None
    return when {
        age >= red -> KanbanStale.Red
        age >= amber -> KanbanStale.Amber
        else -> KanbanStale.None
    }
}
