package dev.hermeskotlin.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// No certificate picker on iOS yet: unavailable, so the Connect screen leaves the client-certificate rows out.
@Composable
actual fun clientCertificatesAvailable(): Boolean = false

@Composable
actual fun rememberClientCertPicker(
    host: String?,
    port: Int,
    onPicked: (String?) -> Unit,
): ClientCertPicker = remember {
    object : ClientCertPicker {
        override val available = false

        override fun pick() = Unit
    }
}
