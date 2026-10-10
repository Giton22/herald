package dev.hermeskotlin.ui

import androidx.compose.ui.window.ComposeUIViewController
import dev.hermeskotlin.core.push.PushGateway
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushRegistration
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import dev.hermeskotlin.ui.di.sharedModules
import org.koin.core.context.startKoin
import org.koin.dsl.module
import platform.Foundation.NSBundle
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController

/** Starts the shared code. The Swift app calls it once, before the first screen. */
fun initKoin() {
    startKoin { modules(sharedModules + iosAppModule) }
}

/**
 * The whole app as one view controller for the Swift host. [onDarkTheme] tells the host which theme is
 * showing, so it can match the status bar.
 */
fun MainViewController(onDarkTheme: (Boolean) -> Unit): UIViewController = ComposeUIViewController {
    App(appVersion = appVersion(), onDarkTheme = onDarkTheme)
}

/** What the Android app module provides, as iOS has it. */
private val iosAppModule = module {
    single<VoiceKeepAlive> { NoKeepAlive }
    single<PushKeys> { NoPushKeys }
    single { PushSetup(get(), get(), get(), get(), get(), get(), deviceName = { UIDevice.currentDevice.name }) }
}

private fun appVersion(): String? = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String

/** A voice chat ends when the app leaves the screen. */
private object NoKeepAlive : VoiceKeepAlive {
    override fun hold(onEnd: () -> Unit, onLost: () -> Unit) = false

    override fun release() = Unit
}

/** No push identity on iOS yet: turning on "Notifications anywhere" says why it can't. */
private object NoPushKeys : PushKeys {
    override val deviceId = ""

    override fun registration(name: String): PushRegistration = error("Notifications anywhere aren't available on iOS yet.")

    override fun pinned(gatewayUrl: String): PushGateway? = null

    override fun accepts(keys: PushGateway) = false

    override fun pin(gatewayUrl: String, keys: PushGateway) = Unit

    override fun retire(gatewayUrl: String) = Unit

    override fun retired(gatewayUrl: String): Set<String> = emptySet()

    override fun forgetRetired(gatewayUrl: String, deviceId: String) = Unit

    override fun pinnedGatewayUrl(): String? = null
}
