package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.inbox.InboxApi
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.PhoneToolException
import dev.hermeskotlin.core.phone.PhoneToolRunner
import dev.hermeskotlin.core.phone.PhoneToolsBridge
import dev.hermeskotlin.core.push.PushGateway
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushRegistration
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import dev.hermeskotlin.desktop.web.Jcef
import dev.hermeskotlin.desktop.web.JcefWeb
import dev.hermeskotlin.ui.DesktopWeb
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.koin.dsl.module
import java.net.InetAddress

/** What the Android app adds to the shared modules, as far as the desktop needs it. */
val desktopModule = module {
    // A desktop app isn't stopped in the background: a voice chat needs nothing to keep it up.
    single<VoiceKeepAlive> {
        object : VoiceKeepAlive {
            override fun hold(onEnd: () -> Unit, onLost: () -> Unit) = true

            override fun release() = Unit
        }
    }
    single<PushKeys> { NoPushKeys }
    single { PushSetup(get(), get(), get(), get(), get(), get(), deviceName = ::computerName) }
    single { InboxApi(get(), get()) }
    single<PhoneToolRunner> { NoPhoneTools }
    single { PhoneToolsBridge(get(), get(), get(), get(), get(), get(), get(), get(), deviceName = ::computerName) }
    // Widgets and plugin pages in the app, where the runtime carries Chromium (the installer's does).
    if (Jcef.available) single<DesktopWeb> { JcefWeb(get(), get()) }
}

private fun computerName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "Desktop"

/**
 * No notifications on the desktop yet, so no identity to register with the gateway: asking for one says so,
 * and nothing is ever pinned.
 */
private object NoPushKeys : PushKeys {
    override val deviceId = "desktop"

    override fun registration(name: String): PushRegistration =
        throw UnsupportedOperationException("Notifications aren't in the desktop app yet.")

    override fun pinned(gatewayUrl: String): PushGateway? = null

    override fun accepts(keys: PushGateway) = false

    override fun pin(gatewayUrl: String, keys: PushGateway) = Unit

    override fun retire(gatewayUrl: String) = Unit

    override fun retired(gatewayUrl: String): Set<String> = emptySet()

    override fun forgetRetired(gatewayUrl: String, deviceId: String) = Unit

    override fun pinnedGatewayUrl(): String? = null
}

/** The phone tools are the phone's. */
private object NoPhoneTools : PhoneToolRunner {
    override suspend fun run(tool: PhoneTool, args: JsonObject, session: String): JsonElement =
        throw PhoneToolException("${tool.title} needs Kyllene on an Android phone.")
}
