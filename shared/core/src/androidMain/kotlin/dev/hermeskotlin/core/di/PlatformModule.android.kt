package dev.hermeskotlin.core.di

import dev.hermeskotlin.core.auth.LoopbackHttpReceiver
import dev.hermeskotlin.core.auth.LoopbackReceiver
import dev.hermeskotlin.core.cache.KeystoreSealer
import dev.hermeskotlin.core.cache.Sealer
import dev.hermeskotlin.core.cache.offlineDatabase
import dev.hermeskotlin.core.gateway.MtlsAppContext
import dev.hermeskotlin.core.settings.WallpaperStore
import dev.hermeskotlin.core.storage.FileBlobFile
import dev.hermeskotlin.core.storage.EncryptedKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.core.voice.AndroidDeviceDictation
import dev.hermeskotlin.core.voice.AndroidLiveCall
import dev.hermeskotlin.core.voice.DeviceDictation
import dev.hermeskotlin.core.voice.DeviceLock
import android.app.KeyguardManager
import dev.hermeskotlin.core.voice.AndroidSpeechPlayer
import dev.hermeskotlin.core.voice.ReadAloudVoices
import dev.hermeskotlin.core.voice.FileReadAloudVoices
import dev.hermeskotlin.core.voice.LiveCalls
import dev.hermeskotlin.core.voice.AndroidVoiceRecorder
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.VoiceRecorder
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File

actual val platformModule: Module = module {
    single<KeyValueStore> { EncryptedKeyValueStore(androidContext()) }
    // The gateway engine's KeyChain lookup needs an application context at handshake time.
    single<Unit>(createdAtStart = true) { MtlsAppContext.context = androidContext() }
    // The browser sign-in comes back through this link; the app's manifest gives it to HeraldActivity.
    single<LoopbackReceiver> { LoopbackHttpReceiver("${androidContext().packageName}:/signed-in") }
    single { WallpaperStore(FileBlobFile(File(androidContext().filesDir, "wallpaper.jpg")), get()) }
    factory<VoiceRecorder> { AndroidVoiceRecorder() }
    single<SpeechPlayer> { AndroidSpeechPlayer(androidContext()) }
    single<ReadAloudVoices> { FileReadAloudVoices(File(androidContext().cacheDir, "read-aloud")) }
    // One per screen, like the recorder: the chat and the assistant panel each stop only their own.
    factory<DeviceDictation> { AndroidDeviceDictation(androidContext()) }
    single { LiveCalls { AndroidLiveCall(androidContext()) } }
    single {
        val keyguard = androidContext().getSystemService(KeyguardManager::class.java)
        DeviceLock { keyguard?.isKeyguardLocked == true }
    }
    single { offlineDatabase(androidContext()) }
    single<Sealer> { KeystoreSealer(get()) }
}
