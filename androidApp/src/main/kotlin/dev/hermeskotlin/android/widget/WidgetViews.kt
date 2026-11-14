package dev.hermeskotlin.android.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import dev.hermeskotlin.android.R
import dev.hermeskotlin.android.notify.BotIcons
import dev.hermeskotlin.android.notify.BotNotifier
import dev.hermeskotlin.core.widget.ChatWidgetRow
import dev.hermeskotlin.core.widget.ChatsWidgetSnapshot
import dev.hermeskotlin.core.widget.KanbanStaleTier
import dev.hermeskotlin.core.widget.KanbanWidgetRow
import dev.hermeskotlin.core.widget.KanbanWidgetSnapshot
import dev.hermeskotlin.core.widget.NowNeedsYouItem
import dev.hermeskotlin.core.widget.NowNeedsYouKind
import dev.hermeskotlin.core.widget.NowRunningItem
import dev.hermeskotlin.core.widget.NowRunningKind
import dev.hermeskotlin.core.widget.NowTarget
import dev.hermeskotlin.core.widget.NowWidgetSnapshot
import dev.hermeskotlin.core.widget.ScheduledProblemKind
import dev.hermeskotlin.core.widget.ScheduledWidgetRow
import dev.hermeskotlin.core.widget.ScheduledWidgetSnapshot
import dev.hermeskotlin.ui.components.relativeTime
import dev.hermeskotlin.ui.components.timeUntil
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The only place that touches `RemoteViews` builders (D23): one render function per widget and
 * state, mapping the W-C1 snapshots to the view IDs of `res/layout/widget_*.xml`. The locked
 * variant (D16), the saved-copy strips and the "as of" line are decided here, in exactly one
 * place. Bot faces are `BotIcons` bitmaps (avatars only when already on disk); the HeraldMark
 * renders from its vector drawable, never tinted (docs/WIDGETS-DESIGN.md §4.2).
 */
object WidgetViews {

    /** What one widget instance renders: the state the refresher decided, before lock redaction. */
    sealed interface UiState<out T> {
        /** No snapshot yet and no stored one: the loading placeholders are the safe default. */
        data object Loading : UiState<Nothing>

        /** No signed-in gateway: content chrome hides, the body opens Kyllene. */
        data object SignedOut : UiState<Nothing>

        /** The refresh failed and no stored snapshot exists. */
        data object Error : UiState<Nothing>

        /**
         * A snapshot to render. [savedNotice] is the reserved strip ("Saved copy · HH:MM" after an
         * offline fallback, "Couldn't refresh · Saved HH:MM" after a failed refresh); null hides it.
         */
        data class Content<T>(val snapshot: T, val savedNotice: String? = null) : UiState<T>
    }

