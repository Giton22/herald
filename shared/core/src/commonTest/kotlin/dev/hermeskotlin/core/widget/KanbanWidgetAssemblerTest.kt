package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.kanban.KanbanBoardMeta
import dev.hermeskotlin.core.kanban.KanbanBoardResponse
import dev.hermeskotlin.core.kanban.KanbanBoardsResponse
import dev.hermeskotlin.core.kanban.KanbanColumn
import dev.hermeskotlin.core.kanban.KanbanTask
import dev.hermeskotlin.core.kanban.KanbanWarnings
import kotlin.test.Test
import kotlin.test.assertEquals

/** T3 (`docs/WIDGETS-SPEC.md` §11): ranks, exclusions, priority, stale tiers, cap, counts, the slug read. */
class KanbanWidgetAssemblerTest {

    private val serverNow = 1_800_000_000L

    private fun task(id: String, status: String, priority: Int = 0, createdAt: Long? = null) =
        KanbanTask(id = id, title = "Task $id", status = status, priority = priority, createdAt = createdAt)

    private fun assemble(vararg tasks: KanbanTask, counts: Map<String, Int>? = null): KanbanWidgetSnapshot {
        val boards = KanbanBoardsResponse(
            boards = listOf(KanbanBoardMeta(slug = "waypoint", name = "Waypoint", counts = counts ?: emptyMap())),
            current = "waypoint",
        )
        val board = KanbanBoardResponse(
            columns = listOf(KanbanColumn(name = "main", tasks = tasks.toList())),
            now = serverNow,
        )
        return KanbanWidgetAssembler.assemble(
            gatewayUrl = "https://gw.example.ts.net",
            boards = boards,
            board = board,
            boardSlug = "waypoint",
            serverNowSeconds = serverNow,
            deviceNowSeconds = serverNow + 5,
        )
    }

    @Test
    fun statusesRankAndAnythingElseIsExcluded() {
        val snapshot = assemble(
            task("todo-1", "todo"),
            task("ready-1", "ready"),
            task("done", "done"),
            task("blocked-1", "blocked"),
            task("triage", "triage"),
            task("running-1", "running"),
            task("review-1", "review"),
            task("archived", "archived"),
            task("scheduled", "scheduled"),
        )
        assertEquals(
            listOf("blocked-1", "review-1", "running-1", "ready-1", "todo-1"),
            snapshot.rows.map { it.taskId },
        )
    }

    @Test
    fun priorityThenStalenessThenOldestThenId() {
        // Same status: priority desc wins first.
        val byPriority = assemble(
            task("low", "blocked", priority = 1),
            task("high", "blocked", priority = 9),
        )
        assertEquals(listOf("high", "low"), byPriority.rows.map { it.taskId })

        // Equal priority: the staler task first (a day old is Amber, past the red line is Red).
        val dayAgo = serverNow - 25 * 60 * 60
        val hourAgo = serverNow - 60 * 60
        val byStaleness = assemble(
            task("fresh", "blocked", priority = 5, createdAt = hourAgo),
            task("stale", "blocked", priority = 5, createdAt = serverNow - 3 * 24 * 60 * 60),
        )
        assertEquals(KanbanStaleTier.Red, byStaleness.rows.first { it.taskId == "stale" }.stale)
        assertEquals(KanbanStaleTier.Amber, byStaleness.rows.first { it.taskId == "fresh" }.stale)
        assertEquals(listOf("stale", "fresh"), byStaleness.rows.map { it.taskId })

        // Equal staleness: oldest first, then id.
        val byAge = assemble(
            task("b", "todo", createdAt = dayAgo),
            task("a", "todo", createdAt = dayAgo),
            task("c", "todo", createdAt = dayAgo - 10),
        )
        assertEquals(listOf("c", "a", "b"), byAge.rows.map { it.taskId })
    }

    @Test
    fun stalenessUsesTheResponseClockNotThePhones() {
        // created 2 hours before the response's `now`: Amber for blocked (amber 1h, red 24h).
        val snapshot = assemble(task("t1", "blocked", createdAt = serverNow - 2 * 60 * 60))
        assertEquals(KanbanStaleTier.Amber, snapshot.rows.single().stale)
    }

    @Test
    fun rowsCapAtTwelve() {
        val snapshot = assemble(*Array(20) { index -> task("t$index", "todo", priority = index) })
        assertEquals(KanbanWidgetAssembler.CAP, snapshot.rows.size)
        // Highest priority first: the cap keeps the head of the order.
        assertEquals("t19", snapshot.rows.first().taskId)
    }

    @Test
    fun countsAlwaysCarryTheFourHeaderKeys() {
        val fromMeta = assemble(counts = mapOf("running" to 3, "blocked" to 1))
        assertEquals(mapOf("running" to 3, "blocked" to 1, "review" to 0, "ready" to 0), fromMeta.counts)

        // No counts on the boards list: they're counted from the board response's columns.
        val boards = KanbanBoardsResponse(boards = listOf(KanbanBoardMeta(slug = "waypoint", name = "Waypoint")))
        val board = KanbanBoardResponse(
            columns = listOf(
                KanbanColumn(name = "ready", tasks = listOf(task("r1", "ready"), task("r2", "ready"))),
                KanbanColumn(name = "blocked", tasks = listOf(task("b1", "blocked"))),
            ),
            now = serverNow,
        )
        val counted = KanbanWidgetAssembler.assemble(
            gatewayUrl = "https://gw.example.ts.net",
            boards = boards,
            board = board,
            boardSlug = "waypoint",
            serverNowSeconds = serverNow,
            deviceNowSeconds = serverNow,
        )
        assertEquals(mapOf("running" to 0, "blocked" to 1, "review" to 0, "ready" to 2), counted.counts)
    }

    @Test
    fun warningCountsAndBoardIdentityComeThrough() {
        val warned = KanbanTask(
            id = "t1",
            title = "Task t1",
            status = "review",
            warnings = KanbanWarnings(count = 2),
        )
        val snapshot = assemble(warned)
        assertEquals(2, snapshot.rows.single().warningCount)
        assertEquals("waypoint", snapshot.boardSlug)
        assertEquals("Waypoint", snapshot.boardName)
    }
}
