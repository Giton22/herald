package dev.hermeskotlin.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// No certificate picker on iOS yet: the Connect screen leaves the client-certificate rows out (clientCertsShow).
@Composable
actual fun rememberClientCertPicker(
    host: String?,
    port: Int,
    onPicked: (String?) -> Unit,
): ClientCertPicker = remember {
    object : ClientCertPicker {
        override fun pick() = onPicked(null)
    }
}
