package dev.hermeskotlin.android

import android.app.Application
import dev.hermeskotlin.android.notify.ChatNotifier
import dev.hermeskotlin.android.notify.notifyModule
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
    }
}
