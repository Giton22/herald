package dev.hermeskotlin.core.cache

import android.content.Context
import androidx.room.Room

/** The cache's database file in the app's private storage. It holds only a copy, so a new version starts it over. */
fun offlineDatabase(context: Context): OfflineDatabase =
    Room.databaseBuilder<OfflineDatabase>(context.applicationContext, context.getDatabasePath("offline-cache.db").absolutePath)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
