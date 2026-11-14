package dev.hermeskotlin.android.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dev.hermeskotlin.android.HeraldActivity
import dev.hermeskotlin.android.notify.ChatNotifications

/**
 * The widget `PendingIntent` contract (D19, `docs/WIDGETS-SPEC.md`): every tap builds the
 * notification-code intent — explicit [HeraldActivity], `ACTION_MAIN` + `CATEGORY_LAUNCHER`, the
 * single-task flags, `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT` — with a per-target request code,
 * because request codes that collide share one destination inside `RemoteViews`.
 *
 * Row intents carry extras; header intents carry the plain `hermes://` link as `intent.data`. Both
 * reach `HeraldActivity.openLinkedChat`, whose precedence handles either shape; while App lock is
 * on, `heldIntent` replays the same intent after the unlock, so widget taps pass the existing gate.
 */
object WidgetIntents {

    // Extras, named like ChatNotifications' (`open_inbox`, `open_session_id`, …):
    const val EXTRA_OPEN_KANBAN_BOARD = "open_kanban_board"          // String: the board slug
    const val EXTRA_OPEN_KANBAN_TASK = "open_kanban_task"            // String: the task id
    const val EXTRA_OPEN_SCHEDULED_JOB = "open_scheduled_job"        // String: the job id
    const val EXTRA_OPEN_SCHEDULED_PROFILE = "open_scheduled_profile" // String: a bot profile for its Routines page

    // WidgetViewsService routing and scope (D23): the collection factory reads its own snapshot only.
    const val EXTRA_WIDGET_KIND = "widget_kind"
    const val KIND_CHATS = "chats"
    const val KIND_KANBAN = "kanban"
    const val KIND_SCHEDULED = "scheduled"
    const val EXTRA_WIDGET_GATEWAY = "widget_gateway"
    const val EXTRA_WIDGET_PROFILE = "widget_profile"
    const val EXTRA_WIDGET_BOARD = "widget_board"

    // Manual-refresh broadcast actions, one per widget (D26 trigger 2):
    const val ACTION_REFRESH_NOW = "dev.herald.android.widget.action.REFRESH_NOW"
    const val ACTION_REFRESH_CHATS = "dev.herald.android.widget.action.REFRESH_CHATS"
    const val ACTION_REFRESH_KANBAN = "dev.herald.android.widget.action.REFRESH_KANBAN"
    const val ACTION_REFRESH_SCHEDULED = "dev.herald.android.widget.action.REFRESH_SCHEDULED"

    /** Deep links (D17); the Now body's quiet-state quick actions reuse the chat ones. */
    private const val LINK_NOW = "hermes://now"
    private const val LINK_CHATS = "hermes://chats"
    private const val LINK_KANBAN = "hermes://kanban"
    private const val LINK_SCHEDULED = "hermes://scheduled"
    private const val LINK_NEW_CHAT = "hermes://new-chat"
    private const val LINK_NEW_CHAT_VOICE = "hermes://new-chat-voice"

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun appIntent(context: Context): Intent = Intent(context, HeraldActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)

    private fun activity(context: Context, request: String, configure: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getActivity(
            context,
            request.hashCode(),
            appIntent(context)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply(configure),
            FLAGS,
        )

    /** A chat row: the stored session id and the existing title extra, like a chat notification. */
    fun openChat(context: Context, sessionId: String, title: String?): PendingIntent =
        activity(context, "chat:$sessionId") {
            putExtra(ChatNotifications.EXTRA_OPEN_SESSION, sessionId)
            title?.let { putExtra(ChatNotifications.EXTRA_OPEN_TITLE, it) }
        }

    /** A bot row on Now: the bot profile plus its chat's session, like `openBot`. */
    fun openBot(context: Context, profile: String, sessionId: String?, title: String?): PendingIntent =
        activity(context, "bot:$profile") {
            putExtra(ChatNotifications.EXTRA_OPEN_BOT, profile)
            sessionId?.let { putExtra(ChatNotifications.EXTRA_OPEN_SESSION, it) }
            title?.let { putExtra(ChatNotifications.EXTRA_OPEN_TITLE, it) }
        }

    /** A Kanban task row: board slug and task id; [board] may be null when the snapshot names none. */
    fun openTask(context: Context, board: String?, taskId: String): PendingIntent =
        activity(context, "task:$board/$taskId") {
            board?.let { putExtra(EXTRA_OPEN_KANBAN_BOARD, it) }
            putExtra(EXTRA_OPEN_KANBAN_TASK, taskId)
        }

    /** A Scheduled row: the job id; [profile] routes a routine to its bot's Routines page. */
    fun openJob(context: Context, jobId: String, profile: String?): PendingIntent =
        activity(context, "job:$jobId") {
            putExtra(EXTRA_OPEN_SCHEDULED_JOB, jobId)
            profile?.let { putExtra(EXTRA_OPEN_SCHEDULED_PROFILE, it) }
        }

    /** A header or fallback body tap: the plain deep link as data, no extras (D19). */
    fun openLink(context: Context, link: String, request: String): PendingIntent =
        activity(context, request) { data = link.toUri() }

    fun openNow(context: Context): PendingIntent = openLink(context, LINK_NOW, "now")
    fun openChats(context: Context): PendingIntent = openLink(context, LINK_CHATS, "chats")
    fun openKanban(context: Context): PendingIntent = openLink(context, LINK_KANBAN, "kanban")
    fun openScheduled(context: Context): PendingIntent = openLink(context, LINK_SCHEDULED, "scheduled")
    fun newChat(context: Context): PendingIntent = openLink(context, LINK_NEW_CHAT, "new-chat")
    fun dictate(context: Context): PendingIntent = openLink(context, LINK_NEW_CHAT_VOICE, "dictate")

    /** The manual-refresh broadcast to one provider (D26 trigger 2). */
    fun refresh(context: Context, receiver: Class<*>, action: String): PendingIntent =
        PendingIntent.getBroadcast(context, action.hashCode(), Intent(context, receiver).setAction(action), FLAGS)

    /**
     * The collection rows' tap template (D19): one mutable PendingIntent on the `ListView`, filled in
     * per row by each row's `setOnClickFillInIntent` — without this the rows are dead taps. Mutable,
     * where every header/body intent above stays immutable.
     */
    fun rowTemplate(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        "widget-rows".hashCode(),
        appIntent(context)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
