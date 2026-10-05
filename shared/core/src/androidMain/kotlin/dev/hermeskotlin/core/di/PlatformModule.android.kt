package dev.hermeskotlin.core.di

import dev.hermeskotlin.core.settings.WallpaperStore
import dev.hermeskotlin.core.storage.AndroidBlobFile
import dev.hermeskotlin.core.storage.EncryptedKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.core.voice.AndroidSpeechPlayer
import dev.hermeskotlin.core.voice.AndroidVoiceRecorder
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.VoiceRecorder
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File

actual val platformModule: Module = module {
    single<KeyValueStore> { EncryptedKeyValueStore(androidContext()) }
    single { WallpaperStore(AndroidBlobFile(File(androidContext().filesDir, "wallpaper.jpg")), get()) }
    factory<VoiceRecorder> { AndroidVoiceRecorder() }
    single<SpeechPlayer> { AndroidSpeechPlayer(androidContext()) }
}
