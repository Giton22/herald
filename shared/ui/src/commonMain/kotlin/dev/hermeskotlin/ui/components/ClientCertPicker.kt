package dev.hermeskotlin.ui.components

import androidx.compose.runtime.Composable

/** The system certificate picker behind the client-certificate rows. Results arrive in [rememberClientCertPicker]'s callback. */
interface ClientCertPicker {
    fun pick()
}

/**
 * Remembers the system picker for a client certificate for [host]:[port], which only hints the picker at
 * which certificate to offer. [onPicked] gets the chosen system-store alias, or null when it is dismissed.
 */
@Composable
expect fun rememberClientCertPicker(
    host: String?,
    port: Int,
    onPicked: (String?) -> Unit,
): ClientCertPicker
