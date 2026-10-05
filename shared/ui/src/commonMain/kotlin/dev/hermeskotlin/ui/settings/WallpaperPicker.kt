package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Opens the platform's photo picker for the chat background. The chosen photo arrives in [onPicked] upright,
 * scaled to about a screen and encoded, ready to keep; [onError] gets a sentence when it can't be read.
 */
@Composable
expect fun rememberWallpaperPicker(onPicked: (ByteArray) -> Unit, onError: (String) -> Unit): () -> Unit

/** The kept chat background, decoded off the main thread: null while it decodes, or when there's none. */
@Composable
expect fun rememberWallpaperBitmap(image: ByteArray?): ImageBitmap?
