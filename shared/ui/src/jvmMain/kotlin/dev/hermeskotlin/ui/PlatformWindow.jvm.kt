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

// The same backends core's platform module picks. Read once: the answer can't change while the app runs, and the
// decoder check touches the disk.
private val desktopAudio = nativeDesktopAudio()
private val desktopPlayback = desktopSpeechPlayback(desktopAudio, desktopSpeechDecoder())

internal actual val hasVoice: Boolean get() = desktopAudio

internal actual val hasSpeechPlayback: Boolean get() = desktopPlayback

/**
 * Dictation and live calls need only native audio; spoken replies also need a decoder. Without one a voice chat
 * couldn't answer aloud, and Read aloud would stay silent.
 */
internal fun desktopSpeechPlayback(nativeAudio: Boolean, speechDecoder: Boolean): Boolean = nativeAudio && speechDecoder
