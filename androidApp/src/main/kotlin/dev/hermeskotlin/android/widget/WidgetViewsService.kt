package dev.hermeskotlin.android.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import dev.hermeskotlin.android.R
import dev.hermeskotlin.core.settings.AppLockTimer
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.widget.ChatWidgetRow
import dev.hermeskotlin.core.widget.KanbanWidgetRow
import dev.hermeskotlin.core.widget.ScheduledWidgetRow
import dev.hermeskotlin.core.widget.WidgetSnapshotStore
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext

/**
 * Backs the three scrollable widgets' `ListView`s (docs/WIDGETS-SPEC.md D23): one
 * `RemoteViewsService` whose factory reads only the snapshot its intent names — the gateway and,
 * for Tasks, the board — so a refresh can never push a cross-gateway `TransactionTooLargeException`
 * through Binder. Rows are built by [WidgetViews]; this class adds no rendering of its own.
 * While App lock covers the widgets the factory serves no rows at all (D16): counts only.
 */
class WidgetViewsService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        RowsFactory(applicationContext, intent)

    private class RowsFactory(
        private val context: Context,
        intent: Intent,
    ) : RemoteViewsFactory {

        private val store: WidgetSnapshotStore = GlobalContext.get().get()
        private val settings: SettingsStore = GlobalContext.get().get()
        private val appLock: AppLockTimer = GlobalContext.get().get()

        private val kind = intent.getStringExtra(WidgetIntents.EXTRA_WIDGET_KIND) ?: WidgetIntents.KIND_CHATS
        private val gateway = intent.getStringExtra(WidgetIntents.EXTRA_WIDGET_GATEWAY).orEmpty()
        private val profile = intent.getStringExtra(WidgetIntents.EXTRA_WIDGET_PROFILE)
        private val board = intent.getStringExtra(WidgetIntents.EXTRA_WIDGET_BOARD)

        /** The rows of the one snapshot this instance was bound to; empty until the first load. */
        private var rows: List<Any> = emptyList()

        /**
         * The same lock rule as the renderers (D16): the App lock setting on and the timer locked
         * or never launched — the service runs in processes the activity never created, so its
         * cold default is locked too.
         */
        private fun locked(): Boolean {
            val prefs = settings.settings.value ?: return true
            return prefs.appLock && (appLock.locked.value || !appLock.hasLaunched)
        }

        override fun onCreate() = Unit

        // Runs on a binder thread, not the main one; the store read is the load the framework wants here.
        override fun onDataSetChanged() {
            rows = if (locked()) {
                emptyList()
            } else {
                runBlocking {
                    when (kind) {
                        WidgetIntents.KIND_CHATS -> store.readChats(gateway, profile)?.rows
                        WidgetIntents.KIND_KANBAN -> board?.let { store.readKanban(gateway, it)?.rows }
                        else -> store.readScheduled(gateway)?.rows
                    }
                }.orEmpty()
            }
        }

        override fun getViewAt(position: Int): RemoteViews {
            val colors = WidgetColors(context, settings.settings.value?.accent)
            val row = rows[position]
            return when (kind) {
                WidgetIntents.KIND_CHATS -> WidgetViews.chatsRow(context, row as ChatWidgetRow, colors)
                WidgetIntents.KIND_KANBAN -> WidgetViews.kanbanRow(context, row as KanbanWidgetRow, colors, board)
                else -> WidgetViews.scheduledRow(context, row as ScheduledWidgetRow, colors)
            }
        }

        override fun getLoadingView(): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_loading_row)

        override fun getViewTypeCount(): Int = 1 // one row layout per factory instance

        override fun getCount(): Int = rows.size

        override fun getItemId(position: Int): Long = position.toLong()

        override fun hasStableIds(): Boolean = false

        override fun onDestroy() {
            rows = emptyList()
        }
    }
}
