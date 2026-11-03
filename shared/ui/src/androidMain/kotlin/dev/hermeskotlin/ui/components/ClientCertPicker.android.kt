package dev.hermeskotlin.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.security.KeyChain
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberClientCertPicker(
    host: String?,
    port: Int,
    onPicked: (String?) -> Unit,
): ClientCertPicker {
    val context = LocalContext.current
    val picked by rememberUpdatedState(onPicked)
    return remember(host, port) {
        object : ClientCertPicker {
            override fun pick() {
                // The grant the picker hands out belongs to the calling activity; only it can use the key.
                // LocalContext is usually a wrapper (theme, layout), not the activity itself.
                val activity = context.findActivity() ?: return
                // The system answers on a binder thread: the callers change screen state, so hand it to the main one.
                KeyChain.choosePrivateKeyAlias(activity, { alias -> activity.runOnUiThread { picked(alias) } }, null, null, host, port, null)
            }
        }
    }
}

/** Unwraps [ContextWrapper]s (theme, layout) to the hosting [Activity], or null when there isn't one. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
