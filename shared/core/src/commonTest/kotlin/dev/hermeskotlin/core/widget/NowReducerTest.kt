package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotProblem
import dev.hermeskotlin.core.bots.BotTrouble
import dev.hermeskotlin.core.bots.BotSession
import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.kanban.KanbanTask
import dev.hermeskotlin.core.sessions.SessionSummary
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** T6 (`docs/WIDGETS-SPEC.md` §11): one item per source, the D12 tier order, caps, the quiet state. */
class NowReducerTest {

    private val now = 1_800_000_000L
    private val gateway = "https://gw.example.ts.net"

    private fun chat(id: String, activityAt: Double? = null, isActive: Boolean = false) =
        SessionSummary(id = id, title = "Chat $id", lastActive = activityAt, isActive = isActive)

    private fun bot(
        name: String,
        chatId: String? = null,
        chatActiveAt: Double? = null,
        workerActiveAt: Double? = null,
        pinned: Boolean = false,
        hidden: Boolean = false,
    ) = Bot(
        name = name,
        canonicalSession = chatId?.let { BotSession(id = it, resolvedId = it, lastActive = chatActiveAt) },
        workerSession = workerActiveAt?.let { BotSession(lastActive = it) },
        uiMeta = if (pinned || hidden) buildJsonObject {
            put("hermes-bots", buildJsonObject {
                if (pinned) put("pinned", true)
                if (hidden) put("hidden", true)
            })
        } else null,
    )

    private fun failingRoutine(bot: String) = CronJob(
        id = "job-$bot",
        name = "[bot:$bot] Nightly",
        lastStatus = "error",
        lastError = "boom",
        profile = bot,
    )

    private fun inputs(
        sessions: List<SessionSummary> = emptyList(),
        waiting: Map<String, Waiting> = emptyMap(),
        runningChats: Map<String, Boolean> = emptyMap(),
        bots: List<Bot> = emptyList(),
        troubles: Map<String, BotTrouble> = emptyMap(),
        failingRoutines: Map<String, CronJob> = emptyMap(),
        cronJobs: List<CronJob> = emptyList(),
        kanbanRows: List<KanbanTask> = emptyList(),
    ) = NowInputs(
        sessions = sessions,
        seen = null,
        waiting = waiting,
        runningChats = runningChats,
        bots = bots,
        troubles = troubles,
        failingRoutines = failingRoutines,
        cronJobs = cronJobs,
        kanbanRows = kanbanRows,
        kanbanBoardSlug = "waypoint",
        nowSeconds = now,
    )

    @Test
    fun oneItemPerSourceInTheD12TierOrder() {
        // All six sources at once; the cap keeps the four most pressing tiers, in order.
        val snapshot = NowReducer.derive(
            gateway,
            "p1",
            inputs(
                sessions = listOf(chat("s1", activityAt = 100.0)),
                waiting = mapOf("s1" to Waiting.Question, "bc-r" to Waiting.Approval),
                bots = listOf(
                    bot("researcher", chatId = "bc-r"),
                    bot("coder"),
                    bot("writer"),
                ),
                troubles = mapOf("coder" to BotTrouble(BotProblem.Setup, "no provider", BotTrouble.Source.Check)),
                failingRoutines = mapOf("writer" to failingRoutine("writer")),
                kanbanRows = listOf(KanbanTask(id = "t1", title = "Blocked", status = "blocked")),
                cronJobs = listOf(CronJob(id = "j1", name = "Bad", lastStatus = "error", lastError = "x")),
            ),
        )
        assertEquals(
            listOf(
                NowNeedsYouKind.ChatWaiting,
                NowNeedsYouKind.BotAnswer,
                NowNeedsYouKind.BotTrouble,
                NowNeedsYouKind.RoutineFailed,
            ),
            snapshot.needsYou.map { it.kind },
        )
        assertEquals("Chat s1", snapshot.needsYou[0].title)
        assertEquals("Has a question", snapshot.needsYou[0].detail)
        assertEquals("Researcher", snapshot.needsYou[1].title)
        assertEquals("Needs approval", snapshot.needsYou[1].detail)
        assertEquals("Coder", snapshot.needsYou[2].title)
        assertEquals(BotProblem.Setup.hint, snapshot.needsYou[2].detail)
        assertEquals("Writer", snapshot.needsYou[3].title)
        // displayName strips the [bot:…] tag: the routine's own title.
        assertEquals("Nightly", snapshot.needsYou[3].detail)
    }

    @Test
    fun kanbanAttentionRanksAboveCronProblems() {
        val snapshot = NowReducer.derive(
            gateway,
            null,
            inputs(
                kanbanRows = listOf(
                    KanbanTask(id = "t1", title = "Blocked", status = "blocked"),
                    KanbanTask(id = "t2", title = "In review", status = "review"),
                ),
                cronJobs = listOf(CronJob(id = "j1", name = "Bad", lastStatus = "error", lastError = "x")),
            ),
        )
        assertEquals(
            listOf(NowNeedsYouKind.KanbanAttention, NowNeedsYouKind.KanbanAttention, NowNeedsYouKind.CronProblem),
            snapshot.needsYou.map { it.kind },
        )
        assertEquals("blocked", snapshot.needsYou[0].detail)
        assertEquals("waypoint", snapshot.needsYou[0].target.kanbanBoard)
    }

