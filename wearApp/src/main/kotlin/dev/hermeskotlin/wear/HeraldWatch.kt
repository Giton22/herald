package dev.hermeskotlin.wear

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

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
    val rings by lazy { WatchRings(this) }
    val link by lazy { WatchLink(this, store, speech, scope, rings) }

    /** Known to Herald alone: a call started by an intent ([MainActivity.answerCallIntent]) carries it, since any app can start that screen. */
    val callToken: String = UUID.randomUUID().toString()

    companion object {
        fun of(context: Context): HeraldWatch = context.applicationContext as HeraldWatch
    }
}
