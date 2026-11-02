package dev.hermeskotlin.core.update

// A desktop takes its own installer, never an APK.
internal actual fun deviceAbis(): List<String> = emptyList()

/** What release.yml builds for each system: an MSI for Windows, a DMG for macOS, a DEB for Linux. */
internal actual fun desktopInstaller(): String? {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.startsWith("windows") -> ".msi"
        os.startsWith("mac") -> ".dmg"
        else -> ".deb"
    }
}
