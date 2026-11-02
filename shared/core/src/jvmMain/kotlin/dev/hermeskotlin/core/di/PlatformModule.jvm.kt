package dev.hermeskotlin.core.di

import dev.hermeskotlin.core.auth.LoopbackHttpReceiver
import dev.hermeskotlin.core.auth.LoopbackReceiver
import dev.hermeskotlin.core.cache.KeystoreSealer
import dev.hermeskotlin.core.cache.Sealer
import dev.hermeskotlin.core.cache.offlineDatabase
import dev.hermeskotlin.core.settings.WallpaperStore
import dev.hermeskotlin.core.storage.FileBlobFile
import dev.hermeskotlin.core.storage.FileKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.core.storage.appDataDir
import dev.hermeskotlin.core.voice.DeviceDictation
import dev.hermeskotlin.core.voice.LiveCalls
import dev.hermeskotlin.core.voice.NoDeviceDictation
import dev.hermeskotlin.core.voice.NoSpeechPlayer
import dev.hermeskotlin.core.voice.NoVoiceRecorder
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.VoiceRecorder
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File

actual val platformModule: Module = module {
    single<KeyValueStore> { FileKeyValueStore(File(appDataDir, "settings.json")) }
    // No app link to send the browser back to: its tab says it's done.
    single<LoopbackReceiver> { LoopbackHttpReceiver(returnUri = null) }
    single { WallpaperStore(FileBlobFile(File(appDataDir, "wallpaper.jpg")), get()) }
    // No voice on the desktop yet; a voice chat has no live mode to fall back from.
    single<VoiceRecorder> { NoVoiceRecorder }
    single<SpeechPlayer> { NoSpeechPlayer }
    single<DeviceDictation> { NoDeviceDictation }
    single { LiveCalls { null } }
    single { offlineDatabase(appDataDir) }
    single<Sealer> { KeystoreSealer(get()) }
}
