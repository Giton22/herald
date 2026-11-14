package dev.hermeskotlin.android.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

/** Chats: recent and pinned conversations, with New chat, Dictate and Refresh (docs/WIDGETS-DESIGN.md §7). */
class ChatsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        goAsyncRefresh(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) goAsyncDeleted(context, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetIntents.ACTION_REFRESH_CHATS) goAsyncRefresh(context)
    }
}
