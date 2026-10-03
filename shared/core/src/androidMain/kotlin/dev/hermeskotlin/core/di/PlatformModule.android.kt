package dev.hermeskotlin.core.di

import dev.hermeskotlin.core.storage.EncryptedKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.core.voice.AndroidSpeechPlayer
import dev.hermeskotlin.core.voice.AndroidVoiceRecorder
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.VoiceRecorder
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<KeyValueStore> { EncryptedKeyValueStore(androidContext()) }
    factory<VoiceRecorder> { AndroidVoiceRecorder() }
    single<SpeechPlayer> { AndroidSpeechPlayer(androidContext()) }
}