    @Test
    fun aBotContributesOnlyItsMostPressingItem() {
        val heldUp = bot("researcher", chatId = "bc-r")
        val snapshot = NowReducer.derive(
            gateway,
            null,
            inputs(
                waiting = mapOf("bc-r" to Waiting.Approval),
                bots = listOf(heldUp),
                // A trouble and a failing routine too: the held answer outranks both.
                troubles = mapOf("researcher" to BotTrouble(BotProblem.Quota, "out of credits", BotTrouble.Source.Turn)),
                failingRoutines = mapOf("researcher" to failingRoutine("researcher")),
            ),
        )
        assertEquals(listOf(NowNeedsYouKind.BotAnswer), snapshot.needsYou.map { it.kind })
    }

    @Test
    fun botTiersFollowRosterOrderAndHiddenBotsCount() {
        // forRoster: pinned first, then the newest sign of life; a hidden bot is still on the roster.
        val pinned = bot("pinnedbot", workerActiveAt = 100.0, pinned = true)
        val fresh = bot("freshbot", workerActiveAt = now - 10.0)
        val hidden = bot("hiddenbot", workerActiveAt = now - 5.0, hidden = true)
        val snapshot = NowReducer.derive(
            gateway,
            null,
            inputs(
                bots = listOf(fresh, hidden, pinned),
                troubles = listOf(fresh, hidden, pinned).associate {
                    it.name to BotTrouble(BotProblem.Setup, "x", BotTrouble.Source.Check)
                },
            ),
        )
        assertEquals(
            listOf("Pinnedbot", "Hiddenbot", "Freshbot"),
            snapshot.needsYou.map { it.title },
        )
    }

    @Test
    fun capsHoldFourNeedsYouAndThreeRunning() {
        val manyWaiting = (1..6).map { chat("s$it", activityAt = it.toDouble()) }
        val needsYou = NowReducer.derive(
            gateway,
            null,
            inputs(
                sessions = manyWaiting,
                waiting = manyWaiting.associate { it.id to Waiting.Input },
            ),
        )
        assertEquals(NowReducer.NEEDS_YOU_CAP, needsYou.needsYou.size)
        // Newest activity first.
        assertEquals("Chat s6", needsYou.needsYou.first().title)

        val runningChats = (1..2).map { chat("r$it", activityAt = it.toDouble()) }
        val running = NowReducer.derive(
            gateway,
            null,
            inputs(
                sessions = runningChats,
                runningChats = runningChats.associate { it.id to true },
                bots = listOf(bot("working", workerActiveAt = now - 30.0)),
                kanbanRows = listOf(KanbanTask(id = "t1", title = "Run", status = "running")),
            ),
        )
        assertEquals(NowReducer.RUNNING_CAP, running.running.size)
        assertEquals(NowRunningKind.ChatTurn, running.running[0].kind)
    }

    @Test
    fun aLoadedChatIsNotARunningItem() {
        // `is_active` means "loaded"; only the live running map makes a row.
        val snapshot = NowReducer.derive(
            gateway,
            null,
            inputs(sessions = listOf(chat("s1", activityAt = 10.0, isActive = true))),
        )
        assertEquals(emptyList<NowRunningItem>(), snapshot.running)
    }

    @Test
    fun workingBotsAndRunningTasksSortAsDocumented() {
        val snapshot = NowReducer.derive(
            gateway,
            null,
            inputs(
                bots = listOf(
                    bot("older", workerActiveAt = now - 90.0),
                    bot("newer", workerActiveAt = now - 10.0),
                ),
                kanbanRows = listOf(
                    KanbanTask(id = "t-late", title = "Late", status = "running", currentRunStartedAt = now - 100),
                    KanbanTask(id = "t-early", title = "Early", status = "running", currentRunStartedAt = now - 1_000),
                ),
            ),
        )
        // The cap keeps the three: both working bots, then the longest-running task.
        assertEquals(
            listOf("Newer", "Older", "Early"),
            snapshot.running.map { it.title },
        )
        assertEquals(NowRunningKind.BotWorking, snapshot.running[0].kind)
        assertEquals(NowRunningKind.KanbanRunning, snapshot.running[2].kind)
    }

    @Test
    fun quietNamesTheSoonestJobWithTheDisplayNameTiebreak() {
        val snapshot = NowReducer.derive(
            gateway,
            null,
            inputs(
                cronJobs = listOf(
                    CronJob(id = "j2", name = "Beta", nextRunAt = "2026-11-12T09:00:00Z"),
                    CronJob(id = "j1", name = "Alpha", nextRunAt = "2026-11-12T09:00:00Z"),
                    CronJob(id = "j3", name = "Later", nextRunAt = "2026-11-13T09:00:00Z"),
                    CronJob(id = "jp", state = "paused"),
                ),
            ),
        )
        val quiet = assertNotNull(snapshot.quiet)
        // Same next run for two jobs: the display-name tiebreak picks Alpha.
        assertEquals("Alpha", quiet.nextJobName)
        assertEquals(kotlin.time.Instant.parse("2026-11-12T09:00:00Z").epochSeconds, quiet.nextRunEpochSeconds)
    }

    @Test
    fun quietWithNothingScheduledAtAll() {
        val snapshot = NowReducer.derive(gateway, null, inputs())
        assertEquals(emptyList<NowNeedsYouItem>(), snapshot.needsYou)
        assertEquals(emptyList<NowRunningItem>(), snapshot.running)
        val quiet = assertNotNull(snapshot.quiet)
        assertNull(quiet.nextRunEpochSeconds)
        assertNull(quiet.nextJobName)
    }
}
