package dev.hermeskotlin.android.assist

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Asks for the microphone on the assistant's behalf: the panel is a window, not an activity, so it
 * can't show Android's prompt, and the prompt would open behind it anyway. Invisible; the panel steps
 * aside while it asks and comes back listening once the answer is yes. When Android no longer shows the
 * prompt (refused twice), Herald's page in system settings is the only place left to allow it.
 */
class MicrophonePermissionActivity : ComponentActivity() {

    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> HeraldAssistService.reopen()
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) ask.launch(Manifest.permission.RECORD_AUDIO)
    }
}
