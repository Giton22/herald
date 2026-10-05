package dev.hermeskotlin.android.notify

import android.os.Build
import dev.hermeskotlin.android.push.PushListener
import dev.hermeskotlin.android.push.PushStore
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushSetup
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

val notifyModule = module {
    single { AppVisibility(androidApplication()) }
    single { ChatNotifications(androidContext()) }
    single { ChatNotifier(androidContext(), get(), get(), get(), get(), get(), get()) }
    single { WatchNotifier(get(), get(), get(), get(), get(), get(), get()) }
    single { BotNotifier(androidContext(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { PushStore(androidContext()) } bind PushKeys::class
    single { PushListener(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { PushSetup(get(), get(), get(), get(), get(), get(), deviceName = { Build.MODEL ?: "Android phone" }) }
}
