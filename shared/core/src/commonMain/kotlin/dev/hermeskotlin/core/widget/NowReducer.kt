package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotTrouble
import dev.hermeskotlin.core.bots.forRoster
import dev.hermeskotlin.core.bots.lastActivity
import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.cron.isRoutineOf
import dev.hermeskotlin.core.cron.problem
import dev.hermeskotlin.core.kanban.KanbanStale
import dev.hermeskotlin.core.kanban.KanbanTask
import dev.hermeskotlin.core.kanban.stale
import dev.hermeskotlin.core.sessions.SeenChats
import dev.hermeskotlin.core.sessions.SessionSummary

/**
 * What `NowReducer.derive` reads: one page of the Recent list plus the live socket maps (all empty on a
 * cold, REST-only refresh) and the REST inputs, so the same pure function serves the app-live pipeline
 * and the cold merge.
 */
data class NowInputs(
    val sessions: List<SessionSummary>,
    /** Null cold; when present, its `isUnread` semantics decide chat read state. */
    val seen: SeenChats?,
    /** `AttentionTracker.waiting` by stored id (empty cold). */
    val waiting: Map<String, Waiting>,
    /** `AttentionTracker.running` by stored id (empty cold). */
    val runningChats: Map<String, Boolean>,
    /** The bot roster (empty cold: the REST surface has no roster). */
    val bots: List<Bot>,
    /** `BotHealth.troubles` by profile (empty cold). */
    val troubles: Map<String, BotTrouble>,
    /** Each bot's most pressing failing routine, by profile (empty cold). */
    val failingRoutines: Map<String, CronJob>,
    /** Every cron job across profiles (the D11 source). */
    val cronJobs: List<CronJob>,
    /** The pinned board's rows (empty cold-without-board). */
    val kanbanRows: List<KanbanTask>,
    val kanbanBoardSlug: String?,
    /** The device clock, epoch seconds. */
    val nowSeconds: Long,
)

/**
 * The Now widget's derivation (D12, `docs/WIDGETS-SPEC.md`): a pure function, so the app-live pipeline
 * (D13) and the cold re-derivation (D14) produce the same shape from the same inputs. Needs-you items
 * are tiered with a strict precedence — waiting chats, bots held on an answer, bot troubles, failing
 * routines, then blocked/review tasks, then cron problems — one item per source, capped at
 * [NEEDS_YOU_CAP]. Running items — chat turns, working bots, running tasks — are capped at [RUNNING_CAP]
 * and never come from `SessionSummary.isActive`. When both lists are empty the snapshot is [NowWidgetSnapshot.quiet].
 */
object NowReducer {

    const val NEEDS_YOU_CAP = 4
    const val RUNNING_CAP = 3

