package dev.hermeskotlin.android.notify

import android.os.Build
import dev.hermeskotlin.android.phone.AndroidPhoneTools
import dev.hermeskotlin.android.phone.ChatTargets
import dev.hermeskotlin.android.phone.Geofences
import dev.hermeskotlin.core.phone.CallOpeners
import dev.hermeskotlin.core.phone.WatchedPlaces
import dev.hermeskotlin.android.phone.PhoneConfirmations
import dev.hermeskotlin.android.push.PushListener
import dev.hermeskotlin.core.phone.PhoneToolRunner
import dev.hermeskotlin.core.phone.PhoneToolsBridge
import dev.hermeskotlin.core.phone.quietNow
import dev.hermeskotlin.core.settings.SettingsStore
import java.time.LocalTime
import dev.hermeskotlin.android.push.PushStore
import dev.hermeskotlin.core.inbox.InboxApi
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import dev.hermeskotlin.core.wear.WatchRelay
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

val notifyModule = module {
    single { AppVisibility(androidApplication()) }
    single {
        val koin = getKoin()
        // A voice chat or a call already on, here or from the watch, has you: a question there is asked on it, and
        // one elsewhere waits as a notification, not rung over it.
        ChatNotifications(androidContext(), get()).apply {
            voiceOn = { koin.get<VoiceCallKeeper>().holding || koin.get<WatchRelay>().onCall }
            quietUntil = {
                val now = LocalTime.now()
                koin.get<SettingsStore>().settings.value?.takeIf { it.quietNow(now.hour * 60 + now.minute) }?.quietUntil
            }
        }
    }
    // Why the agent called, from the call picked up on the watch to the call the watch starts.
    single { CallOpeners() }
    single { VoiceCallKeeper(androidContext()) } bind VoiceKeepAlive::class
    single { ChatNotifier(androidContext(), get(), get(), get(), get(), get(), get(), get<WatchRelay>().busy) }
    single { WatchNotifier(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { BotNotifier(androidContext(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { RoomNotifier(get(), get(), get(), get(), get(), get()) }
    single { PushStore(androidContext()) } bind PushKeys::class
    single { InboxApi(get(), get()) }
    single { PushListener(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { PushSetup(get(), get(), get(), get(), get(), get(), deviceName = { Build.MODEL ?: "Android phone" }) }
    single { PhoneConfirmations(androidContext()) }
    single { Geofences(androidContext(), get(), get()) } bind WatchedPlaces::class
    single { ChatTargets(get(), get(), get(), get()) }
    single { AndroidPhoneTools(androidContext(), get(), get(), get(), get()) } bind PhoneToolRunner::class
    single { PhoneToolsBridge(get(), get(), get(), get(), get(), get(), get(), get(), deviceName = { Build.MODEL ?: "Android phone" }) }
}