    private val clockTime: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    private fun hhmm(context: Context, epochSeconds: Long?): String =
        epochSeconds?.let { clockTime.withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(it)) } ?: ""

    private fun nowMillis(): Long = System.currentTimeMillis()

    private fun RemoteViews.hide(id: Int) = setViewVisibility(id, android.view.View.GONE)

    private fun RemoteViews.show(id: Int) = setViewVisibility(id, android.view.View.VISIBLE)

    /**
     * The locked branch's blanket (D16, design §12.5): `RemoteViews` are re-applied onto the host view
     * Android already inflated, so every container a previous render showed must be hidden again —
     * an unmentioned view keeps its last `VISIBLE` state and leaks titles through the lock.
     */
    private fun RemoteViews.hideContent(ids: List<Int>) = ids.forEach { hide(it) }

    private fun RemoteViews.tint(id: Int, color: Int) = setInt(id, "setColorFilter", color)

    private fun RemoteViews.describe(id: Int, description: String) =
        setCharSequence(id, "setContentDescription", description)

    private fun RemoteViews.adapter(listId: Int, appWidgetId: Int, intent: android.content.Intent) {
        if (Build.VERSION.SDK_INT >= 30) setRemoteAdapter(appWidgetId, listId, intent)
        else @Suppress("DEPRECATION") setRemoteAdapter(listId, intent)
    }

    /** The Now layout's content containers, hidden wholesale on the locked variant (D16). */
    private val nowContentIds = listOf(
        R.id.state_needs, R.id.state_running, R.id.state_quiet, R.id.state_fallback,
        R.id.notice_info, R.id.notice_warning, R.id.context, R.id.running_strip,
        R.id.needs_second, R.id.more, R.id.quiet_next, R.id.action_new_chat, R.id.action_dictate,
        R.id.fallback_new_chat, R.id.fallback_dictate,
    )

    /**
     * Now state + optional containers reset on every UNLOCKED content render. `RemoteViews` are
     * re-applied onto the host view Android already inflated, so a container a previous render
     * showed keeps `VISIBLE` unless explicitly hidden again — this is the unlocked half of the
     * D16/D23 re-apply guard (the locked blanket is `nowContentIds`).
     */
    private val nowStateResetIds = listOf(
        R.id.state_needs, R.id.state_running, R.id.state_quiet, R.id.state_fallback,
        R.id.state_loading, R.id.state_locked,
        R.id.notice_info, R.id.notice_warning, R.id.context, R.id.running_strip,
        R.id.needs_second, R.id.running_second, R.id.more, R.id.quiet_next,
    )

    /** The Chats layout's content containers, hidden wholesale on the locked variant (D16). */
    private val chatsContentIds = listOf(
        R.id.list, R.id.state_empty, R.id.saved_notice, R.id.context, R.id.action_new_chat, R.id.action_dictate,
    )

    /** The Tasks layout's content containers, hidden wholesale on the locked variant (D16). */
    private val kanbanContentIds = listOf(
        R.id.list, R.id.state_empty, R.id.count_strip, R.id.saved_notice, R.id.context,
    )

    /** The Scheduled layout's content containers, hidden wholesale on the locked variant (D16). */
    private val scheduledContentIds = listOf(
        R.id.list, R.id.state_empty, R.id.saved_notice, R.id.context,
    )

    // Now (docs/WIDGETS-DESIGN.md §6).

    fun renderNow(
        context: Context,
        state: UiState<NowWidgetSnapshot>,
        locked: Boolean,
        cold: Boolean,
        colors: WidgetColors,
        bots: BotNotifier?,
        appWidgetId: Int,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_now).apply {
        val refresh = WidgetIntents.refresh(context, NowWidgetProvider::class.java, WidgetIntents.ACTION_REFRESH_NOW)
        setOnClickPendingIntent(R.id.refresh, refresh)
        setCharSequence(
            R.id.refresh, "setContentDescription",
            context.getString(R.string.widget_refresh_now),
        )
        setOnClickPendingIntent(R.id.body, WidgetIntents.openNow(context))
        tintNowHeader(this, colors)

        when (state) {
            UiState.Loading -> Unit // the XML default state already shows the placeholders
            UiState.SignedOut -> {
                fallback(
                    context, this, colors, R.drawable.widget_ic_lock,
                    context.getString(R.string.widget_sign_in_now),
                    context.getString(R.string.widget_now_sign_in_detail),
                )
            }
            UiState.Error -> {
                fallback(
                    context, this, colors, R.drawable.widget_ic_error,
                    context.getString(R.string.widget_now_couldnt_refresh),
                    context.getString(R.string.widget_retry_detail),
                )
            }
            is UiState.Content -> {
                val snapshot = state.snapshot
                if (locked) {
                    show(R.id.state_locked)
                    hide(R.id.state_loading)
                    hideContent(nowContentIds)
                    setTextViewText(
                        R.id.state_locked,
                        context.getString(R.string.widget_now_locked_counts, snapshot.needsYou.size, snapshot.running.size),
                    )
                    return@apply
                }
                // Unlocked content: reset every state/optional container first, so a re-applied
                // render never leaks the previous state's views (```Views reapply semantics).
                hideContent(nowStateResetIds)
                setTextViewText(R.id.title, context.getString(R.string.widget_now_label))
                val asOf = snapshot.socketSavedAtEpochSeconds
                if (asOf != null) {
                    show(R.id.context)
                    setTextViewText(R.id.context, context.getString(R.string.widget_saved_copy_at, hhmm(context, asOf)))
                    setTextColor(R.id.context, colors.textTertiary)
                }

                when {
                    snapshot.needsYou.isNotEmpty() -> {
                        hide(R.id.state_loading)
                        show(R.id.state_needs)
                        renderNeedsLead(context, this, snapshot.needsYou.first(), colors, bots)
                        if (snapshot.running.isNotEmpty()) {
                            // Combined (§6.4): the running strip replaces the second Needs-you row.
                            show(R.id.running_strip)
                            val first = snapshot.running.first()
                            tint(R.id.running_strip_icon, colors.accentText)
                            setTextViewText(
                                R.id.running_strip_title,
                                if (snapshot.running.size > 1) {
                                    context.getString(R.string.widget_running_strip_more, first.title, snapshot.running.size - 1)
                                } else {
                                    context.getString(R.string.widget_running_strip, first.title)
                                },
                            )
                            setTextColor(R.id.running_strip_title, colors.textSecondary)
                            elapsed(R.id.running_strip_elapsed, first.startedAtEpochSeconds, colors)
                            setOnClickPendingIntent(R.id.running_strip, pendingForTarget(context, first.target))
                        } else if (snapshot.needsYou.size > 1) {
                            show(R.id.needs_second)
                            renderNeedsSecond(context, this, snapshot.needsYou[1], colors, bots)
                        }
                        val moreCount = when {
                            snapshot.running.isNotEmpty() -> snapshot.needsYou.size - 1
                            snapshot.needsYou.size > 1 -> snapshot.needsYou.size - 2
                            else -> 0
                        }
                        if (moreCount > 0) {
                            show(R.id.more)
                            setTextViewText(
                                R.id.more,
                                context.resources.getQuantityString(R.plurals.widget_more_need_you, moreCount, moreCount),
                            )
                            setTextColor(R.id.more, colors.textTertiary)
                        }
                    }
                    snapshot.running.isNotEmpty() -> {
                        hide(R.id.state_loading)
                        show(R.id.state_running)
                        renderRunningEntry(context, this, runningEntryIds(second = false), snapshot.running.first(), colors, bots)
                        snapshot.running.getOrNull(1)?.let {
                            show(R.id.running_second)
                            renderRunningEntry(context, this, runningEntryIds(second = true), it, colors, bots)
                        }
                    }
                    snapshot.quiet != null -> {
                        hide(R.id.state_loading)
                        show(R.id.state_quiet)
                        renderQuiet(context, this, snapshot, colors)
                    }
                    else -> {
                        // Cold, no stored socket snapshot, nothing REST-derivable: the §6.5 fallback.
                        // Quick actions remain; the next-job line shows when the REST read knew one.
                        fallback(
                            context, this, colors, R.drawable.widget_ic_info,
                            context.getString(R.string.widget_now_live_unavailable),
                            context.getString(R.string.widget_now_open_to_refresh),
                        )
                    }
                }

                // Bottom notice: cold-unknown live status (§6.5), then a failed refresh's saved strip.
                // Two pre-tinted TextViews: a compound drawable's tint is static, the icon differs.
                if (cold && snapshot.socketSavedAtEpochSeconds == null &&
                    (snapshot.needsYou.isNotEmpty() || snapshot.running.isNotEmpty())
                ) {
                    show(R.id.notice_info)
                    setTextViewText(R.id.notice_info_text, context.getString(R.string.widget_now_live_unavailable_notice))
                    tint(R.id.notice_info_icon, colors.warning)
                } else if (state.savedNotice != null) {
                    show(R.id.notice_warning)
                    setTextViewText(R.id.notice_warning_text, state.savedNotice)
                    tint(R.id.notice_warning_icon, colors.warning)
                }
            }
        }
    }

    private fun tintNowHeader(views: RemoteViews, colors: WidgetColors) {
        views.setTextColor(R.id.title, colors.text)
        views.tint(R.id.refresh_icon, colors.accentText)
    }

    private fun fallback(
        context: Context,
        views: RemoteViews,
        colors: WidgetColors,
        icon: Int,
        title: String,
        detail: String,
    ) {
        views.hide(R.id.state_loading)
        views.show(R.id.state_fallback)
        views.setImageViewResource(R.id.fallback_icon, icon)
        views.tint(R.id.fallback_icon, colors.textTertiary)
        views.setTextViewText(R.id.fallback_title, title)
        views.setTextColor(R.id.fallback_title, colors.text)
        views.setTextViewText(R.id.fallback_detail, detail)
        views.setTextColor(R.id.fallback_detail, colors.textSecondary)
        views.setOnClickPendingIntent(R.id.fallback_new_chat, WidgetIntents.newChat(context))
        views.setOnClickPendingIntent(R.id.fallback_dictate, WidgetIntents.dictate(context))
        tintFallbackActions(views, colors)
    }

    private fun tintFallbackActions(views: RemoteViews, colors: WidgetColors) {
        views.setInt(R.id.fallback_new_chat, "setBackgroundResource", colors.accentActionBackground())
        views.setInt(R.id.fallback_dictate, "setBackgroundResource", R.drawable.widget_action_secondary)
        views.tint(R.id.fallback_new_chat_icon, colors.onAccent)
        views.tint(R.id.fallback_dictate_icon, colors.text)
    }

    private fun RemoteViews.elapsed(id: Int, startedAtEpochSeconds: Double?, colors: WidgetColors) {
        val label = relativeTime(startedAtEpochSeconds, nowMillis())
        if (label.isBlank()) {
            hide(id)
        } else {
            show(id)
            setTextViewText(id, label)
            setTextColor(id, colors.textTertiary)
        }
    }

    /** The lead Needs-you aperture (§6.1): face for bots, source glyph otherwise, optional badge. */
    private fun renderNeedsLead(
        context: Context,
        views: RemoteViews,
        item: NowNeedsYouItem,
        colors: WidgetColors,
        bots: BotNotifier?,
    ) {
        val urgent = item.kind == NowNeedsYouKind.BotTrouble ||
            item.kind == NowNeedsYouKind.RoutineFailed || item.kind == NowNeedsYouKind.CronProblem
        views.setTextColor(R.id.needs_eyebrow, if (urgent) colors.danger else colors.accentText)
        views.setTextViewText(R.id.lead_title, item.title)
        views.setTextColor(R.id.lead_title, colors.text)
        views.setTextViewText(R.id.lead_detail, item.detail ?: "")
        views.setTextColor(R.id.lead_detail, colors.textSecondary)
        renderNeedsIcon(context, views, R.id.lead_icon, item.kind, item.botProfile, colors, bots, lead = true)

        when {
            item.kind == NowNeedsYouKind.BotTrouble -> {
                views.show(R.id.lead_badge)
                views.setTextViewText(R.id.lead_badge, "⚠")
                views.setTextColor(R.id.lead_badge, colors.danger)
            }
            item.kind == NowNeedsYouKind.KanbanAttention -> {
                views.show(R.id.lead_badge)
                views.setTextViewText(R.id.lead_badge, item.detail ?: "")
                views.setTextColor(R.id.lead_badge, colors.warning)
            }
            else -> views.hide(R.id.lead_badge)
        }
        views.setOnClickPendingIntent(R.id.needs_lead, pendingForTarget(context, item.target))
        views.describe(
            R.id.needs_lead,
            listOfNotNull(kindLabel(context, item.kind), item.title, item.detail).joinToString(". ") + ".",
        )
    }

    private fun renderNeedsSecond(
        context: Context,
        views: RemoteViews,
        item: NowNeedsYouItem,
        colors: WidgetColors,
        bots: BotNotifier?,
    ) {
        views.setTextViewText(R.id.second_title, item.title)
        views.setTextColor(R.id.second_title, colors.text)
        views.setTextViewText(R.id.second_detail, item.detail ?: "")
        views.setTextColor(R.id.second_detail, colors.textSecondary)
        renderNeedsIcon(context, views, R.id.second_icon, item.kind, item.botProfile, colors, bots, lead = false)
        views.setOnClickPendingIntent(R.id.needs_second, pendingForTarget(context, item.target))
    }

    private fun renderNeedsIcon(
        context: Context,
        views: RemoteViews,
        iconId: Int,
        kind: NowNeedsYouKind,
        botProfile: String?,
        colors: WidgetColors,
        bots: BotNotifier?,
        lead: Boolean,
    ) {
        val bot = botProfile?.let { bots?.knownBot(it) }
        if (bot != null && kind != NowNeedsYouKind.ChatWaiting && kind != NowNeedsYouKind.KanbanAttention) {
            val size = if (lead) 160 else 128
            val face = bots?.picture(bot)?.takeIf { it.isNotEmpty() }
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                ?: BotIcons.face(bot, size)
            views.setImageViewBitmap(iconId, face)
            return
        }
        when (kind) {
            NowNeedsYouKind.ChatWaiting -> {
                views.setImageViewResource(iconId, R.drawable.widget_ic_chat)
                views.tint(iconId, colors.accentText)
            }
            NowNeedsYouKind.KanbanAttention -> {
                views.setImageViewResource(iconId, R.drawable.widget_ic_kanban)
                views.tint(iconId, colors.warning)
            }
            NowNeedsYouKind.CronProblem -> {
                views.setImageViewResource(iconId, R.drawable.widget_ic_clock)
                views.tint(iconId, colors.danger)
            }
            else -> {
                // A bot the roster doesn't know (cold): the source glyph stands in, never a generated avatar.
                views.setImageViewResource(iconId, R.drawable.widget_ic_activity)
                views.tint(iconId, colors.accentText)
            }
        }
    }

    /** IDs of one running-aperture row (`running_first_*` / `running_second_*` in widget_now.xml). */
    private data class RunningEntryIds(val root: Int, val icon: Int, val title: Int, val meta: Int, val elapsed: Int)

    private fun runningEntryIds(second: Boolean): RunningEntryIds =
        if (second) RunningEntryIds(
            R.id.running_second, R.id.running_second_icon,
            R.id.running_second_title, R.id.running_second_meta, R.id.running_second_elapsed,
        )
        else RunningEntryIds(
            R.id.running_first, R.id.running_first_icon,
            R.id.running_first_title, R.id.running_first_meta, R.id.running_first_elapsed,
        )

    private fun renderRunningEntry(
        context: Context,
        views: RemoteViews,
        ids: RunningEntryIds,
        item: NowRunningItem,
        colors: WidgetColors,
        bots: BotNotifier?,
    ) {
        views.setTextViewText(ids.title, item.title)
        views.setTextColor(ids.title, colors.text)
        views.setTextViewText(ids.meta, runningKindLabel(context, item))
        views.setTextColor(ids.meta, colors.textSecondary)
        views.elapsed(ids.elapsed, item.startedAtEpochSeconds, colors)
        val bot = item.botProfile?.let { bots?.knownBot(it) }
        if (bot != null) {
            val face = bots?.picture(bot)?.takeIf { it.isNotEmpty() }
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                ?: BotIcons.face(bot, 128)
            views.setImageViewBitmap(ids.icon, face)
        } else {
            views.setImageViewResource(ids.icon, R.drawable.widget_ic_activity)
            views.tint(ids.icon, colors.accentText)
        }
        views.setOnClickPendingIntent(ids.root, pendingForTarget(context, item.target))
        views.describe(ids.root, runningKindLabel(context, item) + ". " + item.title + ".")
    }

    private fun runningKindLabel(context: Context, item: NowRunningItem): String = when (item.kind) {
        NowRunningKind.ChatTurn -> context.getString(R.string.widget_now_kind_chat_turn)
        NowRunningKind.BotWorking -> context.getString(R.string.widget_now_kind_bot_working)
        NowRunningKind.KanbanRunning -> context.getString(R.string.widget_now_kind_kanban_running)
    }

    /** Accessibility labels per Needs-you kind (D18): what the item is, not what its source happens to be doing. */
    private fun kindLabel(context: Context, kind: NowNeedsYouKind): String = when (kind) {
        NowNeedsYouKind.ChatWaiting -> context.getString(R.string.widget_now_kind_waiting)
        NowNeedsYouKind.BotAnswer -> context.getString(R.string.widget_now_kind_waiting)
        NowNeedsYouKind.BotTrouble -> context.getString(R.string.widget_now_kind_bot_trouble)
        NowNeedsYouKind.RoutineFailed -> context.getString(R.string.widget_now_kind_routine_failed)
        NowNeedsYouKind.KanbanAttention -> context.getString(R.string.widget_now_kind_task_status)
        NowNeedsYouKind.CronProblem -> context.getString(R.string.widget_now_kind_scheduled_problem)
    }

    private fun renderQuiet(
        context: Context,
        views: RemoteViews,
        snapshot: NowWidgetSnapshot,
        colors: WidgetColors,
    ) {
        // The mark is drawn to a bitmap from its untinted vector (§4.2).
        val mark = bitmapOf(context, R.drawable.widget_herald_mark, 192)
        views.setImageViewBitmap(R.id.quiet_mark, mark)
        views.setTextColor(R.id.quiet_title, colors.text)
        views.setTextViewText(R.id.quiet_detail, context.getString(R.string.widget_everything_quiet))
        views.setTextColor(R.id.quiet_detail, colors.textTertiary)
        val quiet = snapshot.quiet
        val nextName = quiet?.nextJobName
        if (quiet != null && nextName != null) {
            views.show(R.id.quiet_next)
            views.setTextViewText(
                R.id.quiet_next,
                context.getString(R.string.widget_next_job, nextName, hhmm(context, quiet.nextRunEpochSeconds)),
            )
            views.setTextColor(R.id.quiet_next, colors.textTertiary)
        } else {
            views.hide(R.id.quiet_next)
        }
        views.setOnClickPendingIntent(R.id.action_new_chat, WidgetIntents.newChat(context))
        views.setOnClickPendingIntent(R.id.action_dictate, WidgetIntents.dictate(context))
        views.setInt(R.id.action_new_chat, "setBackgroundResource", colors.accentActionBackground())
        views.setInt(R.id.action_dictate, "setBackgroundResource", R.drawable.widget_action_secondary)
        views.tint(R.id.action_new_chat_icon, colors.onAccent)
        views.tint(R.id.action_dictate_icon, colors.text)
        views.describe(R.id.quiet_mark, context.getString(R.string.widget_all_quiet))
    }

    private fun bitmapOf(context: Context, drawableRes: Int, sizePx: Int): Bitmap {
        val drawable = ContextCompat.getDrawable(context, drawableRes)
            ?: return createBitmap(1, 1)
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bitmap
    }

    private fun pendingForTarget(context: Context, target: NowTarget): android.app.PendingIntent {
        val session = target.storedSessionId
        val bot = target.botProfile
        val task = target.kanbanTaskId
        val board = target.kanbanBoard
        val job = target.scheduledJobId
        return when {
            session != null -> WidgetIntents.openChat(context, session, target.title)
            bot != null -> WidgetIntents.openBot(context, bot, session, target.title)
            task != null -> WidgetIntents.openTask(context, board, task)
            board != null -> WidgetIntents.openKanban(context)
            job != null -> WidgetIntents.openJob(context, job, bot)
            else -> WidgetIntents.openNow(context)
        }
    }

    // Chats (docs/WIDGETS-DESIGN.md §7).

    fun renderChats(
        context: Context,
        state: UiState<ChatsWidgetSnapshot>,
        locked: Boolean,
        colors: WidgetColors,
        gatewayLabel: String?,
        profile: String?,
        appWidgetId: Int,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_chats).apply {
        setTextColor(R.id.title, colors.text)
        setTextColor(R.id.context, colors.textTertiary)
        tint(R.id.refresh_icon, colors.accentText)
        tint(R.id.action_new_chat_icon, colors.accentText)
        tint(R.id.action_dictate_icon, colors.accentText)
        setOnClickPendingIntent(R.id.header, WidgetIntents.openChats(context))
        setOnClickPendingIntent(R.id.action_new_chat, WidgetIntents.newChat(context))
        setOnClickPendingIntent(R.id.action_dictate, WidgetIntents.dictate(context))
        setOnClickPendingIntent(R.id.refresh, WidgetIntents.refresh(context, ChatsWidgetProvider::class.java, WidgetIntents.ACTION_REFRESH_CHATS))
        setCharSequence(R.id.refresh, "setContentDescription", context.getString(R.string.widget_refresh_chats))

        when (state) {
            UiState.Loading -> Unit
            UiState.SignedOut -> {
                hide(R.id.action_new_chat)
                hide(R.id.action_dictate)
                emptyState(
                    context, this, colors, R.drawable.widget_ic_lock,
                    context.getString(R.string.widget_sign_in_chats),
                    context.getString(R.string.widget_chats_sign_in_detail),
                )
            }
            UiState.Error -> emptyState(
                context, this, colors, R.drawable.widget_ic_cloud_off,
                context.getString(R.string.widget_chats_couldnt_refresh),
                context.getString(R.string.widget_chats_no_cache_detail),
            )
            is UiState.Content -> {
                if (locked) {
                    show(R.id.state_locked)
                    hide(R.id.state_loading)
                    hideContent(chatsContentIds)
                    setTextViewText(
                        R.id.state_locked,
                        context.getString(R.string.widget_chats_locked_counts, state.snapshot.rows.size),
                    )
                    return@apply
                }
                setTextViewText(R.id.title, context.getString(R.string.widget_chats_label))
                val contextLine = listOfNotNull(profile, gatewayLabel).joinToString(" · ")
                if (contextLine.isNotBlank()) {
                    show(R.id.context)
                    setTextViewText(R.id.context, contextLine)
                }
                state.savedNotice?.let { savedNotice(it, colors) }
                val snapshot = state.snapshot
                if (snapshot.rows.isEmpty()) {
                    emptyState(
                        context, this, colors, R.drawable.widget_ic_chat,
                        context.getString(R.string.widget_chats_empty),
                        context.getString(R.string.widget_chats_empty_detail),
                    )
                } else {
                    hide(R.id.state_loading)
                    show(R.id.list)
                    setPendingIntentTemplate(R.id.list, WidgetIntents.rowTemplate(context))
                    adapter(R.id.list, appWidgetId, serviceIntent(context, WidgetIntents.KIND_CHATS, snapshot.gatewayUrl, snapshot.profile, null))
                }
            }
        }
    }

    private fun emptyState(
        context: Context,
        views: RemoteViews,
        colors: WidgetColors,
        icon: Int,
        title: String,
        detail: String,
    ) {
        views.hide(R.id.state_loading)
        views.hide(R.id.list)
        views.show(R.id.state_empty)
        views.setImageViewResource(R.id.empty_icon, icon)
        views.tint(R.id.empty_icon, colors.textTertiary)
        views.setTextViewText(R.id.empty_title, title)
        views.setTextColor(R.id.empty_title, colors.text)
        views.setTextViewText(R.id.empty_detail, detail)
        views.setTextColor(R.id.empty_detail, colors.textSecondary)
    }

    private fun RemoteViews.savedNotice(text: String, colors: WidgetColors) {
        show(R.id.saved_notice)
        setTextViewText(R.id.saved_notice_text, text)
        setTextColor(R.id.saved_notice_text, colors.warning)
        tint(R.id.saved_notice_icon, colors.warning)
    }

    internal fun serviceIntent(
        context: Context,
        kind: String,
        gateway: String,
        profile: String?,
        board: String?,
    ): android.content.Intent = android.content.Intent(context, WidgetViewsService::class.java)
        .putExtra(WidgetIntents.EXTRA_WIDGET_KIND, kind)
        .putExtra(WidgetIntents.EXTRA_WIDGET_GATEWAY, gateway)
        .apply {
            profile?.let { putExtra(WidgetIntents.EXTRA_WIDGET_PROFILE, it) }
            board?.let { putExtra(WidgetIntents.EXTRA_WIDGET_BOARD, it) }
            // The framework matches cached RemoteViewsAdapters with Intent.filterEquals(), which ignores
            // extras. Without a distinct data URI every collection widget reuses the first-bound adapter
            // and shows the same rows (Scheduled showed Chats). Encode the scope into the data to separate them.
            data = (
                "kyllene-widget://" + kind + "/" + android.net.Uri.encode(gateway) +
                    "/" + android.net.Uri.encode(profile.orEmpty()) + "/" + android.net.Uri.encode(board.orEmpty())
                ).toUri()
        }

    // Tasks / Kanban (docs/WIDGETS-DESIGN.md §8).

    fun renderKanban(
        context: Context,
        state: UiState<KanbanWidgetSnapshot>,
        locked: Boolean,
        colors: WidgetColors,
        appWidgetId: Int,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_kanban).apply {
        tint(R.id.refresh_icon, colors.accentText)
        setOnClickPendingIntent(R.id.refresh, WidgetIntents.refresh(context, KanbanWidgetProvider::class.java, WidgetIntents.ACTION_REFRESH_KANBAN))
        setCharSequence(R.id.refresh, "setContentDescription", context.getString(R.string.widget_refresh_kanban))
        setOnClickPendingIntent(R.id.header, WidgetIntents.openKanban(context))

        when (state) {
            UiState.Loading -> Unit
            UiState.SignedOut -> {
                hide(R.id.count_strip)
                emptyState(
                    context, this, colors, R.drawable.widget_ic_lock,
                    context.getString(R.string.widget_sign_in_kanban),
                    context.getString(R.string.widget_kanban_sign_in_detail),
                )
            }
            UiState.Error -> emptyState(
                context, this, colors, R.drawable.widget_ic_error,
                context.getString(R.string.widget_kanban_couldnt_refresh),
                context.getString(R.string.widget_retry_detail),
            )
            is UiState.Content -> {
                if (locked) {
                    show(R.id.state_locked)
                    hide(R.id.state_loading)
                    hideContent(kanbanContentIds)
                    setTextViewText(
                        R.id.state_locked,
                        context.getString(R.string.widget_kanban_locked_counts, state.snapshot.rows.size),
                    )
                    return@apply
                }
                val snapshot = state.snapshot
                setTextViewText(R.id.title, snapshot.boardName)
                setTextColor(R.id.title, colors.text)
                show(R.id.context)
                setTextViewText(R.id.context, context.getString(R.string.widget_kanban_label))
                setTextColor(R.id.context, colors.textTertiary)

                hide(R.id.state_loading)
                show(R.id.count_strip)
                counts(context, this, snapshot, colors)
                state.savedNotice?.let { savedNotice(it, colors) }

                if (snapshot.rows.isEmpty()) {
                    // An empty board keeps its 0-count strip (design §8.3); only "no board at all" drops it.
                    val noBoard = snapshot.boardSlug.isEmpty()
                    if (noBoard) hide(R.id.count_strip)
                    emptyState(
                        context, this, colors, R.drawable.widget_ic_kanban,
                        context.getString(if (noBoard) R.string.widget_kanban_no_board else R.string.widget_kanban_empty),
                        context.getString(
                            if (noBoard) R.string.widget_kanban_no_board_detail else R.string.widget_kanban_empty_detail,
                        ),
                    )
                } else {
                    show(R.id.list)
                    setPendingIntentTemplate(R.id.list, WidgetIntents.rowTemplate(context))
                    adapter(R.id.list, appWidgetId, serviceIntent(context, WidgetIntents.KIND_KANBAN, snapshot.gatewayUrl, null, snapshot.boardSlug))
                }
            }
        }
    }

    private fun counts(context: Context, views: RemoteViews, snapshot: KanbanWidgetSnapshot, colors: WidgetColors) {
        fun set(id: Int, nId: Int, key: String, color: Int, background: Int) {
            views.setTextViewText(nId, (snapshot.counts[key] ?: 0).toString())
            views.setTextColor(nId, color)
            views.setInt(id, "setBackgroundResource", background)
        }
        set(R.id.count_running, R.id.count_running_n, "running", colors.accentText, colors.accentPillBackground())
        set(R.id.count_blocked, R.id.count_blocked_n, "blocked", colors.danger, R.drawable.widget_pill_danger)
        set(R.id.count_review, R.id.count_review_n, "review", colors.warning, R.drawable.widget_pill_warning)
        set(R.id.count_ready, R.id.count_ready_n, "ready", colors.success, R.drawable.widget_pill_success)
    }

    // Scheduled (docs/WIDGETS-DESIGN.md §9).

    fun renderScheduled(
        context: Context,
        state: UiState<ScheduledWidgetSnapshot>,
        locked: Boolean,
        colors: WidgetColors,
        appWidgetId: Int,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_scheduled).apply {
        tint(R.id.refresh_icon, colors.accentText)
        setOnClickPendingIntent(R.id.refresh, WidgetIntents.refresh(context, ScheduledWidgetProvider::class.java, WidgetIntents.ACTION_REFRESH_SCHEDULED))
        setCharSequence(R.id.refresh, "setContentDescription", context.getString(R.string.widget_refresh_scheduled))
        setOnClickPendingIntent(R.id.header, WidgetIntents.openScheduled(context))

        when (state) {
            UiState.Loading -> Unit
            UiState.SignedOut -> emptyState(
                context, this, colors, R.drawable.widget_ic_lock,
                context.getString(R.string.widget_sign_in_scheduled),
                context.getString(R.string.widget_scheduled_sign_in_detail),
            )
            UiState.Error -> emptyState(
                context, this, colors, R.drawable.widget_ic_error,
                context.getString(R.string.widget_scheduled_couldnt_refresh),
                context.getString(R.string.widget_retry_detail),
            )
            is UiState.Content -> {
                if (locked) {
                    show(R.id.state_locked)
                    hide(R.id.state_loading)
                    hideContent(scheduledContentIds)
                    setTextViewText(
                        R.id.state_locked,
                        context.getString(R.string.widget_scheduled_locked_counts, state.snapshot.rows.size),
                    )
                    return@apply
                }
                setTextColor(R.id.title, colors.text)
                show(R.id.context)
                setTextColor(R.id.context, colors.textTertiary)
                val snapshot = state.snapshot
                val problems = snapshot.rows.count { it.isProblem }
                val active = snapshot.rows.count { !it.isProblem && it.state != "paused" }
                setTextViewText(
                    R.id.context,
                    when {
                        state.savedNotice != null -> context.getString(R.string.widget_scheduled_saved_copy)
                        problems > 0 -> context.resources.getQuantityString(
                            R.plurals.widget_scheduled_summary_problems, problems, problems, active,
                        )
                        active > 0 -> context.resources.getQuantityString(
                            R.plurals.widget_scheduled_summary_active, active, active,
                        )
                        else -> context.getString(R.string.widget_scheduled_no_active)
                    },
                )
                state.savedNotice?.let { savedNotice(it, colors) }

                if (snapshot.rows.isEmpty()) {
                    emptyState(
                        context, this, colors, R.drawable.widget_ic_clock,
                        context.getString(R.string.widget_scheduled_empty),
                        context.getString(R.string.widget_scheduled_empty_detail),
                    )
                } else {
                    hide(R.id.state_loading)
                    show(R.id.list)
                    setPendingIntentTemplate(R.id.list, WidgetIntents.rowTemplate(context))
                    adapter(R.id.list, appWidgetId, serviceIntent(context, WidgetIntents.KIND_SCHEDULED, snapshot.gatewayUrl, null, null))
                }
            }
        }
    }

    // Collection rows (docs/WIDGETS-DESIGN.md §7.2, §8.2, §9.2-9.3).

    fun chatsRow(context: Context, row: ChatWidgetRow, colors: WidgetColors): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_chats_row).apply {
            setTextViewText(R.id.row_title, row.title)
            setTextColor(R.id.row_title, colors.text)
            setTextViewText(R.id.row_subtitle, row.subtitle ?: "")
            setTextColor(R.id.row_subtitle, colors.textTertiary)

            setViewVisibility(R.id.marker_pin, if (row.pinned) android.view.View.VISIBLE else android.view.View.GONE)
            tint(R.id.marker_pin, colors.textTertiary)
            setViewVisibility(R.id.marker_thread, if (row.thread) android.view.View.VISIBLE else android.view.View.GONE)
            tint(R.id.marker_thread, colors.textTertiary)

            // Status precedence: waiting label → Running → unread dot → none; age is always last.
            val status = row.waitingLabel ?: if (row.running) context.getString(R.string.widget_running) else null
            if (status != null) {
                show(R.id.row_status)
                setTextViewText(R.id.row_status, status)
                setTextColor(R.id.row_status, colors.accentText)
            } else {
                hide(R.id.row_status)
            }
            if (row.unread && status == null) {
                show(R.id.row_unread)
                tint(R.id.row_unread, colors.accentText)
            } else {
                hide(R.id.row_unread)
            }
            val age = relativeTime(row.activityEpochSeconds, nowMillis())
            if (age.isBlank()) hide(R.id.row_age) else {
                show(R.id.row_age)
                setTextViewText(R.id.row_age, age)
                setTextColor(R.id.row_age, colors.textTertiary)
            }

            setOnClickFillInIntent(
                R.id.row_root,
                android.content.Intent().apply {
                    putExtra(dev.hermeskotlin.android.notify.ChatNotifications.EXTRA_OPEN_SESSION, row.storedSessionId)
                    row.title.let { putExtra(dev.hermeskotlin.android.notify.ChatNotifications.EXTRA_OPEN_TITLE, it) }
                },
            )
            describe(
                R.id.row_root,
                listOfNotNull(status, row.title, if (age.isBlank()) null else age,
                    if (row.pinned) context.getString(R.string.widget_pinned) else null,
                    context.getString(R.string.widget_open_chat)).joinToString(". "),
            )
        }

    fun kanbanRow(context: Context, row: KanbanWidgetRow, colors: WidgetColors, boardSlug: String? = null): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_kanban_row).apply {
            val statusColor = when (row.status.lowercase()) {
                "running".lowercase() -> colors.accentText
                "blocked" -> colors.danger
                "review" -> colors.warning
                "ready" -> colors.success
                else -> colors.textTertiary
            }
            tint(R.id.status_dot, statusColor)
            setTextViewText(R.id.row_title, row.title)
            setTextColor(R.id.row_title, when (row.stale) {
                KanbanStaleTier.Red -> colors.danger
                KanbanStaleTier.Amber -> colors.warning
                KanbanStaleTier.None -> colors.text
            })

            // Metadata: @assignee (or Unassigned) → priority → stale → warning count.
            val assignee = row.assignee ?: context.getString(R.string.widget_unassigned)
            val metadata = buildList {
                add("@$assignee")
                if (row.priority > 0) add("P${row.priority}")
                when (row.stale) {
                    KanbanStaleTier.Amber -> add(context.getString(R.string.widget_stale))
                    KanbanStaleTier.Red -> add(context.getString(R.string.widget_stale_red))
                    KanbanStaleTier.None -> Unit
                }
                if (row.warningCount > 0) add(context.getString(R.string.widget_warning_badge, row.warningCount))
            }.joinToString("  ·  ")
            setTextViewText(R.id.row_metadata, metadata)
            setTextColor(R.id.row_metadata, colors.textSecondary)

            // Trailing pill: Red stale → warnings → Amber stale → priority → status word.
            val pillText: String? = when {
                row.stale == KanbanStaleTier.Red -> context.getString(R.string.widget_stale_red)
                row.warningCount > 0 -> context.getString(R.string.widget_warning_badge, row.warningCount)
                row.stale == KanbanStaleTier.Amber -> context.getString(R.string.widget_stale)
                row.priority > 0 -> "P${row.priority}"
                else -> row.status.replaceFirstChar { it.titlecase() }
            }
            if (pillText == null) {
                hide(R.id.row_badge)
            } else {
                show(R.id.row_badge)
                setTextViewText(R.id.row_badge, pillText)
                val pillColor = when {
                    row.stale == KanbanStaleTier.Red || row.status.equals("blocked", true) -> colors.danger
                    row.warningCount > 0 || row.stale == KanbanStaleTier.Amber || row.status.equals("review", true) -> colors.warning
                    row.status.equals("ready", true) -> colors.success
                    else -> colors.textSecondary
                }
                setTextColor(R.id.row_badge, pillColor)
                setInt(
                    R.id.row_badge, "setBackgroundResource",
                    when {
                        pillColor == colors.danger -> R.drawable.widget_pill_danger
                        pillColor == colors.warning -> R.drawable.widget_pill_warning
                        pillColor == colors.success -> R.drawable.widget_pill_success
                        else -> R.drawable.widget_pill_neutral
                    },
                )
            }

            setOnClickFillInIntent(
                R.id.row_root,
                android.content.Intent().apply {
                    boardSlug?.takeIf { it.isNotEmpty() }?.let { putExtra(WidgetIntents.EXTRA_OPEN_KANBAN_BOARD, it) }
                    putExtra(WidgetIntents.EXTRA_OPEN_KANBAN_TASK, row.taskId)
                },
            )
            describe(
                R.id.row_root,
                listOfNotNull(row.status.replaceFirstChar { it.titlecase() }, row.title, "@$assignee",
                    if (row.warningCount > 0) context.getString(R.string.widget_warning_badge, row.warningCount) else null,
                    context.getString(R.string.widget_open_task)).joinToString(". "),
            )
        }

    fun scheduledRow(context: Context, row: ScheduledWidgetRow, colors: WidgetColors): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_scheduled_row).apply {
            val problemColor = if (row.problemKind == ScheduledProblemKind.Missed) colors.warning else colors.danger
            if (row.isProblem) {
                // Problem rows are 64dp: the base row is 52dp, padded to the taller rhythm (§9.2).
                setViewPadding(R.id.row_root, 0, 6, 0, 6)
                show(R.id.problem_icon)
                setImageViewResource(R.id.problem_icon, R.drawable.widget_ic_warning)
                tint(R.id.problem_icon, problemColor)
                hide(R.id.time_column)
                setTextViewText(R.id.row_subtitle, row.problemSummary ?: "")
                setTextColor(R.id.row_subtitle, colors.textSecondary)
                setInt(R.id.row_subtitle, "setMaxLines", 2)

                val pill = when (row.problemKind ?: ScheduledProblemKind.Failed) {
                    ScheduledProblemKind.Failed -> context.getString(R.string.widget_failed)
                    ScheduledProblemKind.DeliveryFailed -> context.getString(R.string.widget_delivery_failed)
                    ScheduledProblemKind.Missed -> context.getString(R.string.widget_missed)
                }
                show(R.id.row_badge)
                setTextViewText(R.id.row_badge, pill)
                setTextColor(R.id.row_badge, problemColor)
                setInt(
                    R.id.row_badge, "setBackgroundResource",
                    if (row.problemKind == ScheduledProblemKind.Missed) R.drawable.widget_pill_warning
                    else R.drawable.widget_pill_danger,
                )
            } else {
                hide(R.id.problem_icon)
                show(R.id.time_column)
                setInt(R.id.row_subtitle, "setMaxLines", 1)
                if (row.state == "paused") {
                    setTextViewText(R.id.time_primary, context.getString(R.string.widget_paused))
                    setTextColor(R.id.time_primary, colors.textTertiary)
                    hide(R.id.time_secondary)
                } else {
                    val next = row.nextRunEpochSeconds
                    setTextViewText(R.id.time_primary, hhmm(context, next))
                    setTextColor(R.id.time_primary, colors.text)
                    val until = timeUntil(next?.toDouble(), nowMillis())
                    if (until.isBlank()) hide(R.id.time_secondary) else {
                        show(R.id.time_secondary)
                        setTextViewText(R.id.time_secondary, until)
                        setTextColor(R.id.time_secondary, colors.textTertiary)
                    }
                }
                val schedule = row.scheduleDisplay
                val owner = row.routineOwnerLabel?.let { "@$it" } ?: row.profile
                setTextViewText(
                    R.id.row_subtitle,
                    listOfNotNull(schedule, owner).joinToString("  ·  "),
                )
                setTextColor(R.id.row_subtitle, colors.textSecondary)
                if (row.state == "paused") setTextColor(R.id.row_title, colors.textTertiary)
                hide(R.id.row_badge)
            }
            setTextViewText(R.id.row_title, row.displayName)
            if (row.state != "paused") setTextColor(R.id.row_title, colors.text)

            setOnClickFillInIntent(
                R.id.row_root,
                android.content.Intent().apply {
                    putExtra(WidgetIntents.EXTRA_OPEN_SCHEDULED_JOB, row.jobId)
                    row.profile?.let { putExtra(WidgetIntents.EXTRA_OPEN_SCHEDULED_PROFILE, it) }
                },
            )
            describe(
                R.id.row_root,
                listOfNotNull(
                    if (row.isProblem) row.problemSummary else null,
                    row.displayName,
                    row.routineOwnerLabel ?: row.profile,
                    context.getString(R.string.widget_open_job),
                ).joinToString(". "),
            )
        }
}
