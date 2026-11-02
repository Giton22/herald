package dev.hermeskotlin.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

// The desktop's keyboard never goes away, so a focused field keeps its focus.
internal actual val imeVisible: Boolean
    @Composable get() = true

internal actual fun fullScreenDialogProperties() = DialogProperties(usePlatformDefaultWidth = false)

internal actual val enterSends: Boolean = true

internal actual val macKeys: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")
