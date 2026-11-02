package dev.hermeskotlin.android.phone

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri

/** Health Connect's "privacy policy" link for Herald: the README's Privacy section, in the browser. Invisible. */
class HealthPrivacyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, PRIVACY.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    private companion object {
        const val PRIVACY = "https://github.com/Giton22/kyllene#privacy"
    }
}
