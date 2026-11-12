package dev.hermeskotlin.android.widget

import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import dev.hermeskotlin.android.R
import dev.hermeskotlin.core.widget.ChatWidgetRow
import dev.hermeskotlin.core.widget.ChatsWidgetSnapshot
import dev.hermeskotlin.core.widget.KanbanStaleTier
import dev.hermeskotlin.core.widget.KanbanWidgetRow
import dev.hermeskotlin.core.widget.KanbanWidgetSnapshot
import dev.hermeskotlin.core.widget.NowNeedsYouItem
import dev.hermeskotlin.core.widget.NowNeedsYouKind
import dev.hermeskotlin.core.widget.NowQuietState
import dev.hermeskotlin.core.widget.NowRunningItem
import dev.hermeskotlin.core.widget.NowRunningKind
import dev.hermeskotlin.core.widget.NowTarget
import dev.hermeskotlin.core.widget.NowWidgetSnapshot
import dev.hermeskotlin.core.widget.ScheduledProblemKind
import dev.hermeskotlin.core.widget.ScheduledWidgetRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The RemoteViews render contract (docs/WIDGETS-SPEC.md D18-D27, docs/WIDGETS-DESIGN.md §6-§9):
 * each state picks its aperture, the App-locked variant shows counts only, rows carry their
 * badges, and every render inflates against a real parent without a cross-widget exception.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = WidgetTestApp::class)
class WidgetViewsRenderTest {

    private val context = RuntimeEnvironment.getApplication()
    private val colors get() = WidgetColors(context, "Blue")

    @Test
    fun collectionAdapterIntentsAreDistinctPerScope() {
        // The framework matches cached RemoteViewsAdapters with Intent.filterEquals(), which ignores
        // extras. Each widget scope must carry a distinct data URI, or Scheduled/Kanban reuse the
        // first-bound (Chats) adapter and show chats instead of their own rows.
        val chats = WidgetViews.serviceIntent(context, WidgetIntents.KIND_CHATS, "https://gw", "default", null)
        val scheduled = WidgetViews.serviceIntent(context, WidgetIntents.KIND_SCHEDULED, "https://gw", null, null)
        val kanban = WidgetViews.serviceIntent(context, WidgetIntents.KIND_KANBAN, "https://gw", null, "board")
        assertTrue("chats vs scheduled adapters must not collide", !chats.filterEquals(scheduled))
        assertTrue("chats vs kanban adapters must not collide", !chats.filterEquals(kanban))
        assertTrue("scheduled vs kanban adapters must not collide", !scheduled.filterEquals(kanban))
        // The same scope still matches, so a widget reuses its own adapter across refreshes.
        val chatsAgain = WidgetViews.serviceIntent(context, WidgetIntents.KIND_CHATS, "https://gw", "default", null)
        assertTrue(chats.filterEquals(chatsAgain))
    }

    private fun RemoteViews.applied(): View = apply(context, FrameLayout(context))

    private fun quietNow() = NowWidgetSnapshot(
        gatewayUrl = "https://gw",
        profile = null,
        needsYou = emptyList(),
        running = emptyList(),
        quiet = NowQuietState(nextRunEpochSeconds = 1_800_000_000L, nextJobName = "Nightly review"),
        socketSavedAtEpochSeconds = null,
        savedAtEpochSeconds = 1_700_000_000L,
    )

