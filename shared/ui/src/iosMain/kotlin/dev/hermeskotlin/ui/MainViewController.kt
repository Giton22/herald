package dev.hermeskotlin.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.window.ComposeUIViewController
import dev.hermeskotlin.core.push.PushGateway
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushRegistration
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.voice.IosVoiceKeepAlive
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import dev.hermeskotlin.ui.di.sharedModules
import dev.hermeskotlin.ui.platform.IosAppLock
import dev.hermeskotlin.ui.platform.IosChatNotifier
import dev.hermeskotlin.ui.platform.IosLinks
import dev.hermeskotlin.ui.platform.IosWidgets
import dev.hermeskotlin.ui.platform.SafariUriHandler
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import platform.Foundation.NSBundle
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController

/** Starts the shared code. The Swift app calls it once, before the first screen. */
fun initKoin() {
    startKoin { modules(sharedModules + iosAppModule) }
    // Before launch finishes: the notification center has to have its delegate by then, or a tap that
    // launched the app is lost.
    KoinPlatform.getKoin().get<IosChatNotifier>()
}

/**
 * The whole app as one view controller for the Swift host. [onDarkTheme] tells the host which theme is
 * showing, so it can match the status bar.
 */
fun MainViewController(onDarkTheme: (Boolean) -> Unit): UIViewController {
    val lock = KoinPlatform.getKoin().get<IosAppLock>()
    // Started with the UI: it follows the connection for the quick actions and the app for shares.
    KoinPlatform.getKoin().get<IosLinks>()
    return ComposeUIViewController {
        val locked by lock.timer.locked.collectAsState()
        CompositionLocalProvider(LocalUriHandler provides SafariUriHandler) {
            App(appVersion = appVersion(), onDarkTheme = onDarkTheme, locked = locked, onUnlock = lock::ask)
        }
    }
}

/**
 * Opens a `hermes://` link, a quick action's link or a share the extension left, for the Swift host. False when
 * it isn't one Herald knows.
 */
fun openLink(url: String): Boolean = KoinPlatform.getKoin().get<IosLinks>().open(url)

/** Siri's and Shortcuts' "Ask Herald": a new chat with [text] in the composer, or dictating without it. */
fun askHerald(text: String?) = KoinPlatform.getKoin().get<IosLinks>().ask(text)

/** What the Android app module provides, as iOS has it. */
internal val iosAppModule = module {
    single { IosAppLock(get(), get()) }
    single { IosLinks(get(), get(), get(), get(), get()) }
    single { IosChatNotifier(get(), get(), links = { get<IosLinks>().open(it) }, scope = get()) }
    single(createdAtStart = true) { IosWidgets(get(), get(), get(), get()) }
    single<VoiceKeepAlive> { IosVoiceKeepAlive() }
    single<PushKeys> { NoPushKeys }
    single { PushSetup(get(), get(), get(), get(), get(), get(), deviceName = { UIDevice.currentDevice.name }) }
}

private fun appVersion(): String? = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String

/** No push identity on iOS yet: turning on "Notifications anywhere" says why, without touching the gateway. */
private object NoPushKeys : PushKeys {
    override val deviceId = ""

    override val unavailable = "Notifications anywhere aren't available on iOS yet."

    override fun registration(name: String): PushRegistration = error("Notifications anywhere aren't available on iOS yet.")

    override fun pinned(gatewayUrl: String): PushGateway? = null

    override fun accepts(keys: PushGateway) = false

    override fun pin(gatewayUrl: String, keys: PushGateway) = Unit

    override fun retire(gatewayUrl: String) = Unit

    override fun retired(gatewayUrl: String): Set<String> = emptySet()

    override fun forgetRetired(gatewayUrl: String, deviceId: String) = Unit

    override fun pinnedGatewayUrl(): String? = null
}
