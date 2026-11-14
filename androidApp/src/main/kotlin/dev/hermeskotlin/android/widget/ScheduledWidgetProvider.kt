package dev.hermeskotlin.android.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

/** Scheduled: problem and missed-fire jobs first, then what is coming up (docs/WIDGETS-DESIGN.md §9). */
class ScheduledWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        goAsyncRefresh(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) goAsyncDeleted(context, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetIntents.ACTION_REFRESH_SCHEDULED) goAsyncRefresh(context)
    }
}
