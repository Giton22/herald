package dev.hermeskotlin.core.storage

import java.io.File

/** The desktop app's own folder: %APPDATA%\Herald, ~/Library/Application Support/Herald, or $XDG_DATA_HOME/herald. */
val appDataDir: File by lazy {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = System.getProperty("user.home")
    val dir = when {
        os.startsWith("windows") -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "Herald")
        os.startsWith("mac") -> File(home, "Library/Application Support/Herald")
        else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "herald")
    }
    dir.apply { mkdirs() }
}
