package dev.hermeskotlin.core.widget

import kotlinx.serialization.Serializable

/**
 * The four home-screen widgets' state, as JSON in [WidgetSnapshotStore] and as the single contract the
 * two renderers (Compose previews here, `RemoteViews` in androidApp) both draw from (D2-D6,
 * `docs/WIDGETS-SPEC.md`). Every epoch time is seconds; a missing time is null, never 0. Every list
 * defaults to empty so an older stored snapshot decodes across additive changes.
 */

@Serializable
data class ChatsWidgetSnapshot(
    val gatewayUrl: String,
    /** The profile the list is scoped to; null is the gateway's launch profile. */
    val profile: String?,
    val rows: List<ChatWidgetRow> = emptyList(),
    val savedAtEpochSeconds: Long,
)

@Serializable
data class ChatWidgetRow(
    /** The stored session id, as `EXTRA_OPEN_SESSION` and `hermes://session/<id>` want it. */
    val storedSessionId: String,
    /** `SessionSummary.displayTitle` (title, else first preview line, else "Untitled session"). */
    val title: String,
    /** First line of the latest preview, or null. */
    val subtitle: String? = null,
    /** The `Waiting` label ("Needs approval", "Has a question", "Needs input", "Waiting for you"); null when not waiting. */
    val waitingLabel: String? = null,
    /** A turn is running now (socket-only knowledge; false in any cold snapshot). */
    val running: Boolean = false,
    /** Read state decided by `SeenChats.isUnread` semantics, not the raw gateway flag alone. */
    val unread: Boolean = false,
    val pinned: Boolean = false,
    /** The chat is a thread; best-effort, false cold. */
    val thread: Boolean = false,
    /** `SessionSummary.activityAt`; null when the row carries no time (the age is then not drawn). */
    val activityEpochSeconds: Double? = null,
    /** The row came from `OfflineCache` (or the stored snapshot), not from the live gateway. */
    val savedCopy: Boolean = false,
)

@Serializable
data class KanbanWidgetSnapshot(
    val gatewayUrl: String,
    /** The board the snapshot was actually read from — always the slug every REST call passed. */
    val boardSlug: String,
    /** `KanbanBoardMeta.name`, falling back to the slug. */
    val boardName: String,
    /** Always carries the four header keys, 0 when absent from the gateway's counts. */
    val counts: Map<String, Int> = emptyMap(),
    val rows: List<KanbanWidgetRow> = emptyList(),
    val savedAtEpochSeconds: Long,
)

enum class KanbanStaleTier { None, Amber, Red }

@Serializable
data class KanbanWidgetRow(
    val taskId: String,
    val title: String,
    /** The gateway's status word, as sent. */
    val status: String,
    val assignee: String? = null,
    val priority: Int = 0,
    val stale: KanbanStaleTier = KanbanStaleTier.None,
    /** `KanbanWarnings.count`, 0 when absent. */
    val warningCount: Int = 0,
)

@Serializable
data class ScheduledWidgetSnapshot(
    /** One gateway's jobs across every profile (`CronApi.jobs` is `profile=all`). */
    val gatewayUrl: String,
    val rows: List<ScheduledWidgetRow> = emptyList(),
    val savedAtEpochSeconds: Long,
)

@Serializable
data class ScheduledWidgetRow(
    val jobId: String,
    /** The cron store the job lives in, as the gateway tags it (`CronJob.profile`). */
    val profile: String? = null,
    /** `CronJob.displayName`. */
    val displayName: String,
    /** The owning bot's `label` when the job is that bot's routine, else null. */
    val routineOwnerLabel: String? = null,
    /** `CronJob.scheduleDisplay`; null when blank. */
    val scheduleDisplay: String? = null,
    /** `CronJob.nextRunEpochSeconds` (null when paused, finished or unparseable). */
    val nextRunEpochSeconds: Long? = null,
    /** The gateway's state word: `scheduled`, `paused`, `completed`, `error`. */
    val state: String,
    /** `CronJob.problem` text; null when the job is fine. */
    val problemSummary: String? = null,
    /** True exactly when `problemSummary != null` and the job isn't a quiet pause. */
    val isProblem: Boolean = false,
    /**
     * Which problem word the row's pill shows (docs/WIDGETS-DESIGN.md §9.2): a missed fire, a
     * failed delivery, or a plain failure. Null when [isProblem] is false; the renderer reads
     * [ScheduledProblemKind.Failed] for stored rows written before this field existed.
     */
    val problemKind: ScheduledProblemKind? = null,
)

enum class ScheduledProblemKind { Failed, DeliveryFailed, Missed }

enum class NowNeedsYouKind {
    /** A (non-bot) chat is held on the user; [NowNeedsYouItem.detail] is the `Waiting` label. */
    ChatWaiting,

    /** A bot's own chat is held on the user. */
    BotAnswer,

    /** A bot can't work until something is fixed; detail is the trouble's hint. */
    BotTrouble,

    /** A bot's routine went wrong; detail is the job's `displayName`. */
    RoutineFailed,

    /** A Kanban task is blocked or in review; detail is the status word. */
    KanbanAttention,

    /** A cron job failed, was undelivered or missed its last fire; detail is `CronJob.problem`. */
    CronProblem,
}

enum class NowRunningKind { ChatTurn, BotWorking, KanbanRunning }

/** Where a Now item goes when tapped. Exactly one group is non-null. */
@Serializable
data class NowTarget(
    val storedSessionId: String? = null,
    val title: String? = null,
    val botProfile: String? = null,
    val kanbanBoard: String? = null,
    val kanbanTaskId: String? = null,
    val scheduledJobId: String? = null,
)

@Serializable
data class NowNeedsYouItem(
    val kind: NowNeedsYouKind,
    val title: String,
    val detail: String? = null,
    val target: NowTarget,
    /** Bot face rendering key: the bot profile name, else null for non-bot items. */
    val botProfile: String? = null,
    /** Newest relevant activity, for sorting; null when nothing is known. */
    val activityEpochSeconds: Double? = null,
    /** True when this row was carried over from the stored snapshot on a cold refresh. */
    val carried: Boolean = false,
)

@Serializable
data class NowRunningItem(
    val kind: NowRunningKind,
    val title: String,
    val target: NowTarget,
    val botProfile: String? = null,
    /** For ChatTurn: the chat's `activityAt`. For BotWorking: the bot's `lastActivity()`. For KanbanRunning: the task's `startedAt`. */
    val startedAtEpochSeconds: Double? = null,
    val carried: Boolean = false,
)

@Serializable
data class NowQuietState(
    /** The soonest upcoming job's next run; null when nothing is scheduled. */
    val nextRunEpochSeconds: Long? = null,
    /** That job's `displayName`. */
    val nextJobName: String? = null,
)

@Serializable
data class NowWidgetSnapshot(
    val gatewayUrl: String,
    /** The profile whose chats and bots this Now shows; null is the launch profile. */
    val profile: String?,
    val needsYou: List<NowNeedsYouItem> = emptyList(),
    val running: List<NowRunningItem> = emptyList(),
    /** Non-null exactly when `needsYou` and `running` are both empty. */
    val quiet: NowQuietState? = null,
    /**
     * When the socket-derived inputs behind this snapshot were last actually collected. Null when they
     * are live-fresh; a timestamp when they were carried from the stored snapshot on a cold refresh,
     * which is what the "as of" line renders.
     */
    val socketSavedAtEpochSeconds: Long? = null,
    val savedAtEpochSeconds: Long,
)
