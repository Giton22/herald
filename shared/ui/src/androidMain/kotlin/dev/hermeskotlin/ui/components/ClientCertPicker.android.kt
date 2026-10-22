package dev.hermeskotlin.ui.components

import android.app.Activity
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
                val activity = context as? Activity ?: return
                KeyChain.choosePrivateKeyAlias(activity, { alias -> picked(alias) }, null, null, host, port, null)
            }
        }
    }
}
