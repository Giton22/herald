package dev.hermeskotlin.core.cache

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import java.io.File

/** The cache's database in the app's folder. It holds only a copy, so a new version starts it over. */
fun offlineDatabase(dir: File): OfflineDatabase =
    Room.databaseBuilder<OfflineDatabase>(name = File(dir, "offline-cache.db").absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
