package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotSession
import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.kanban.KanbanTask
import dev.hermeskotlin.core.sessions.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T7 (`docs/WIDGETS-SPEC.md` §11): the D14 cold merge. Inputs are REST results only — no socket
 * method is touched; the carried rows are the stored snapshot's socket knowledge, kept only while
 * their target still exists in the fresh data, re-ranked under fresh rows, and stamped `carried`.
 */
class NowColdMergeTest {

    private val gateway = "https://gw.example.ts.net"
    private val now = 1_800_000_000L

    private val researcher = Bot(name = "researcher", canonicalSession = BotSession(id = "bc-r", resolvedId = "bc-r"))

    private fun chat(id: String, activityAt: Double? = null) =
        SessionSummary(id = id, title = "Chat $id", lastActive = activityAt)

    private fun freshInputs(
        sessions: List<SessionSummary> = listOf(chat("s1", activityAt = 500.0)),
        waiting: Map<String, Waiting> = emptyMap(),
        bots: List<Bot> = listOf(researcher),
        cronJobs: List<CronJob> = emptyList(),
        kanbanRows: List<KanbanTask> = emptyList(),
        sessionsKnown: Boolean = true,
    ) = NowInputs(
        sessions = sessions,
        sessionsKnown = sessionsKnown,
        seen = null,
        waiting = waiting,
        runningChats = emptyMap(),
        bots = bots,
        troubles = emptyMap(),
        failingRoutines = emptyMap(),
        cronJobs = cronJobs,
        kanbanRows = kanbanRows,
        kanbanBoardSlug = "waypoint",
        nowSeconds = now,
    )

    private fun waitingItem(id: String, activityAt: Double? = 100.0, carried: Boolean = false) = NowNeedsYouItem(
        kind = NowNeedsYouKind.ChatWaiting,
        title = "Chat $id",
        detail = "Has a question",
        target = NowTarget(storedSessionId = id, title = "Chat $id"),
        activityEpochSeconds = activityAt,
        carried = carried,
    )

    private fun botAnswer(bot: String, chatId: String, carried: Boolean = false) = NowNeedsYouItem(
        kind = NowNeedsYouKind.BotAnswer,
        title = bot.replaceFirstChar { it.uppercase() },
        detail = "Needs approval",
        target = NowTarget(storedSessionId = chatId, title = bot, botProfile = bot),
        botProfile = bot,
        carried = carried,
    )

    @Test
    fun carriedRowsSurviveOnlyWhileTheirTargetExists() {
        val stored = NowWidgetSnapshot(
            gatewayUrl = gateway,
            profile = "p1",
            needsYou = listOf(
                waitingItem("s-gone"),
                waitingItem("s1"),
                botAnswer("researcher", "bc-r"),
                NowNeedsYouItem(
                    kind = NowNeedsYouKind.KanbanAttention,
                    title = "Blocked",
                    target = NowTarget(kanbanBoard = "waypoint", kanbanTaskId = "t1"),
                ),
                botAnswer("ghost", "bc-ghost"),
            ),
            running = listOf(
                NowRunningItem(
                    kind = NowRunningKind.ChatTurn,
                    title = "Chat s-gone",
                    target = NowTarget(storedSessionId = "s-gone", title = "Chat s-gone"),
                ),
                NowRunningItem(
                    kind = NowRunningKind.ChatTurn,
                    title = "Chat s1",
                    target = NowTarget(storedSessionId = "s1", title = "Chat s1"),
                ),
                NowRunningItem(
                    kind = NowRunningKind.KanbanRunning,
                    title = "Run",
                    target = NowTarget(kanbanBoard = "waypoint", kanbanTaskId = "t1"),
                ),
            ),
            savedAtEpochSeconds = 500,
        )

        val merged = NowReducer.mergeCold(gateway, "p1", stored, freshInputs(), knownBots = listOf(researcher))

        // s-gone's chat and the ghost bot vanished from the fresh data: their rows are dropped.
        assertEquals(
            listOf("s1", "bc-r"),
            merged.needsYou.map { it.target.storedSessionId },
        )
        assertTrue(merged.needsYou.all { it.carried })
        // Kanban rows are REST knowledge: the stored one is never carried, the fresh board re-derives it.
        assertEquals(listOf(NowNeedsYouKind.ChatWaiting, NowNeedsYouKind.BotAnswer), merged.needsYou.map { it.kind })

        assertEquals(listOf("s1"), merged.running.map { it.target.storedSessionId })
        assertTrue(merged.running.single().carried)
        assertEquals(NowRunningKind.ChatTurn, merged.running.single().kind)

        // The socket knowledge's age is propagated for the "as of" line; the write time is fresh.
        assertEquals(500, merged.socketSavedAtEpochSeconds)
        assertEquals(now, merged.savedAtEpochSeconds)
        assertNull(merged.quiet)
    }

