package dev.hermeskotlin.android.widget

import dev.hermeskotlin.android.HermesApplication
import dev.hermeskotlin.android.notify.BotNotifier
import dev.hermeskotlin.core.settings.AppLockTimer
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** The widgets' Koin wiring: [WidgetRefresher] decides, [WidgetUpdater] listens and pushes. */
val widgetModule = module {
    single { (androidContext() as HermesApplication).appLock }
    single { WidgetRefresher(get(), get(), get(), knownBots = { get<BotNotifier>().knownRoster() }) }
    single {
        WidgetUpdater(
            androidContext(), get(), get(), get(), get(), get(), get(), get<AppLockTimer>(), get(), get(),
        )
    }
}
