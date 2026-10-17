package dev.hermeskotlin.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.DialogProperties

internal actual val imeVisible: Boolean
    @Composable get() = WindowInsets.ime.getBottom(LocalDensity.current) > 0

internal actual fun fullScreenDialogProperties() = DialogProperties(usePlatformDefaultWidth = false, usePlatformInsets = false)
