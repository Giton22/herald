package dev.hermeskotlin.designsystem

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

// The app bundle carries the same .ttf files as the Android resources (the Xcode project points at them).

internal actual val Geist: FontFamily = bundledFamily(
    "geist_regular" to FontWeight.Normal,
    "geist_medium" to FontWeight.Medium,
    "geist_semibold" to FontWeight.SemiBold,
)

internal actual val GeistMono: FontFamily = bundledFamily(
    "geist_mono_regular" to FontWeight.Normal,
    "geist_mono_medium" to FontWeight.Medium,
)

/** The bundle's fonts by file name; the system font if the bundle has none of them (tests, previews). */
private fun bundledFamily(vararg files: Pair<String, FontWeight>): FontFamily {
    val fonts = files.mapNotNull { (name, weight) -> bundleBytes(name)?.let { Font(name, it, weight) } }
    return if (fonts.isEmpty()) FontFamily.Default else FontFamily(fonts)
}

@OptIn(ExperimentalForeignApi::class)
private fun bundleBytes(name: String): ByteArray? {
    val path = NSBundle.mainBundle.pathForResource(name, "ttf") ?: return null
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    val size = data.length.toInt()
    if (size == 0) return null
    return ByteArray(size).also { bytes -> bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }
}