    @Test
    fun quietStateShowsMarkNextJobAndQuickActions() {
        val view = WidgetViews.renderNow(
            context, WidgetViews.UiState.Content(quietNow()), locked = false, cold = false, colors, bots = null, 0,
        ).applied()
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.state_quiet).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.state_loading).visibility)
        assertEquals("Everything is quiet", view.findViewById<android.widget.TextView>(R.id.quiet_detail).text.toString())
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.quiet_next).visibility)
        assertTrue(view.findViewById<android.widget.TextView>(R.id.quiet_next).text.toString().startsWith("Next: Nightly review · "))
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.action_new_chat).visibility)
    }

    @Test
    fun needsYouWithRunningShowsCombinedStrip() {
        val snapshot = quietNow().copy(
            needsYou = listOf(
                NowNeedsYouItem(
                    kind = NowNeedsYouKind.ChatWaiting,
                    title = "Design review",
                    detail = "Needs approval",
                    target = NowTarget(storedSessionId = "s-1"),
                ),
            ),
            running = listOf(
                NowRunningItem(
                    kind = NowRunningKind.BotWorking,
                    title = "Helper",
                    target = NowTarget(botProfile = "helper"),
                ),
            ),
            quiet = null,
        )
        val view = WidgetViews.renderNow(
            context, WidgetViews.UiState.Content(snapshot), locked = false, cold = false, colors, bots = null, 0,
        ).applied()
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.state_needs).visibility)
        assertEquals("Design review", view.findViewById<android.widget.TextView>(R.id.lead_title).text.toString())
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.running_strip).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.needs_second).visibility)
        assertTrue(
            view.findViewById<android.widget.TextView>(R.id.running_strip_title).text.toString()
                .startsWith("Running: Helper"),
        )
    }

    @Test
    fun lockedNowShowsCountsOnly() {
        val snapshot = quietNow().copy(
            needsYou = listOf(
                NowNeedsYouItem(
                    kind = NowNeedsYouKind.ChatWaiting,
                    title = "Secret chat",
                    detail = "Needs approval",
                    target = NowTarget(storedSessionId = "s-1"),
                ),
            ),
            quiet = null,
        )
        val view = WidgetViews.renderNow(
            context, WidgetViews.UiState.Content(snapshot), locked = true, cold = false, colors, bots = null, 0,
        ).applied()
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.state_locked).visibility)
        assertEquals("Needs you: 1 · Running: 0", view.findViewById<android.widget.TextView>(R.id.state_locked).text.toString())
        assertEquals(View.GONE, view.findViewById<View>(R.id.state_needs).visibility)
    }

    @Test
    fun signedOutNowShowsFallbackAndQuickActions() {
        val view = WidgetViews.renderNow(
            context, WidgetViews.UiState.SignedOut, locked = false, cold = true, colors, bots = null, 0,
        ).applied()
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.state_fallback).visibility)
        assertEquals("Sign in to see Now", view.findViewById<android.widget.TextView>(R.id.fallback_title).text.toString())
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.fallback_new_chat).visibility)
    }

    @Test
    fun coldCarriedNowShowsLiveUnavailableNotice() {
        val snapshot = quietNow().copy(
            needsYou = listOf(
                NowNeedsYouItem(
                    kind = NowNeedsYouKind.KanbanAttention,
                    title = "Publish tokens",
                    detail = "Blocked",
                    target = NowTarget(kanbanBoard = "work", kanbanTaskId = "t-9"),
                ),
            ),
            quiet = null,
        )
        val view = WidgetViews.renderNow(
            context, WidgetViews.UiState.Content(snapshot), locked = false, cold = true, colors, bots = null, 0,
        ).applied()
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.notice_info).visibility)
        assertEquals(
            "Live chat and bot status unavailable",
            view.findViewById<android.widget.TextView>(R.id.notice_info_text).text.toString(),
        )
    }

    @Test
    fun kanbanCountsAndLockedVariant() {
        val snapshot = KanbanWidgetSnapshot(
            gatewayUrl = "https://gw",
            boardSlug = "work",
            boardName = "Work board",
            counts = mapOf("running" to 2, "blocked" to 1, "review" to 0, "ready" to 3),
            rows = listOf(
                KanbanWidgetRow(
                    taskId = "t-1", title = "Ship it", status = "blocked",
                    assignee = "jason", priority = 1, stale = KanbanStaleTier.Red, warningCount = 2,
                ),
            ),
            savedAtEpochSeconds = 1_700_000_000L,
        )
        val content = WidgetViews.renderKanban(context, WidgetViews.UiState.Content(snapshot), locked = false, colors, 0).applied()
        assertEquals("Work board", content.findViewById<android.widget.TextView>(R.id.title).text.toString())
        assertEquals("2", content.findViewById<android.widget.TextView>(R.id.count_running_n).text.toString())
        assertEquals("1", content.findViewById<android.widget.TextView>(R.id.count_blocked_n).text.toString())

        val locked = WidgetViews.renderKanban(context, WidgetViews.UiState.Content(snapshot), locked = true, colors, 0).applied()
        assertEquals(View.VISIBLE, locked.findViewById<View>(R.id.state_locked).visibility)
        assertEquals(View.GONE, locked.findViewById<View>(R.id.state_loading).visibility)
    }

    @Test
    fun chatsSignedOutHidesActionsAndShowsSignIn() {
        val view = WidgetViews.renderChats(
            context, WidgetViews.UiState.SignedOut, locked = false, colors,
            gatewayLabel = "gw", profile = null, appWidgetId = 0,
        ).applied()
        assertEquals(View.GONE, view.findViewById<View>(R.id.action_new_chat).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.action_dictate).visibility)
        assertEquals("Sign in to see chats", view.findViewById<android.widget.TextView>(R.id.empty_title).text.toString())
    }

    @Test
    fun chatsSavedNoticeStripShows() {
        val snapshot = ChatsWidgetSnapshot(
            gatewayUrl = "https://gw",
            profile = null,
            rows = listOf(ChatWidgetRow(storedSessionId = "s-1", title = "Chat")),
            savedAtEpochSeconds = 1_700_000_000L,
        )
        val view = WidgetViews.renderChats(
            context, WidgetViews.UiState.Content(snapshot, savedNotice = "Couldn't refresh · Saved 10:00"),
            locked = false, colors, gatewayLabel = "gw", profile = null, appWidgetId = 0,
        ).applied()
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.saved_notice).visibility)
        assertEquals(
            "Couldn't refresh · Saved 10:00",
            view.findViewById<android.widget.TextView>(R.id.saved_notice_text).text.toString(),
        )
    }

    @Test
    fun chatsRowShowsWaitingLabelWithPrecedenceOverUnread() {
        val row = ChatWidgetRow(
            storedSessionId = "s-1",
            title = "Deploy check",
            subtitle = "Can I proceed?",
            waitingLabel = "Needs approval",
            unread = true,
            pinned = true,
            activityEpochSeconds = 1_700_000_000.0,
        )
        val view = WidgetViews.chatsRow(context, row, colors).applied()
        assertEquals("Deploy check", view.findViewById<android.widget.TextView>(R.id.row_title).text.toString())
        assertEquals("Needs approval", view.findViewById<android.widget.TextView>(R.id.row_status).text.toString())
        assertEquals(View.GONE, view.findViewById<View>(R.id.row_unread).visibility)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.marker_pin).visibility)
        assertNotEquals("", view.findViewById<android.widget.TextView>(R.id.row_age).text.toString())
    }

    @Test
    fun kanbanRowRedStalePillAndMetadata() {
        val row = KanbanWidgetRow(
            taskId = "t-1", title = "Ship it", status = "blocked",
            assignee = "jason", priority = 1, stale = KanbanStaleTier.Red, warningCount = 2,
        )
        val view = WidgetViews.kanbanRow(context, row, colors).applied()
        assertEquals("Ship it", view.findViewById<android.widget.TextView>(R.id.row_title).text.toString())
        assertTrue(view.findViewById<android.widget.TextView>(R.id.row_metadata).text.toString().contains("@jason"))
        assertEquals("Stale!", view.findViewById<android.widget.TextView>(R.id.row_badge).text.toString())
    }

    @Test
    fun emptyBoardKeepsTheCountStripButNoBoardDropsIt() {
        fun board(slug: String, rows: List<KanbanWidgetRow>) = KanbanWidgetSnapshot(
            gatewayUrl = "https://gw",
            boardSlug = slug,
            boardName = slug.ifEmpty { "" },
            counts = mapOf("running" to 0, "blocked" to 0, "review" to 0, "ready" to 0),
            rows = rows,
            savedAtEpochSeconds = 1_700_000_000L,
        )
        // A reachable-but-empty board (M1): the 0-count strip stays, the empty state shows.
        val empty = WidgetViews.renderKanban(
            context, WidgetViews.UiState.Content(board("main", emptyList())), locked = false, colors, 0,
        ).applied()
        assertEquals(View.VISIBLE, empty.findViewById<View>(R.id.count_strip).visibility)
        assertEquals(View.VISIBLE, empty.findViewById<View>(R.id.state_empty).visibility)
        assertEquals(context.getString(R.string.widget_kanban_empty), empty.findViewById<android.widget.TextView>(R.id.empty_title).text.toString())

        // No board at all: the strip drops and the copy says so (design §8.3).
        val none = WidgetViews.renderKanban(
            context, WidgetViews.UiState.Content(board("", emptyList())), locked = false, colors, 0,
        ).applied()
        assertEquals(View.GONE, none.findViewById<View>(R.id.count_strip).visibility)
        assertEquals(context.getString(R.string.widget_kanban_no_board), none.findViewById<android.widget.TextView>(R.id.empty_title).text.toString())
    }

    @Test
    fun needsYouKindsGetTheirOwnAccessibilityLabels() {
        // M6: the lead row's content description names the item's kind, not a running label.
        fun descriptionOf(kind: NowNeedsYouKind): String {
            val snapshot = quietNow().copy(
                needsYou = listOf(
                    NowNeedsYouItem(
                        kind = kind,
                        title = "Helper",
                        detail = "Needs a fix",
                        target = NowTarget(storedSessionId = "s-1"),
                    ),
                ),
            )
            val view = WidgetViews.renderNow(
                context, WidgetViews.UiState.Content(snapshot), locked = false, cold = false, colors, bots = null, 0,
            ).applied()
            return view.findViewById<View>(R.id.needs_lead).contentDescription.toString()
        }

        assertTrue(descriptionOf(NowNeedsYouKind.BotTrouble).startsWith(context.getString(R.string.widget_now_kind_bot_trouble)))
        assertTrue(descriptionOf(NowNeedsYouKind.RoutineFailed).startsWith(context.getString(R.string.widget_now_kind_routine_failed)))
        assertTrue(descriptionOf(NowNeedsYouKind.KanbanAttention).startsWith(context.getString(R.string.widget_now_kind_task_status)))
        assertTrue(descriptionOf(NowNeedsYouKind.CronProblem).startsWith(context.getString(R.string.widget_now_kind_scheduled_problem)))
        assertTrue(descriptionOf(NowNeedsYouKind.ChatWaiting).startsWith(context.getString(R.string.widget_now_kind_waiting)))
    }

    @Test
    fun scheduledProblemRowMissedKindShowsWarningPill() {
        val row = ScheduledWidgetRow(
            jobId = "j-1",
            profile = "ops",
            displayName = "Nightly",
            scheduleDisplay = "0 3 * * *",
            state = "error",
            problemSummary = "Gateway unreachable",
            isProblem = true,
            problemKind = ScheduledProblemKind.Missed,
        )
        val view = WidgetViews.scheduledRow(context, row, colors).applied()
        assertEquals("Nightly", view.findViewById<android.widget.TextView>(R.id.row_title).text.toString())
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.problem_icon).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.time_column).visibility)
        assertEquals("Missed", view.findViewById<android.widget.TextView>(R.id.row_badge).text.toString())
        assertEquals("Gateway unreachable", view.findViewById<android.widget.TextView>(R.id.row_subtitle).text.toString())
    }

    @Test
    fun scheduledUpcomingRowShowsTimeAndRoutineOwner() {
        val row = ScheduledWidgetRow(
            jobId = "j-2",
            profile = "reviewer",
            displayName = "Weekly summary",
            routineOwnerLabel = "Reviewer",
            scheduleDisplay = "Mon 09:00",
            nextRunEpochSeconds = 1_800_000_000L,
            state = "scheduled",
        )
        val view = WidgetViews.scheduledRow(context, row, colors).applied()
        assertEquals(View.GONE, view.findViewById<View>(R.id.problem_icon).visibility)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.time_column).visibility)
        assertNotEquals("", view.findViewById<android.widget.TextView>(R.id.time_primary).text.toString())
        assertTrue(view.findViewById<android.widget.TextView>(R.id.row_subtitle).text.toString().contains("@Reviewer"))
        assertEquals(View.GONE, view.findViewById<View>(R.id.row_badge).visibility)
    }
}
