package dev.hermeskotlin.android.assist

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit

/**
 * Asks for the microphone on the assistant's behalf: the panel is a window, not an activity, so it
 * can't show Android's prompt, and the prompt would open behind it anyway. Invisible; the panel steps
 * aside while it asks and comes back listening once the answer is yes. When Android no longer shows the
 * prompt (refused twice), Herald's page in system settings is the only place left to allow it.
 */
class MicrophonePermissionActivity : ComponentActivity() {

    /** Whether Android would have explained itself before this ask: true only after one refusal. */
    private var refusedBefore = false

    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val explainNow = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        when {
            granted -> {
                prefs.edit { remove(KEY_REFUSED) }
                HeraldAssistService.reopen()
            }
            explainNow -> prefs.edit { putBoolean(KEY_REFUSED, true) }
            // No explanation either way also means the prompt was just closed unanswered (back, or a tap
            // outside) the first time; only after a refusal does it mean Android stopped asking.
            refusedBefore || prefs.getBoolean(KEY_REFUSED, false) -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            refusedBefore = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            ask.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private companion object {
        const val PREFS = "assistant"
        const val KEY_REFUSED = "microphone_refused"
    }
}
