package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberMediaActions(): MediaActions = remember {
    object : MediaActions {
        override suspend fun save(bytes: ByteArray, name: String) = "Saving isn't available on iOS yet."

        override fun share(bytes: ByteArray, name: String) = Unit
    }
}
