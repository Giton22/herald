package dev.hermeskotlin.designsystem.components

import androidx.compose.ui.platform.ClipEntry

/** A plain-text clipboard entry; building one is platform specific. */
expect fun plainTextClipEntry(text: String): ClipEntry
