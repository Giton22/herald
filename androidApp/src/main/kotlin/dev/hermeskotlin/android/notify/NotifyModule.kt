package dev.hermeskotlin.android.notify

import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val notifyModule = module {
    single { AppVisibility(androidApplication()) }
    single { ChatNotifications(androidContext()) }
    single { ChatNotifier(androidContext(), get(), get(), get(), get(), get(), get()) }
    single { BotNotifier(androidContext(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
