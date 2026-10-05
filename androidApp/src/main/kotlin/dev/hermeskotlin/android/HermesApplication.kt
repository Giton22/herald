package dev.hermeskotlin.android

import android.app.Application
import dev.hermeskotlin.android.notify.BackgroundConnection
import dev.hermeskotlin.android.notify.BotNotifier
import dev.hermeskotlin.android.notify.ChatNotifier
import dev.hermeskotlin.android.notify.notifyModule
import dev.hermeskotlin.android.push.PushListener
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.ui.di.sharedModules
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class HermesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val koin = startKoin {
            androidContext(this@HermesApplication)
            modules(sharedModules + notifyModule)
        }.koin
        koin.get<ChatNotifier>().start()
        koin.get<BotNotifier>().start()
        koin.get<BackgroundConnection>().start()
        koin.get<PushSetup>().start()
        koin.get<PushListener>().start()
    }
}
