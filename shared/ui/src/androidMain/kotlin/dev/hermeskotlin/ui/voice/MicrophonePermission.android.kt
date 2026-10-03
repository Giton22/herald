package dev.hermeskotlin.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
actual fun rememberMicrophonePermission(): MicrophonePermission {
    val context = LocalContext.current
    // The callbacks of the request in flight; one dialog shows at a time.
    val pending = remember { arrayOfNulls<Pair<() -> Unit, () -> Unit>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val (onDenied, onGranted) = pending[0] ?: return@rememberLauncherForActivityResult
        pending[0] = null
        if (granted) onGranted() else onDenied()
    }
    return remember(launcher) {
        MicrophonePermission { onDenied, onGranted ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                onGranted()
            } else {
                pending[0] = onDenied to onGranted
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}
