package dev.hermeskotlin.core.cache

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.hermeskotlin.core.storage.appSupportPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** The cache's database file in Application Support. It holds only a copy, so a new version starts it over. */
fun offlineDatabase(): OfflineDatabase =
    Room.databaseBuilder<OfflineDatabase>(name = appSupportPath("offline-cache.db"))
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
