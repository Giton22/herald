package dev.hermeskotlin.android

import android.app.Application
import dev.hermeskotlin.ui.di.sharedModules
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class HermesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@HermesApplication)
            modules(sharedModules)
        }
    }
}
