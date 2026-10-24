package dev.hermeskotlin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

@Composable
actual fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        // Off, it leaves the view's flag alone, so it never turns off what something else keeps on.
        if (!on) return@DisposableEffect onDispose {}
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
