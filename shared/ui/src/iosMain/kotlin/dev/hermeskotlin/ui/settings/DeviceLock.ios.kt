package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun deviceHasScreenLock(): Boolean =
    // Read on every draw: the user may have just come back from setting a passcode.
    LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)
