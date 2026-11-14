package dev.hermeskotlin.android.widget

import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import dev.hermeskotlin.android.R
import dev.hermeskotlin.core.settings.AppLockTimer
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.widget.ChatWidgetRow
import dev.hermeskotlin.core.widget.ChatsWidgetSnapshot
import dev.hermeskotlin.core.widget.KanbanWidgetRow
import dev.hermeskotlin.core.widget.KanbanWidgetSnapshot
import dev.hermeskotlin.core.widget.NowNeedsYouItem
import dev.hermeskotlin.core.widget.NowNeedsYouKind
import dev.hermeskotlin.core.widget.NowTarget
import dev.hermeskotlin.core.widget.NowWidgetSnapshot
import dev.hermeskotlin.core.widget.ScheduledWidgetRow
import dev.hermeskotlin.core.widget.ScheduledWidgetSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * B3 (docs/WIDGETS-SPEC.md D16, docs/WIDGETS-DESIGN.md §12.5): the App-locked variant hides every
 * content container even when the `RemoteViews` re-applies onto a host the unlocked render filled,
 * and the lock's cold default treats a never-launched process as locked.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = WidgetTestApp::class)
class WidgetLockTest {

    private val context = RuntimeEnvironment.getApplication()
    private val colors get() = WidgetColors(context, "Blue")

    /**
     * The production shape: the widget host inflates the layout once and `reapply`s every later
     * render onto it. The unlocked render must show its content first — then the locked reapply
     * must hide all of it, or the previous render leaks through the lock.
     */
    private fun assertLockedReapply(unlocked: RemoteViews, locked: RemoteViews, shownId: Int, contentIds: List<Int>) {
        val host = FrameLayout(context)
        val view = unlocked.apply(context, host)
        assertEquals(View.VISIBLE, view.findViewById<View>(shownId).visibility)

        locked.reapply(context, view)
        for (id in contentIds) {
            assertEquals("content view $id must be GONE once locked", View.GONE, view.findViewById<View>(id).visibility)
        }
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.state_locked).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.state_loading).visibility)
    }

    private val chatsContent = listOf(
        R.id.list, R.id.state_empty, R.id.saved_notice, R.id.context, R.id.action_new_chat, R.id.action_dictate,
    )
    private val kanbanContent = listOf(R.id.list, R.id.state_empty, R.id.count_strip, R.id.saved_notice, R.id.context)
    private val nowContent = listOf(
        R.id.state_needs, R.id.state_running, R.id.state_quiet, R.id.context,
        R.id.action_new_chat, R.id.action_dictate,
    )
    private val scheduledContent = listOf(R.id.list, R.id.state_empty, R.id.saved_notice, R.id.context)

    @Test
    fun lockedChatsReapplyHidesEverything() {
        val snapshot = ChatsWidgetSnapshot(
            gatewayUrl = "https://gw",
            profile = null,
            rows = listOf(ChatWidgetRow(storedSessionId = "s-1", title = "Chat")),
            savedAtEpochSeconds = 1_700_000_000L,
        )
        assertLockedReapply(
            WidgetViews.renderChats(context, WidgetViews.UiState.Content(snapshot), false, colors, "GW", null, 0),
            WidgetViews.renderChats(context, WidgetViews.UiState.Content(snapshot), true, colors, "GW", null, 0),
            R.id.list, chatsContent,
        )
    }

    @Test
    fun lockedKanbanReapplyHidesTheCountStrip() {
        val snapshot = KanbanWidgetSnapshot(
            gatewayUrl = "https://gw",
            boardSlug = "main",
            boardName = "Main",
            counts = mapOf("running" to 2, "blocked" to 1, "review" to 0, "ready" to 3),
            rows = listOf(KanbanWidgetRow(taskId = "t-1", title = "Task", status = "running")),
            savedAtEpochSeconds = 1_700_000_000L,
        )
        assertLockedReapply(
            WidgetViews.renderKanban(context, WidgetViews.UiState.Content(snapshot), false, colors, 0),
            WidgetViews.renderKanban(context, WidgetViews.UiState.Content(snapshot), true, colors, 0),
            R.id.count_strip, kanbanContent,
        )
    }

    @Test
    fun lockedNowReapplyHidesTheNeedsList() {
        val snapshot = NowWidgetSnapshot(
            gatewayUrl = "https://gw",
            profile = null,
            needsYou = listOf(
                NowNeedsYouItem(
                    kind = NowNeedsYouKind.ChatWaiting,
                    title = "Design review",
                    detail = "Needs approval",
                    target = NowTarget(storedSessionId = "s-1"),
                ),
            ),
            savedAtEpochSeconds = 1_700_000_000L,
        )
        assertLockedReapply(
            WidgetViews.renderNow(context, WidgetViews.UiState.Content(snapshot), false, false, colors, null, 0),
            WidgetViews.renderNow(context, WidgetViews.UiState.Content(snapshot), true, false, colors, null, 0),
            R.id.state_needs, nowContent,
        )
    }

    @Test
    fun lockedScheduledReapplyHidesTheList() {
        val snapshot = ScheduledWidgetSnapshot(
            gatewayUrl = "https://gw",
            rows = listOf(ScheduledWidgetRow(jobId = "j-1", displayName = "Nightly", state = "scheduled")),
            savedAtEpochSeconds = 1_700_000_000L,
        )
        assertLockedReapply(
            WidgetViews.renderScheduled(context, WidgetViews.UiState.Content(snapshot), false, colors, 0),
            WidgetViews.renderScheduled(context, WidgetViews.UiState.Content(snapshot), true, colors, 0),
            R.id.list, scheduledContent,
        )
    }

    // -- The cold default: a never-launched process with App lock on is locked. --

    private fun settingsWith(appLock: Boolean): SettingsStore {
        val store = SettingsStore(InMemoryKeyValueStore(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        store.update { it.copy(appLock = appLock) }
        return store
    }

    @Test
    fun coldStartWithAppLockOnIsLocked() = runBlocking {
        assertTrue(isWidgetLocked(settingsWith(appLock = true), AppLockTimer()))
    }

    @Test
    fun coldStartWithAppLockOffIsUnlocked() = runBlocking {
        assertFalse(isWidgetLocked(settingsWith(appLock = false), AppLockTimer()))
    }

    @Test
    fun launchedAndUnlockedTimerIsUnlocked() = runBlocking {
        val timer = AppLockTimer()
        timer.onLaunch(enabled = true, fresh = true)
        timer.onUnlocked()
        assertFalse(isWidgetLocked(settingsWith(appLock = true), timer))
    }
}
