package dev.hermeskotlin.wear

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The watch app's few long-lived parts, shared by the screen, the listener, the tile and the complication. */
class HeraldWatch : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store by lazy { WatchStore(this) }
    internal val callDisplay by lazy { WatchCallDisplay(this, scope) }

    override fun onCreate() {
        super.onCreate()
        callDisplay.refresh()
    }

    val speech by lazy { SpeechOut(this) }
    val link by lazy { WatchLink(this, store, speech, scope) }

    companion object {
        fun of(context: Context): HeraldWatch = context.applicationContext as HeraldWatch
    }
}
