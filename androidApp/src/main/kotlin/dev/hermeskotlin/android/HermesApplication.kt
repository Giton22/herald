package dev.hermeskotlin.android

import android.app.Application
import dev.hermeskotlin.android.notify.ChatNotifier
import dev.hermeskotlin.android.notify.notifyModule
import dev.hermeskotlin.core.settings.AppLockTimer
import dev.hermeskotlin.ui.di.sharedModules
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class HermesApplication : Application() {

    /** App lock's state lives as long as the process, so a recreated activity isn't a cold start. */
    val appLock = AppLockTimer()

    override fun onCreate() {
        super.onCreate()
        val koin = startKoin {
            androidContext(this@HermesApplication)
            modules(sharedModules + notifyModule)
        }.koin
        koin.get<ChatNotifier>().start()
    }
}
