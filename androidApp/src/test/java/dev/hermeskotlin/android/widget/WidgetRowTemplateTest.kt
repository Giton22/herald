package dev.hermeskotlin.android.widget

import android.app.PendingIntent
import android.content.Intent
import android.widget.RemoteViews
import dev.hermeskotlin.android.R
import dev.hermeskotlin.core.widget.ChatWidgetRow
import dev.hermeskotlin.core.widget.ChatsWidgetSnapshot
import dev.hermeskotlin.core.widget.KanbanWidgetRow
import dev.hermeskotlin.core.widget.KanbanWidgetSnapshot
import dev.hermeskotlin.core.widget.ScheduledWidgetRow
import dev.hermeskotlin.core.widget.ScheduledWidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * B1 (docs/WIDGETS-SPEC.md D19): the three collection widgets' `ListView`s carry one mutable
 * `PendingIntent` template targeting `HeraldActivity`, so a row tap reaches the activity with the
 * row's fill-in extras merged in. The wiring lives in the `RemoteViews` action list, which this
 * test reads directly.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = WidgetTestApp::class)
class WidgetRowTemplateTest {

    private val context = RuntimeEnvironment.getApplication()
    private val colors get() = WidgetColors(context, "Blue")

    private val actionsField = RemoteViews::class.java.getDeclaredField("mActions").apply { isAccessible = true }

    private fun actionsOf(views: RemoteViews): List<Any> =
        (actionsField.get(views) as? java.util.ArrayList<*>)?.filterNotNull() ?: emptyList()

    private fun templateOn(views: RemoteViews): PendingIntent {
        val action = actionsOf(views).firstOrNull { it.javaClass.simpleName == "SetPendingIntentTemplate" }
            ?: error("no PendingIntent template action — the collection's row taps are dead (D19)")
        val field = action.javaClass.getDeclaredField("mPendingIntentTemplate")
        field.isAccessible = true
        return field.get(action) as PendingIntent
    }

    private fun fillInsOf(views: RemoteViews): List<Intent> =
        actionsOf(views)
            .filter { it.javaClass.simpleName == "SetOnClickResponse" }
            .mapNotNull { action ->
                val response = action.javaClass.getDeclaredField("mResponse").apply { isAccessible = true }.get(action)
                val fill = response.javaClass.getDeclaredField("mFillIntent").apply { isAccessible = true }.get(response) as? Intent
                fill
            }

    @Test
    fun chatsListCarriesTheMutableRowTemplate() = assertTemplateOn(
        WidgetViews.renderChats(
            context,
            WidgetViews.UiState.Content(
                ChatsWidgetSnapshot(
                    gatewayUrl = "https://gw",
                    profile = null,
                    rows = listOf(ChatWidgetRow(storedSessionId = "s-1", title = "Chat")),
                    savedAtEpochSeconds = 1_700_000_000L,
                ),
            ),
            locked = false, colors, "GW", null, 0,
        ),
    )

    @Test
    fun kanbanListCarriesTheMutableRowTemplate() = assertTemplateOn(
        WidgetViews.renderKanban(
            context,
            WidgetViews.UiState.Content(
                KanbanWidgetSnapshot(
                    gatewayUrl = "https://gw",
                    boardSlug = "main",
                    boardName = "Main",
                    counts = mapOf("running" to 1, "blocked" to 0, "review" to 0, "ready" to 0),
                    rows = listOf(KanbanWidgetRow(taskId = "t-1", title = "Task", status = "running")),
                    savedAtEpochSeconds = 1_700_000_000L,
                ),
            ),
            locked = false, colors, 0,
        ),
    )

    @Test
    fun scheduledListCarriesTheMutableRowTemplate() = assertTemplateOn(
        WidgetViews.renderScheduled(
            context,
            WidgetViews.UiState.Content(
                ScheduledWidgetSnapshot(
                    gatewayUrl = "https://gw",
                    rows = listOf(ScheduledWidgetRow(jobId = "j-1", displayName = "Nightly", state = "scheduled")),
                    savedAtEpochSeconds = 1_700_000_000L,
                ),
            ),
            locked = false, colors, 0,
        ),
    )

    private fun assertTemplateOn(views: RemoteViews) {
        val shadow = Shadows.shadowOf(templateOn(views))
        assertEquals(
            "the template must launch HeraldActivity",
            "dev.hermeskotlin.android.HeraldActivity",
            shadow.savedIntent.component?.className,
        )
        assertTrue(
            "the template must be mutable so fill-in intents merge (D19)",
            shadow.flags and PendingIntent.FLAG_MUTABLE != 0,
        )
    }

    @Test
    fun rowsWithDifferentIdsCarryDistinctFillIns() {
        val first = fillInsOf(WidgetViews.chatsRow(context, ChatWidgetRow(storedSessionId = "s-1", title = "One"), colors))
        val second = fillInsOf(WidgetViews.chatsRow(context, ChatWidgetRow(storedSessionId = "s-2", title = "Two"), colors))
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertEquals(
            "s-1",
            first.single().getStringExtra(dev.hermeskotlin.android.notify.ChatNotifications.EXTRA_OPEN_SESSION),
        )
        assertEquals(
            "s-2",
            second.single().getStringExtra(dev.hermeskotlin.android.notify.ChatNotifications.EXTRA_OPEN_SESSION),
        )
        val kanbanFill = fillInsOf(WidgetViews.kanbanRow(context, KanbanWidgetRow(taskId = "t-9", title = "Ship", status = "running"), colors, boardSlug = "herald")).single()
        assertEquals("herald", kanbanFill.getStringExtra(WidgetIntents.EXTRA_OPEN_KANBAN_BOARD))
        assertEquals("t-9", kanbanFill.getStringExtra(WidgetIntents.EXTRA_OPEN_KANBAN_TASK))
    }
}