    fun derive(gatewayUrl: String, profile: String?, inputs: NowInputs): NowWidgetSnapshot {
        val roster = inputs.bots.forRoster()
        // Both lineage ids: a compressed bot chat answers to either its canonical id or its open id.
        val botChatIds = roster.flatMap { listOfNotNull(it.canonicalSession?.id, it.canonicalSession?.openId) }.toSet()
        val needsYou = buildList {
            // Tier 0: a non-bot chat held on the user, newest activity first.
            inputs.sessions
                .filter { session -> botChatIds.none { session.isChat(it) } }
                .mapNotNull { session ->
                    val label = inputs.waiting.firstNotNullOfOrNull { (id, waiting) -> waiting.takeIf { session.isChat(id) } } ?: return@mapNotNull null
                    NowNeedsYouItem(
                        kind = NowNeedsYouKind.ChatWaiting,
                        title = session.displayTitle,
                        detail = label.label,
                        target = NowTarget(storedSessionId = session.id, title = session.displayTitle),
                        activityEpochSeconds = session.activityAt,
                    )
                }
                .sortedWith(compareByDescending<NowNeedsYouItem> { it.activityEpochSeconds ?: Double.NEGATIVE_INFINITY }.thenBy { it.target.storedSessionId })
                .let(::addAll)
            // Tiers 1-3: one item per bot at its most pressing (an answer outranks a trouble, a trouble
            // a failing routine). Tier groups keep their D12 precedence; inside a tier, roster order
            // (pinned first, then newest).
            data class BotItem(val tier: Int, val item: NowNeedsYouItem)
            val botItems = roster.mapNotNull { bot ->
                val held = bot.canonicalSession
                    ?.let { chat -> inputs.waiting.firstNotNullOfOrNull { (key, waiting) -> waiting.takeIf { chat.isChat(key) } } }
                when {
                    held != null -> BotItem(
                        1,
                        NowNeedsYouItem(
                            kind = NowNeedsYouKind.BotAnswer,
                            title = bot.label,
                            detail = held.label,
                            target = NowTarget(storedSessionId = bot.canonicalSession.openId, title = bot.label, botProfile = bot.name),
                            botProfile = bot.name,
                            activityEpochSeconds = bot.canonicalSession.activityAt,
                        ),
                    )
                    inputs.troubles[bot.name] != null -> BotItem(
                        2,
                        NowNeedsYouItem(
                            kind = NowNeedsYouKind.BotTrouble,
                            title = bot.label,
                            detail = inputs.troubles.getValue(bot.name).problem.hint,
                            target = NowTarget(storedSessionId = bot.canonicalSession?.openId, title = bot.label, botProfile = bot.name),
                            botProfile = bot.name,
                            activityEpochSeconds = bot.lastActivity(),
                        ),
                    )
                    inputs.failingRoutines[bot.name] != null -> BotItem(
                        3,
                        NowNeedsYouItem(
                            kind = NowNeedsYouKind.RoutineFailed,
                            title = bot.label,
                            detail = inputs.failingRoutines.getValue(bot.name).displayName,
                            target = NowTarget(
                                storedSessionId = bot.canonicalSession?.openId,
                                title = bot.label,
                                botProfile = bot.name,
                                scheduledJobId = inputs.failingRoutines.getValue(bot.name).id,
                            ),
                            botProfile = bot.name,
                            activityEpochSeconds = inputs.failingRoutines.getValue(bot.name).lastRunEpochSeconds,
                        ),
                    )
                    else -> null
                }
            }
            for (tier in 1..3) botItems.filter { it.tier == tier }.forEach { add(it.item) }
            // Tier 4: blocked, then review, Kanban tasks — priority, staleness, age.
            inputs.kanbanRows
                .filter { it.status == "blocked" || it.status == "review" }
                .sortedWith(
                    compareBy<KanbanTask> { if (it.status == "blocked") 0 else 1 }
                        .thenByDescending { it.priority }
                        .thenByDescending { tierRank(it.stale()) }
                        .thenBy { it.createdAt ?: Long.MAX_VALUE }
                        .thenBy { it.id },
                )
                .forEach { task ->
                    add(
                        NowNeedsYouItem(
                            kind = NowNeedsYouKind.KanbanAttention,
                            title = task.title,
                            detail = task.status,
                            target = NowTarget(kanbanBoard = inputs.kanbanBoardSlug, kanbanTaskId = task.id),
                            activityEpochSeconds = task.createdAt?.toDouble(),
                        )
                    )
                }
            // Tier 5: cron problems, newest failure first.
            inputs.cronJobs
                .filter { ScheduledWidgetAssembler.isProblem(it) }
                .sortedWith(compareByDescending<CronJob> { it.missedFireEpochSeconds ?: it.lastRunEpochSeconds ?: 0.0 }.thenBy { it.displayName })
                .forEach { job ->
                    add(
                        NowNeedsYouItem(
                            kind = NowNeedsYouKind.CronProblem,
                            title = job.displayName,
                            detail = job.problem,
                            target = NowTarget(scheduledJobId = job.id),
                            activityEpochSeconds = job.lastRunEpochSeconds,
                        )
                    )
                }
        }.take(NEEDS_YOU_CAP)

        val running = buildList {
            // Chat turns from the live maps only — never `isActive`.
            inputs.sessions
                .filter { session -> inputs.runningChats.any { (id, running) -> running && session.isChat(id) } }
                .filter { session -> botChatIds.none { session.isChat(it) } }
                .sortedByDescending { it.activityAt ?: Double.NEGATIVE_INFINITY }
                .forEach { session ->
                    add(
                        NowRunningItem(
                            kind = NowRunningKind.ChatTurn,
                            title = session.displayTitle,
                            target = NowTarget(storedSessionId = session.id, title = session.displayTitle),
                            startedAtEpochSeconds = session.activityAt,
                        )
                    )
                }
            // Working bots, most recently active first.
            roster
                .filter { it.isWorking(inputs.nowSeconds.toDouble()) }
                .sortedByDescending { it.lastActivity() ?: Double.NEGATIVE_INFINITY }
                .forEach { bot ->
                    add(
                        NowRunningItem(
                            kind = NowRunningKind.BotWorking,
                            title = bot.label,
                            target = NowTarget(storedSessionId = bot.canonicalSession?.openId, title = bot.label, botProfile = bot.name),
                            botProfile = bot.name,
                            startedAtEpochSeconds = bot.lastActivity(),
                        )
                    )
                }
            // Running tasks, longest-running first.
            inputs.kanbanRows
                .filter { it.status == "running" }
                .sortedBy { it.currentRunStartedAt ?: it.startedAt ?: Long.MAX_VALUE }
                .forEach { task ->
                    add(
                        NowRunningItem(
                            kind = NowRunningKind.KanbanRunning,
                            title = task.title,
                            target = NowTarget(kanbanBoard = inputs.kanbanBoardSlug, kanbanTaskId = task.id),
                            startedAtEpochSeconds = (task.currentRunStartedAt ?: task.startedAt)?.toDouble(),
                        )
                    )
                }
        }.take(RUNNING_CAP)

        val quiet = if (needsYou.isEmpty() && running.isEmpty()) {
            inputs.cronJobs
                .filter { it.state == "scheduled" && it.nextRunEpochSeconds != null }
                .sortedWith(compareBy<CronJob> { it.nextRunEpochSeconds }.thenBy { it.displayName })
                .firstOrNull()
                ?.let { NowQuietState(nextRunEpochSeconds = it.nextRunEpochSeconds?.toLong(), nextJobName = it.displayName) }
                ?: NowQuietState()
        } else null

        return NowWidgetSnapshot(
            gatewayUrl = gatewayUrl,
            profile = profile,
            needsYou = needsYou,
            running = running,
            quiet = quiet,
            socketSavedAtEpochSeconds = null,
            savedAtEpochSeconds = inputs.nowSeconds,
        )
    }

