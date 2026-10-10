package dev.hermeskotlin.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.AVFAudio.AVAudioApplication
import platform.AVFAudio.AVAudioApplicationRecordPermissionDenied
import platform.AVFAudio.AVAudioApplicationRecordPermissionGranted
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberMicrophonePermission(): MicrophonePermission = remember {
    MicrophonePermission { onDenied, onGranted ->
        when (AVAudioApplication.sharedInstance.recordPermission) {
            AVAudioApplicationRecordPermissionGranted -> onGranted()
            AVAudioApplicationRecordPermissionDenied -> onDenied()
            // Undetermined: the system asks once, and answers off the main thread.
            else -> AVAudioApplication.requestRecordPermissionWithCompletionHandler { granted ->
                dispatch_async(dispatch_get_main_queue()) { if (granted) onGranted() else onDenied() }
            }
        }
    }
}
