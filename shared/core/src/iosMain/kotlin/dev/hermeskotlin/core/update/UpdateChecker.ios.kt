package dev.hermeskotlin.core.update

// The App Store updates the app; there are no per-ABI downloads to pick from.
internal actual fun deviceAbis(): List<String> = emptyList()

// Not the desktop: no installer to pick.
internal actual fun desktopInstaller(): String? = null