    @Test
    fun carriedRowsSinkBelowFreshRowsOfTheSameTier() {
        val stored = NowWidgetSnapshot(
            gatewayUrl = gateway,
            profile = null,
            needsYou = listOf(waitingItem("s-old", activityAt = 9_000.0)),
            savedAtEpochSeconds = 500,
        )
        // A fresh REST read can see a waiting chat too (the gateway flags it): the carried row sinks.
        val merged = NowReducer.mergeCold(
            gateway,
            null,
            stored,
            freshInputs(
                sessions = listOf(chat("s-old", 9_000.0), chat("s-new", 9_500.0)),
                waiting = mapOf("s-new" to Waiting.Question),
            ),
            knownBots = listOf(researcher),
        )
        assertEquals(
            listOf("s-new", "s-old"),
            merged.needsYou.map { it.target.storedSessionId },
        )
        assertFalse(merged.needsYou[0].carried)
        assertTrue(merged.needsYou[1].carried)
    }

    @Test
    fun freshRestRowsKeepTheirTierPrecedenceOverCarriedRows() {
        val stored = NowWidgetSnapshot(
            gatewayUrl = gateway,
            profile = null,
            needsYou = listOf(botAnswer("researcher", "bc-r")),
            savedAtEpochSeconds = 500,
        )
        val merged = NowReducer.mergeCold(
            gateway,
            null,
            stored,
            freshInputs(
                kanbanRows = listOf(KanbanTask(id = "t1", title = "Blocked", status = "blocked")),
                cronJobs = listOf(CronJob(id = "j1", name = "Bad", lastStatus = "error", lastError = "x")),
            ),
            knownBots = listOf(researcher),
        )
        // Tier 1 (carried answer) still outranks the fresh REST tiers 4 and 5.
        assertEquals(
            listOf(
                NowNeedsYouKind.BotAnswer,
                NowNeedsYouKind.KanbanAttention,
                NowNeedsYouKind.CronProblem,
            ),
            merged.needsYou.map { it.kind },
        )
    }

    @Test
    fun aStoredSnapshotWithoutSocketRowsStillPropagatesTheStamp() {
        val stored = NowWidgetSnapshot(gatewayUrl = gateway, profile = null, savedAtEpochSeconds = 500)
        val merged = NowReducer.mergeCold(gateway, null, stored, freshInputs(), knownBots = listOf(researcher))
        assertEquals(500, merged.socketSavedAtEpochSeconds)
        assertEquals(emptyList<NowNeedsYouItem>(), merged.needsYou)
    }

    @Test
    fun noStoredSnapshotIsJustTheFreshDerivation() {
        val merged = NowReducer.mergeCold(gateway, null, null, freshInputs(), knownBots = listOf(researcher))
        val fresh = NowReducer.derive(gateway, null, freshInputs())
        assertEquals(fresh, merged)
        assertNull(merged.socketSavedAtEpochSeconds)
        assertNotNull(merged.quiet)
    }

    @Test
    fun anEmptySavedRosterDoesNotDeleteCarriedBotRows() {
        val stored = NowWidgetSnapshot(
            gatewayUrl = gateway,
            profile = null,
            needsYou = listOf(botAnswer("researcher", "bc-r")),
            running = listOf(
                NowRunningItem(
                    kind = NowRunningKind.BotWorking,
                    title = "Researcher",
                    target = NowTarget(storedSessionId = "bc-r", botProfile = "researcher"),
                    botProfile = "researcher",
                ),
            ),
            savedAtEpochSeconds = 500,
        )

        val merged = NowReducer.mergeCold(gateway, null, stored, freshInputs(), knownBots = emptyList())

        // No saved roster is no existence evidence, not proof that every bot was deleted.
        assertEquals(listOf("bc-r"), merged.needsYou.map { it.target.storedSessionId })
        assertEquals(listOf("bc-r"), merged.running.map { it.target.storedSessionId })
        assertTrue(merged.needsYou.single().carried)
        assertTrue(merged.running.single().carried)
    }

    @Test
    fun anUnavailableSessionPageDoesNotDeleteCarriedChatRows() {
        val stored = NowWidgetSnapshot(
            gatewayUrl = gateway,
            profile = null,
            needsYou = listOf(waitingItem("s-carried")),
            running = listOf(
                NowRunningItem(
                    kind = NowRunningKind.ChatTurn,
                    title = "Chat s-carried",
                    target = NowTarget(storedSessionId = "s-carried"),
                ),
            ),
            savedAtEpochSeconds = 500,
        )

        val merged = NowReducer.mergeCold(
            gateway,
            null,
            stored,
            freshInputs(sessions = emptyList(), sessionsKnown = false),
            knownBots = listOf(researcher),
        )

        // A failed REST read with no offline fallback is no existence evidence. A successful empty
        // page still uses sessionsKnown = true and drops deleted chats, as the first test proves.
        assertEquals(listOf("s-carried"), merged.needsYou.map { it.target.storedSessionId })
        assertEquals(listOf("s-carried"), merged.running.map { it.target.storedSessionId })
        assertTrue(merged.needsYou.single().carried)
        assertTrue(merged.running.single().carried)
    }
}
