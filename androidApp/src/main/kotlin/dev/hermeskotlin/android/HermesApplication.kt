package dev.hermeskotlin.android

import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import dev.hermeskotlin.android.notify.BotNotifier
import dev.hermeskotlin.android.notify.ChatNotifications
import dev.hermeskotlin.android.notify.ChatNotifier
import dev.hermeskotlin.android.notify.RoomNotifier
import dev.hermeskotlin.android.notify.WatchNotifier
import dev.hermeskotlin.android.phone.PhoneConfirmations
import dev.hermeskotlin.android.notify.notifyModule
import dev.hermeskotlin.android.phone.Geofences
import dev.hermeskotlin.android.push.PushListener
import dev.hermeskotlin.android.wear.WatchAccentSync
import dev.hermeskotlin.android.wear.WatchRinger
import dev.hermeskotlin.android.wear.WearRequests
import dev.hermeskotlin.android.wear.wearModule
import dev.hermeskotlin.android.widget.HomeWidgets
import dev.hermeskotlin.core.phone.PhoneToolsBridge
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.settings.AppLockTimer
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.ui.applyAppLanguage
import dev.hermeskotlin.ui.di.sharedModules
import dev.hermeskotlin.ui.handOverStoredLanguage
import kotlinx.coroutines.flow.first
import dev.hermeskotlin.ui.systemKeepsAppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
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
            modules(sharedModules + notifyModule + wearModule)
        }.koin
        koin.get<WearRequests>().start()
        koin.get<WatchRinger>().start()
        koin.get<WatchAccentSync>().start()
        koin.get<ChatNotifier>().start()
        koin.get<BotNotifier>().start()
        koin.get<RoomNotifier>().start()
        koin.get<WatchNotifier>().start()
        koin.get<PushSetup>().start()
        koin.get<PushListener>().start()
        koin.get<PhoneToolsBridge>().start()
        // Android forgets proximity alerts on a restart; a restart or an update wakes Herald to arm them again.
        koin.get<Geofences>().start()
        HomeWidgets(this, koin.get(), koin.get()).start()
        followLanguageSetting(koin.get(), koin.get())
    }

    /**
     * Android 12 and older: Settings › Language is the app's own setting, and text read outside the screens
     * (notifications, shortcuts) follows it too, from the moment the stored settings are read. Android 13 and
     * later apply the per-app language to the whole process themselves, and take over one picked before the update.
     */
    private fun followLanguageSetting(settings: SettingsStore, scope: CoroutineScope) {
        if (systemKeepsAppLanguage) {
            scope.launch(Dispatchers.Main) {
                val stored = settings.settings.filterNotNull().first().language
                if (handOverStoredLanguage(this@HermesApplication, stored)) settings.update { it.copy(language = null) }
            }
            return
        }
        scope.launch(Dispatchers.Main) {
            settings.settings.filterNotNull().map { it.language }.distinctUntilChanged().collect {
                applyAppLanguage(this@HermesApplication, it)
                renameChannels()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A change of the phone's own language resets the process's; the picked one goes back over it.
        if (!systemKeepsAppLanguage) get<SettingsStore>().settings.value?.let { applyAppLanguage(this, it.language) }
        renameChannels()
    }

    /** A new language: the notification channels take its names, as Settings › Notifications lists them. */
    private fun renameChannels() {
        GlobalContext.getOrNull()?.run {
            get<ChatNotifications>().createChannels()
            get<PhoneConfirmations>().createChannel()
        }
    }
}
