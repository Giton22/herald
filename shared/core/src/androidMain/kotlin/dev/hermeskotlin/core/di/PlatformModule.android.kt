package dev.hermeskotlin.core.di

import dev.hermeskotlin.core.storage.EncryptedKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<KeyValueStore> { EncryptedKeyValueStore(androidContext()) }
}
