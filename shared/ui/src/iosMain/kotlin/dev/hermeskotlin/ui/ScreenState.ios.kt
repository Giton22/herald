package dev.hermeskotlin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication

/** iOS doesn't rebuild a screen when the phone turns or the appearance changes. */
@Composable
actual fun rememberChangingConfigurations(): () -> Boolean = { false }

@Composable
actual fun KeepScreenOn(on: Boolean) {
    DisposableEffect(on) {
        if (on) UIApplication.sharedApplication.idleTimerDisabled = true
        onDispose { if (on) UIApplication.sharedApplication.idleTimerDisabled = false }
    }
}
