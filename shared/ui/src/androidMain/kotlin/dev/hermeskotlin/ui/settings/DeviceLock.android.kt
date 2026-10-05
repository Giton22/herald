package dev.hermeskotlin.ui.settings

import android.app.KeyguardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun deviceHasScreenLock(): Boolean {
    // Read on every draw: the user may have just come back from setting one up.
    val keyguard = LocalContext.current.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    return keyguard?.isDeviceSecure == true
}
