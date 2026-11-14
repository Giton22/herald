package dev.hermeskotlin.android.widget

import android.content.BroadcastReceiver
import android.content.Context
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * How a provider answers the system and its refresh button (docs/WIDGETS-SPEC.md D26): hand the
 * work to the app's [WidgetUpdater] and let the broadcast's `goAsync()` window cover the refresh.
 */
internal fun BroadcastReceiver.goAsyncRefresh(context: Context) {
    val updater = GlobalContext.get().get<WidgetUpdater>()
    val pending = goAsync()
    updater.scope.launch {
        try {
            updater.refreshAndRender(cold = true)
        } finally {
            pending.finish()
        }
    }
}

/** A removed instance's scope goes with it (D23); snapshots are shared, so only the last instance's removal deletes. */
internal fun BroadcastReceiver.goAsyncDeleted(context: Context, appWidgetId: Int) {
    val updater = GlobalContext.get().get<WidgetUpdater>()
    updater.onDeleted(appWidgetId)
}
