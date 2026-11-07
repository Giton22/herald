package dev.hermeskotlin.ui.components

import androidx.compose.runtime.Composable

/** The platform certificate picker behind the client-certificate rows. Results arrive in [rememberClientCertPicker]'s callback. */
interface ClientCertPicker {
    val available: Boolean get() = true
    fun pick()
}

/**
 * Remembers the picker for [host]:[port]. Android selects a system-store alias; secure Linux builds import
 * an encrypted PKCS#12 identity selected by hostname, like Android. A cancelled picker does not replace a selection.
 */
@Composable
expect fun rememberClientCertPicker(
    host: String?,
    port: Int,
    onPicked: (String?) -> Unit,
): ClientCertPicker

/** Whether this build can select or securely import a client identity. */
@Composable
expect fun clientCertificatesAvailable(): Boolean
