package dev.hermeskotlin.ui.voice

import androidx.compose.runtime.Composable

// The desktop asks nobody: the recorder itself says when there's no microphone to use.
@Composable
actual fun rememberMicrophonePermission(): MicrophonePermission = MicrophonePermission { _, onGranted -> onGranted() }
