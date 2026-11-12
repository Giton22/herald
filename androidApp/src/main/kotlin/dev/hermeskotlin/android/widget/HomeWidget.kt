package dev.hermeskotlin.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import dev.hermeskotlin.android.HeraldActivity
import dev.hermeskotlin.android.R
import dev.hermeskotlin.core.gateway.GatewayList
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.shortlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The home-screen widget: New chat, Dictate and Call Hermes on the gateway in use (the launcher shortcuts'
 * `hermes://` links), and, with more than one gateway saved, up to six of them to switch to in a tap.
 * Drawn with plain RemoteViews; [HomeWidgets] draws it again whenever the saved gateways change.
 */
class HomeWidget : AppWidgetProvider(), KoinComponent {
    private val gateways: GatewayRepository by inject()
    private val scope: CoroutineScope by inject()

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // The saved gateways are read from storage once per process; a widget placed first thing waits for them.
        val pending = goAsync()
        scope.launch {
            try {
                manager.updateAppWidget(ids, views(context, gateways.all()))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** The saved gateway, by address, that a gateway on the widget opens the app on. */
        const val EXTRA_OPEN_GATEWAY = "dev.hermeskotlin.extra.OPEN_GATEWAY"

        /** Draws every placed widget for [list]. */
        fun refresh(context: Context, list: GatewayList) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HomeWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, list))
        }

        private fun views(context: Context, list: GatewayList): RemoteViews =
            RemoteViews(context.packageName, R.layout.home_widget).apply {
                setOnClickPendingIntent(R.id.widget_new_chat, link(context, "hermes://new-chat", 0))
                setOnClickPendingIntent(R.id.widget_dictate, link(context, "hermes://new-chat-voice", 1))
                setOnClickPendingIntent(R.id.widget_call, link(context, "hermes://voice-chat", 2))
                val shown = list.shortlist(GATEWAY_VIEWS.size)
                setViewVisibility(R.id.widget_gateways, if (shown.isEmpty()) View.GONE else View.VISIBLE)
                setViewVisibility(R.id.widget_gateways_more, if (shown.size > GATEWAYS_PER_ROW) View.VISIBLE else View.GONE)
                GATEWAY_VIEWS.forEachIndexed { i, id ->
                    val gateway = shown.getOrNull(i)
                    setViewVisibility(id, if (gateway == null) View.GONE else View.VISIBLE)
                    if (gateway == null) return@forEachIndexed
                    // The one in use is marked, as the gateways sheet checks it.
                    val inUse = gateway.url == list.currentUrl
                    setTextViewText(id, if (inUse) "● ${gateway.label}" else gateway.label)
                    setContentDescription(id, context.getString(if (inUse) R.string.widget_gateway_in_use else R.string.widget_gateway_switch, gateway.label))
                    setOnClickPendingIntent(id, gatewayIntent(context, gateway.url, i))
                }
            }

        private fun link(context: Context, uri: String, code: Int): PendingIntent = PendingIntent.getActivity(
            context,
            code,
            Intent(Intent.ACTION_VIEW, uri.toUri(), context, HeraldActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun gatewayIntent(context: Context, url: String, index: Int): PendingIntent = PendingIntent.getActivity(
            context,
            GATEWAY_REQUEST + index,
            Intent(context, HeraldActivity::class.java)
                .putExtra(EXTRA_OPEN_GATEWAY, url)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** The gateways the widget has room for, in two rows of three; the rest are a tap further, in the app. */
        private val GATEWAY_VIEWS = listOf(
            R.id.widget_gateway_0, R.id.widget_gateway_1, R.id.widget_gateway_2,
            R.id.widget_gateway_3, R.id.widget_gateway_4, R.id.widget_gateway_5,
        )
        private const val GATEWAYS_PER_ROW = 3

        /** Past the actions' request codes, so each gateway keeps its own PendingIntent. */
        private const val GATEWAY_REQUEST = 100
    }
}

/** Draws the home-screen widgets again whenever the saved gateways, or the one in use, change. */
class HomeWidgets(private val context: Context, private val gateways: GatewayRepository, private val scope: CoroutineScope) {
    fun start() {
        scope.launch {
            // Read first: the list is empty until then, which would take the gateways off a widget showing them.
            gateways.all()
            gateways.list.collect { HomeWidget.refresh(context, it) }
        }
    }
}
