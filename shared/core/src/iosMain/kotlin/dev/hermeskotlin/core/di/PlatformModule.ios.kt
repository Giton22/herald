package dev.hermeskotlin.core.di

import dev.hermeskotlin.core.cache.offlineDatabase
import dev.hermeskotlin.core.settings.WallpaperStore
import dev.hermeskotlin.core.storage.IosBlobFile
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.core.storage.KeychainKeyValueStore
import dev.hermeskotlin.core.storage.appSupportPath
import dev.hermeskotlin.core.voice.LiveCalls
import org.koin.core.module.Module
import org.koin.dsl.module

/** The Keychain service that holds the app's secrets. */
const val KEYCHAIN_SERVICE = "dev.herald.ios"

actual val platformModule: Module = module {
    single<KeyValueStore> { KeychainKeyValueStore(KEYCHAIN_SERVICE) }
    single { WallpaperStore(IosBlobFile(appSupportPath("wallpaper.jpg")), get()) }
    // No WebRTC on iOS yet: a voice chat falls back to the chained mode (record, transcribe, speak).
    single { LiveCalls { null } }
    single { offlineDatabase() }
}
