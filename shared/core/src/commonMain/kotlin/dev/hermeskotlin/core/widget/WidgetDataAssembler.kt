package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.bots.BotHealth
import dev.hermeskotlin.core.bots.BotTrouble
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.cache.OfflineCache
import dev.hermeskotlin.core.chat.AttentionTracker
import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.core.common.runCatchingCancellable
import dev.hermeskotlin.core.cron.CronApi
import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.cron.isRoutineOf
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.kanban.KanbanApi
import dev.hermeskotlin.core.sessions.SessionListFilter
import dev.hermeskotlin.core.sessions.SessionPage
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.sessions.SeenStore
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.kanbanBoard
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Fetches the REST inputs, combines them with the caller's socket-side maps, computes all four widget
 * snapshots deterministically and writes them to [snapshotStore] (D9-D14, D26; `docs/WIDGETS-SPEC.md`).
 *
 * Two entry points mirror the two freshness modes:
 * - [refreshAllLive] (D13) runs inside the app process: it collects the live `AttentionTracker` /
 *   `BotHealth` state and the bot roster from the Koin singletons and derives Now fresh, stamped
 *   `socketSavedAtEpochSeconds = null` (live-fresh).
 * - [refreshAllCold] (D14) never opens a socket: it re-derives the REST-derivable tiers from REST alone
 *   and carries the socket-only rows of the stored Now snapshot across via [NowReducer.mergeCold].
 *
 * The per-widget [refreshNow] / [refreshChats] / [refreshKanban] / [refreshScheduled] methods hold the
 * same pipelines one widget at a time (D23's refresh pipelines, D26's "a `cron.changed` never re-fetches
 * the Kanban board"): every method performs only the reads its widget needs and writes only its own
 * snapshot. [refreshAll] composes them unchanged.
 *
 * Failure semantics (D26): a failed Kanban or Scheduled read writes nothing — the renderer re-renders the
 * stored snapshot. Chats falls back (D9): `OfflineCache`, then the stored snapshot's rows with
 * `savedCopy = true`, then an empty snapshot — the widget always renders something honest.
 */
/**
 * The per-widget pipelines a refresher drives. An interface (not [WidgetDataAssembler] itself) so
 * the refresher's routing, deadline and fallback behaviour are testable without a gateway.
 */
interface WidgetRefreshPipelines {
    /** One widget's pipeline with the app process alive; only the reads that widget needs run (D26). */
    suspend fun refreshNow(url: GatewayUrl, profile: String?, threadIds: Set<String> = emptySet()): NowWidgetSnapshot

    /** One widget's pipeline over REST alone (D14): carried socket rows merge across from the stored snapshot. */
    suspend fun refreshNowCold(url: GatewayUrl, profile: String?, knownBots: List<Bot>, threadIds: Set<String> = emptySet()): NowWidgetSnapshot

    /** One widget's pipeline with the app process alive; only the Recent page and its evidence run (D26). */
    suspend fun refreshChats(url: GatewayUrl, profile: String?, threadIds: Set<String> = emptySet()): ChatsWidgetSnapshot

    /** One widget's pipeline: `boards()` and the chosen board, nothing else (D26). */
    suspend fun refreshKanban(url: GatewayUrl): KanbanWidgetSnapshot?

    /** One widget's pipeline: the cross-profile `jobs()` read and the warm roster, nothing else (D26). */
    suspend fun refreshScheduled(url: GatewayUrl, live: Boolean = true): ScheduledWidgetSnapshot?
}

class WidgetDataAssembler(
    private val sessionsApi: SessionsApi,
    private val cronApi: CronApi,
    private val kanbanApi: KanbanApi,
    private val botsApi: BotsApi,
    private val attention: AttentionTracker,
    private val botHealth: BotHealth,
    private val seenStore: SeenStore,
    private val offline: OfflineCache,
    private val settings: SettingsStore,
    private val snapshotStore: WidgetSnapshotStore,
    private val nowSeconds: () -> Long = { getTimeMillis() / 1000 },
) : WidgetRefreshPipelines {

    /** The socket-side state the app process can supply (empty everywhere cold, or in a pure test). */
    data class LiveMaps(
        val waiting: Map<String, Waiting> = emptyMap(),
        val runningChats: Map<String, Boolean> = emptyMap(),
        val bots: List<Bot> = emptyList(),
        val troubles: Map<String, BotTrouble> = emptyMap(),
    )

    data class AllWidgets(
        val now: NowWidgetSnapshot?,
        val chats: ChatsWidgetSnapshot?,
        val kanban: KanbanWidgetSnapshot?,
        val scheduled: ScheduledWidgetSnapshot?,
    )

    /** D13: refresh every widget from inside the app process, with live socket state. */
    suspend fun refreshAllLive(url: GatewayUrl, profile: String?, threadIds: Set<String> = emptySet()): AllWidgets {
        val live = liveMaps()
        return refreshAll(url, profile, live = live, cold = false, threadIds = threadIds)
    }

    /** D14: refresh every widget REST-only; Now merges its socket-only rows from the stored snapshot. */
    suspend fun refreshAllCold(url: GatewayUrl, profile: String?, threadIds: Set<String> = emptySet()): AllWidgets =
        refreshAll(url, profile, live = LiveMaps(), cold = true, threadIds = threadIds)

    /** One widget's pipeline with the app process alive; only the reads that widget needs run (D26). */
    override suspend fun refreshNow(url: GatewayUrl, profile: String?, threadIds: Set<String>): NowWidgetSnapshot =
        nowPipeline(url, profile, liveMaps(), cold = false, threadIds)

    /**
     * One widget's pipeline over REST alone (D14): the carried socket rows of the stored Now snapshot
     * merge across via [NowReducer.mergeCold], judged for existence against [knownBots] — the roster a
     * background refresh has is the saved one (`BotNotifier.knownRoster`), since a broadcast-started
     * process never ran `profiles.list` over the socket.
     */
    override suspend fun refreshNowCold(url: GatewayUrl, profile: String?, knownBots: List<Bot>, threadIds: Set<String>): NowWidgetSnapshot =
        nowPipeline(url, profile, LiveMaps(bots = knownBots), cold = true, threadIds)

    /** One widget's pipeline with the app process alive; only the Recent page and its evidence run (D26). */
    override suspend fun refreshChats(url: GatewayUrl, profile: String?, threadIds: Set<String>): ChatsWidgetSnapshot =
        chatsPipeline(url, profile, liveMaps(), threadIds)

    /** One widget's pipeline: `boards()` and the chosen board, nothing else (D26). */
    override suspend fun refreshKanban(url: GatewayUrl): KanbanWidgetSnapshot? = kanbanPipeline(url)

    /** One widget's pipeline: the cross-profile `jobs()` read and the warm roster, nothing else (D26). */
    override suspend fun refreshScheduled(url: GatewayUrl, live: Boolean): ScheduledWidgetSnapshot? =
        scheduledPipeline(url, if (live) liveMaps() else LiveMaps())

    /**
     * The combined pipeline. [refreshAllLive]/[refreshAllCold] keep the freshness mode explicit; the
     * per-widget public methods above hold the same pipelines one widget at a time. Every REST read is
     * independent and best-effort, as D26's deadline demands, and `threadIds` is the app's best-effort
     * thread knowledge (empty cold).
     */
    suspend fun refreshAll(url: GatewayUrl, profile: String?, live: LiveMaps, cold: Boolean, threadIds: Set<String>): AllWidgets =
        AllWidgets(
            now = nowPipeline(url, profile, live, cold, threadIds),
            chats = chatsPipeline(url, profile, live, threadIds),
            kanban = kanbanPipeline(url),
            scheduled = scheduledPipeline(url, live),
        )

    /** The socket-side maps as the app's Koin singletons currently hold them (D13). */
    private suspend fun liveMaps(): LiveMaps {
        val roster = runCatchingCancellable { botsApi.roster().bots }.getOrDefault(emptyList())
        return LiveMaps(
            waiting = attention.waiting.value,
            runningChats = attention.running.value,
            bots = roster,
            troubles = botHealth.troubles.value,
        )
    }

    // Now (D12-D14).
    private suspend fun nowPipeline(url: GatewayUrl, profile: String?, live: LiveMaps, cold: Boolean, threadIds: Set<String>): NowWidgetSnapshot {
        val scope = url.value
        val clock = nowSeconds()
        val sessionsPage = readSessionsPage(url, profile)
        val offlineSaved = readOfflineSaved(url, profile, sessionsPage)
        val jobsResult = runCatchingCancellable { cronApi.jobs(url).orNull() }.getOrNull()
        val boardsResult = runCatchingCancellable { kanbanApi.boards(url).orNull() }.getOrNull()
        val pin = settings.settings.filterNotNull().first().kanbanBoard(url)
        val chosen = boardsResult?.let { KanbanWidgetAssembler.resolveBoard(it, pin) }
        val board = chosen?.let { runCatchingCancellable { kanbanApi.board(url, board = it.slug).orNull() }.getOrNull() }
        val seen = readSeen(url, profile)
        val sessions = sessionsPage?.sessions ?: offlineSaved?.sessions.orEmpty()

        val nowInputs = NowInputs(
            sessions = sessions,
            seen = seen,
            waiting = live.waiting,
            runningChats = live.runningChats,
            bots = live.bots,
            troubles = live.troubles,
            failingRoutines = failingRoutines(live.bots, jobsResult.orEmpty()),
            cronJobs = jobsResult.orEmpty(),
            kanbanRows = board?.columns.orEmpty().flatMap { it.tasks },
            kanbanBoardSlug = chosen?.slug ?: pin,
            nowSeconds = clock,
        )
        val now = if (cold) {
            NowReducer.mergeCold(scope, profile, snapshotStore.readNow(scope, profile), nowInputs, live.bots)
        } else {
            NowReducer.derive(scope, profile, nowInputs)
        }
        snapshotStore.saveNow(now)
        return now
    }

    // Chats (D9): the REST page; else the offline saved list; else the stored snapshot's rows.
    private suspend fun chatsPipeline(url: GatewayUrl, profile: String?, live: LiveMaps, threadIds: Set<String>): ChatsWidgetSnapshot {
        val scope = url.value
        val clock = nowSeconds()
        val sessionsPage = readSessionsPage(url, profile)
        val offlineSaved = readOfflineSaved(url, profile, sessionsPage)
        val seen = readSeen(url, profile)
        // Both lineage ids: a compressed bot chat answers to either its canonical id or its open id.
        val botChatIds = live.bots.flatMap { listOfNotNull(it.canonicalSession?.id, it.canonicalSession?.openId) }.toSet()
        val sessions = sessionsPage?.sessions ?: offlineSaved?.sessions.orEmpty()

        val chats = when {
            sessionsPage != null -> ChatsWidgetAssembler.assemble(scope, profile, sessions, seen, live.waiting, live.runningChats, botChatIds, threadIds, savedCopy = false, nowSeconds = clock)
            offlineSaved != null -> ChatsWidgetAssembler.assemble(scope, profile, sessions, seen, live.waiting, live.runningChats, botChatIds, threadIds, savedCopy = true, nowSeconds = clock)
            else -> snapshotStore.readChats(scope, profile)?.let { stored ->
                stored.copy(rows = stored.rows.map { it.copy(savedCopy = true) }, savedAtEpochSeconds = clock)
            } ?: ChatsWidgetSnapshot(gatewayUrl = scope, profile = profile, rows = emptyList(), savedAtEpochSeconds = clock)
        }
        snapshotStore.saveChats(chats)
        return chats
    }

    // Kanban (D10): the resolved slug — never the pin's alone — is what REST passed and what stores.
    private suspend fun kanbanPipeline(url: GatewayUrl): KanbanWidgetSnapshot? {
        val scope = url.value
        val clock = nowSeconds()
        val boardsResult = runCatchingCancellable { kanbanApi.boards(url).orNull() }.getOrNull()
        val pin = settings.settings.filterNotNull().first().kanbanBoard(url)
        val chosen = boardsResult?.let { KanbanWidgetAssembler.resolveBoard(it, pin) }
        val board = chosen?.let { runCatchingCancellable { kanbanApi.board(url, board = it.slug).orNull() }.getOrNull() }
        val kanban = if (boardsResult != null && chosen != null) {
            KanbanWidgetAssembler.assemble(
                gatewayUrl = scope,
                boards = boardsResult,
                board = board,
                boardSlug = chosen.slug,
                serverNowSeconds = board?.now ?: clock,
                deviceNowSeconds = clock,
            ).also { snapshotStore.saveKanban(it) }
        } else if (boardsResult != null) {
            // The boards list came back empty: the reachable "No Kanban board" state (design §8.3),
            // stored so a later failure doesn't resurrect a board the gateway no longer has.
            KanbanWidgetAssembler.assemble(
                gatewayUrl = scope,
                boards = boardsResult,
                board = null,
                boardSlug = "",
                serverNowSeconds = clock,
                deviceNowSeconds = clock,
            ).also { snapshotStore.saveKanban(it) }
        } else {
            val fallbackSlug = chosen?.slug ?: pin
            if (fallbackSlug != null) snapshotStore.readKanban(scope, fallbackSlug) else null
        }
        return kanban
    }

    // Scheduled (D11): an empty success overwrites (a cleared gateway has no jobs); a failed read
    // leaves the stored snapshot standing.
    private suspend fun scheduledPipeline(url: GatewayUrl, live: LiveMaps): ScheduledWidgetSnapshot? {
        val scope = url.value
        val clock = nowSeconds()
        val jobsResult = runCatchingCancellable { cronApi.jobs(url).orNull() }.getOrNull()
        val scheduled = if (jobsResult != null) {
            ScheduledWidgetAssembler.assemble(scope, jobsResult, live.bots, clock)
                .also { snapshotStore.saveScheduled(it) }
        } else {
            snapshotStore.readScheduled(scope)
        }
        return scheduled
    }

    private suspend fun readSessionsPage(url: GatewayUrl, profile: String?) =
        runCatchingCancellable { sessionsApi.list(url, limit = 50, filter = SessionListFilter.Recent, profile = profile).orNull() }.getOrNull()

    private suspend fun readOfflineSaved(url: GatewayUrl, profile: String?, sessionsPage: SessionPage?) =
        if (sessionsPage == null) runCatchingCancellable { offline.savedList(url, profile, SessionListFilter.Recent) }.getOrNull() else null

    private suspend fun readSeen(url: GatewayUrl, profile: String?) =
        runCatchingCancellable { seenStore.seen(url, profile).first() }.getOrNull()

    /** The success value of an [ApiResult], or null for every failure shape. */
    private fun <T> dev.hermeskotlin.core.network.ApiResult<T>.orNull(): T? =
        (this as? dev.hermeskotlin.core.network.ApiResult.Success)?.value

    /** The app's own per-bot failing-routine rule, verbatim (`BotsViewModel`): D11 restricted to one bot's routines. */
    internal fun failingRoutines(bots: List<Bot>, jobs: List<CronJob>): Map<String, CronJob> =
        bots.mapNotNull { bot ->
            jobs.firstOrNull { it.isRoutineOf(bot.name) && ScheduledWidgetAssembler.isProblem(it) }
                ?.let { bot.name to it }
        }.toMap()
}
