package dev.hermeskotlin.ui.voice

import androidx.compose.runtime.Composable

/** Asks for the microphone before voice input starts. */
fun interface MicrophonePermission {
    /** Runs [onGranted] once the app may record, asking first if needed; [onDenied] otherwise. */
    fun withMicrophone(onDenied: () -> Unit, onGranted: () -> Unit)
}

@Composable
expect fun rememberMicrophonePermission(): MicrophonePermission
