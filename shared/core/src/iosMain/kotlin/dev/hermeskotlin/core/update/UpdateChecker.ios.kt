package dev.hermeskotlin.core.update

// The App Store updates the app; there are no per-ABI downloads to pick from.
internal actual fun deviceAbis(): List<String> = emptyList()
