package dev.hermeskotlin.android

import android.app.KeyguardManager
import android.os.Build
import android.view.WindowManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.hermeskotlin.core.settings.AppLockTimer

/**
 * App lock's Android side for [activity]: asks for a fingerprint, face or the screen lock through
 * BiometricPrompt, and keeps the app's content out of Recents while the lock is on.
 */
class AppLockGate(private val activity: FragmentActivity, private val timer: AppLockTimer, private val onUnlocked: () -> Unit) {

    private var asking = false

    /** Shows the system's unlock prompt, unless it's already up. */
    fun ask() {
        if (!timer.locked.value || asking) return
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure != true) {
            // The screen lock was removed since: there's nothing left to unlock with, so don't lock the user out.
            unlock()
            return
        }
        asking = true
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                asking = false
                unlock()
            }

            // Cancelled or too many tries: the cover stays, with its Unlock button to ask again.
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                asking = false
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Herald")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }

    /** Hides the app's content in Recents while [on]. */
    fun hideFromRecents(on: Boolean) {
        // Not setRecentsScreenshotEnabled: it only drops the stored snapshot, and Recents still shows the app
        // live while swiping up from it. FLAG_SECURE blanks both, and also blocks screenshots of the app.
        if (on) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun unlock() {
        timer.onUnlocked()
        onUnlocked()
    }

    private companion object {
        /** A strong biometric or the screen lock; Android 9 and 10 only pair the screen lock with weak ones. */
        val AUTHENTICATORS = if (Build.VERSION.SDK_INT >= 30) {
            Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        } else {
            Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
        }
    }
}
