package dev.hermeskotlin.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import dev.hermeskotlin.core.voice.desktopSpeechDecoder
import dev.hermeskotlin.core.voice.nativeDesktopAudio

// The desktop's keyboard never goes away, so a focused field keeps its focus.
internal actual val imeVisible: Boolean
    @Composable get() = true

internal actual fun fullScreenDialogProperties() = DialogProperties(usePlatformDefaultWidth = false)

internal actual val enterSends: Boolean = true

internal actual val macKeys: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

// Read once: the answer can't change while the app runs, and the check touches the disk.
private val desktopVoice = desktopVoiceSupported(
    System.getProperty("os.name").orEmpty(), System.getProperty("os.arch").orEmpty(), desktopSpeechDecoder(),
)

internal actual val hasVoice: Boolean get() = desktopVoice

/**
 * The same backends core's platform module picks: native audio, and a decoder for spoken replies. Without one a voice
 * chat couldn't answer aloud, and Read aloud would stay silent.
 */
internal fun desktopVoiceSupported(osName: String, osArch: String, speechDecoder: Boolean): Boolean =
    nativeDesktopAudio(osName, osArch) && speechDecoder
