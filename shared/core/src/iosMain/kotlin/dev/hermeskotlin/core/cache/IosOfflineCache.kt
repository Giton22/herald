package dev.hermeskotlin.core.cache

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.hermeskotlin.core.storage.appSupportPath
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

/**
 * The cache's database in a folder of its own in Application Support, kept out of iCloud and computer backups
 * (the Keychain key that opens its rows doesn't leave the device either). It holds only a copy, so a new
 * version starts it over.
 */
@OptIn(ExperimentalForeignApi::class)
fun offlineDatabase(): OfflineDatabase {
    val dir = appSupportPath("OfflineCache")
    NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    // On the folder, so it covers the SQLite journal files as well.
    NSURL.fileURLWithPath(dir, isDirectory = true).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    return Room.databaseBuilder<OfflineDatabase>(name = "$dir/offline-cache.db")
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}
