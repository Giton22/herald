package dev.hermeskotlin.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/** Whether the on-screen keyboard is showing. */
internal expect val imeVisible: Boolean
    @Composable get

/** A dialog that covers the whole window, under the system bars too (the media viewer). */
internal expect fun fullScreenDialogProperties(): DialogProperties