    /**
     * The cold, REST-only refresh (D14): re-derive everything REST can see, then carry the socket-only
     * items of [stored] (waiting chats, bot answers, bot troubles, failing routines, chat turns, working
     * bots) across — dropping any whose target vanished from the fresh data (a deleted chat, a bot
     * [knownBots] no longer knows) — re-rank with [derive]'s tiers so fresh REST items land in their
     * precedence and carried items sink below fresh ones of the same source, and stamp every carried row
     * `carried = true`. No socket method is touched here; [freshInputs] holds REST results only.
     */
    fun mergeCold(
        gatewayUrl: String,
        profile: String?,
        stored: NowWidgetSnapshot?,
        freshInputs: NowInputs,
        knownBots: List<Bot>,
    ): NowWidgetSnapshot {
        val fresh = derive(gatewayUrl, profile, freshInputs)
        if (stored == null) return fresh

        val knownBotNames = knownBots.map { it.name }.toSet()
        val chatIds = freshInputs.sessions.flatMap { listOf(it.id) + it.lineageIds.orEmpty() }.toSet()
        fun NowTarget.chatExists(): Boolean = storedSessionId == null || storedSessionId in chatIds
        fun NowTarget.botExists(): Boolean =
            botProfile == null || knownBotNames.isEmpty() || botProfile in knownBotNames

        // A bot row's target also names the bot chat, which the Recent page never shows (bot chats are
        // machine-originated), so the roster is the only existence signal for it; a plain chat row is
        // judged by the page alone. An empty saved roster supplies no evidence, so it keeps bot rows.
        fun NowTarget.exists(): Boolean = if (botProfile != null) botExists() else chatExists()

        val carriedNeedsYou = stored.needsYou
            .filter { it.kind == NowNeedsYouKind.ChatWaiting || it.kind == NowNeedsYouKind.BotAnswer || it.kind == NowNeedsYouKind.BotTrouble || it.kind == NowNeedsYouKind.RoutineFailed }
            .filter { it.target.exists() }
            .map { it.copy(carried = true) }
        val carriedRunning = stored.running
            .filter { it.kind == NowRunningKind.ChatTurn || it.kind == NowRunningKind.BotWorking }
            .filter { it.target.exists() }
            .map { it.copy(carried = true) }

        if (carriedNeedsYou.isEmpty() && carriedRunning.isEmpty()) return fresh.copy(
            socketSavedAtEpochSeconds = stored.socketSavedAtEpochSeconds ?: stored.savedAtEpochSeconds,
        )

        // Re-rank per D12: tiers keep their precedence; within a tier the D12 sort applies, and when a
        // tier holds both fresh and carried rows a fresh row wins ties (carried rows sink as fresh REST
        // items arrive). Cold fresh inputs hold no tier 0-3 or ChatTurn/BotWorking rows, so those tiers
        // are carried-only in practice; the per-tier sorts keep this honest if that ever changes.
        val rosterIndex = knownBots.forRoster().mapIndexed { index, bot -> bot.name to index }.toMap()
        val tierOf: (NowNeedsYouItem) -> Int = { when (it.kind) {
            NowNeedsYouKind.ChatWaiting -> 0
            NowNeedsYouKind.BotAnswer -> 1
            NowNeedsYouKind.BotTrouble -> 2
            NowNeedsYouKind.RoutineFailed -> 3
            NowNeedsYouKind.KanbanAttention -> 4
            NowNeedsYouKind.CronProblem -> 5
        } }
        val mergedNeedsYou = ArrayList<NowNeedsYouItem>(fresh.needsYou.size + carriedNeedsYou.size)
        for (tier in 0..5) {
            val freshTier = fresh.needsYou.filter { tierOf(it) == tier }
            val carriedTier = carriedNeedsYou.filter { tierOf(it) == tier }
            when (tier) {
                0 -> mergedNeedsYou += (freshTier + carriedTier).sortedWith(
                    compareByDescending<NowNeedsYouItem> { it.activityEpochSeconds ?: Double.NEGATIVE_INFINITY }
                        .thenBy { it.target.storedSessionId }
                        .thenBy { it.carried },
                )
                1, 2, 3 -> mergedNeedsYou += (freshTier + carriedTier).sortedWith(
                    compareBy<NowNeedsYouItem> { rosterIndex[it.botProfile] ?: Int.MAX_VALUE }
                        .thenBy { tierOf(it) }
                        .thenBy { it.carried },
                )
                else -> mergedNeedsYou += freshTier
            }
        }
        val needsYou = mergedNeedsYou.take(NEEDS_YOU_CAP)

        val runningTierOf: (NowRunningItem) -> Int = { when (it.kind) {
            NowRunningKind.ChatTurn -> 0
            NowRunningKind.BotWorking -> 1
            NowRunningKind.KanbanRunning -> 2
        } }
        val mergedRunning = ArrayList<NowRunningItem>(fresh.running.size + carriedRunning.size)
        for (tier in 0..2) {
            val freshTier = fresh.running.filter { runningTierOf(it) == tier }
            val carriedTier = carriedRunning.filter { runningTierOf(it) == tier }
            when (tier) {
                0, 1 -> mergedRunning += (freshTier + carriedTier).sortedWith(
                    compareByDescending<NowRunningItem> { it.startedAtEpochSeconds ?: Double.NEGATIVE_INFINITY }
                        .thenBy { it.carried },
                )
                else -> mergedRunning += freshTier
            }
        }
        val running = mergedRunning.take(RUNNING_CAP)

        return NowWidgetSnapshot(
            gatewayUrl = gatewayUrl,
            profile = profile,
            needsYou = needsYou,
            running = running,
            quiet = if (needsYou.isEmpty() && running.isEmpty()) fresh.quiet else null,
            socketSavedAtEpochSeconds = stored.socketSavedAtEpochSeconds ?: stored.savedAtEpochSeconds,
            savedAtEpochSeconds = fresh.savedAtEpochSeconds,
        )
    }

    private fun tierRank(tier: KanbanStale): Int = when (tier) {
        KanbanStale.Red -> 2
        KanbanStale.Amber -> 1
        KanbanStale.None -> 0
    }
}
