package dev.hermeskotlin.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberChangingConfigurations(): () -> Boolean {
    val activity = LocalContext.current.findActivity()
    return remember(activity) { { activity?.isChangingConfigurations == true } }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
