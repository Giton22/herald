package dev.hermeskotlin.core.di

import dev.hermeskotlin.core.auth.IosLoopbackReceiver
import dev.hermeskotlin.core.auth.LoopbackReceiver
import dev.hermeskotlin.core.cache.KeychainSealer
import dev.hermeskotlin.core.cache.Sealer
import dev.hermeskotlin.core.cache.offlineDatabase
import dev.hermeskotlin.core.settings.WallpaperStore
import dev.hermeskotlin.core.storage.IosBlobFile
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.core.storage.KeychainKeyValueStore
import dev.hermeskotlin.core.storage.appSupportPath
import dev.hermeskotlin.core.voice.DeviceDictation
import dev.hermeskotlin.core.voice.IosDeviceDictation
import dev.hermeskotlin.core.voice.IosSpeechPlayer
import dev.hermeskotlin.core.voice.IosVoiceRecorder
import dev.hermeskotlin.core.voice.LiveCalls
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.VoiceRecorder
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSBundle

/** The Keychain service that holds the app's secrets. */
const val KEYCHAIN_SERVICE = "dev.herald.ios"

/** The link scheme the sign-in returns on: the bundle id, which only this app can claim. */
val signInReturnScheme: String get() = NSBundle.mainBundle.bundleIdentifier ?: KEYCHAIN_SERVICE

actual val platformModule: Module = module {
    single<KeyValueStore> { KeychainKeyValueStore(KEYCHAIN_SERVICE) }
    // The in-app browser session watches for this link, which closes it once the gateway has sent the code.
    single<LoopbackReceiver> { IosLoopbackReceiver("$signInReturnScheme:/signed-in") }
    single { WallpaperStore(IosBlobFile(appSupportPath("wallpaper.jpg")), get()) }
    factory<VoiceRecorder> { IosVoiceRecorder() }
    single<SpeechPlayer> { IosSpeechPlayer() }
    factory<DeviceDictation> { IosDeviceDictation() }
    // No WebRTC on iOS yet: a voice chat falls back to the chained mode (record, transcribe, speak).
    single { LiveCalls { null } }
    single { offlineDatabase() }
    single<Sealer> { KeychainSealer(get()) }
}

