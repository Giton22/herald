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
import dev.hermeskotlin.core.voice.LiveCalls
import dev.hermeskotlin.core.voice.NoDeviceDictation
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
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
    factory<VoiceRecorder> { NoVoiceRecorder }
    single<SpeechPlayer> { NoSpeechPlayer }
    factory<DeviceDictation> { NoDeviceDictation }
    // No WebRTC on iOS yet: a voice chat falls back to the chained mode (record, transcribe, speak).
    single { LiveCalls { null } }
    single { offlineDatabase() }
    single<Sealer> { KeychainSealer(get()) }
}

private object NoVoiceRecorder : VoiceRecorder {
    override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording =
        error("Voice input isn't available on iOS yet.")

    override fun finish() = Unit
}

private object NoSpeechPlayer : SpeechPlayer {
    override suspend fun play(audio: SpokenAudio) = Unit
}
