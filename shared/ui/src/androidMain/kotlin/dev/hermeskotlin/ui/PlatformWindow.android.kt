package dev.hermeskotlin.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalLayoutApi::class)
internal actual val imeVisible: Boolean
    @Composable get() = WindowInsets.isImeVisible

internal actual fun fullScreenDialogProperties() = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
