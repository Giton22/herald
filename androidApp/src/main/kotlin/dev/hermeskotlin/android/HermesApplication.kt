package dev.hermeskotlin.android

import android.app.Application
import android.content.Intent
import dev.hermeskotlin.android.notify.BotNotifier
import dev.hermeskotlin.android.notify.ChatNotifier
import dev.hermeskotlin.android.notify.RoomNotifier
import dev.hermeskotlin.android.notify.WatchNotifier
import dev.hermeskotlin.android.notify.notifyModule
import dev.hermeskotlin.android.push.PushListener
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.settings.AppLockTimer
import dev.hermeskotlin.ui.di.sharedModules
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class HermesApplication : Application() {

    /** App lock's state lives as long as the process, so a recreated activity isn't a cold start. */
    val appLock = AppLockTimer()

    /**
     * A link or share that came in while App lock was locked; it opens once the user unlocks. Kept here,
     * not on the activity, so a rotation or theme change under the lock doesn't drop it.
     */
    var heldIntent: Intent? = null

    override fun onCreate() {
        super.onCreate()
        val koin = startKoin {
            androidContext(this@HermesApplication)
            modules(sharedModules + notifyModule)
        }.koin
        koin.get<ChatNotifier>().start()
        koin.get<BotNotifier>().start()
        koin.get<RoomNotifier>().start()
        koin.get<WatchNotifier>().start()
        koin.get<PushSetup>().start()
        koin.get<PushListener>().start()
    }
}
