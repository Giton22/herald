package dev.hermeskotlin.android.wear

import dev.hermeskotlin.core.wear.WatchOutbox
import dev.hermeskotlin.core.wear.WatchRelay
import dev.hermeskotlin.ui.voice.WatchCalls
import dev.hermeskotlin.ui.calls.PhoneCallModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** The watch companion's phone side: the relay to Hermes, its live calls, the Data Layer under them, its request notifications and the accent it follows. */
val wearModule = module {
    single<WatchOutbox> { PhoneOutbox(androidContext()) }
    single { WatchRelay(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { WatchCalls(get(), get(), get(), get(), get()) }
    single { WearRequests(get(), get()) }
    single(createdAtStart = true) { WatchProfileNotifier(androidContext(), get(), get(), get()).also { it.start() } }
    single { WatchRinger(androidContext(), get(), get(), get(), get(), get()) }
    single { WatchAccentSync(androidContext(), get(), get()) }
    // A call for an answer picked up on the phone holds its chat as a call from the watch does.
    viewModelOf(::PhoneCallModel)
}
