package dev.hermeskotlin.ui

import dev.hermeskotlin.core.voice.nativeDesktopAudio
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopVoiceTest {
    @Test fun onlySupportedLinuxHostsAdvertiseTheImplementedVoiceBackend() {
        for (arch in listOf("amd64", "x86_64", "aarch64")) assertTrue(nativeDesktopAudio("Linux", arch))
        for (os in listOf("Windows 11", "Mac OS X", "FreeBSD", "")) assertFalse(nativeDesktopAudio(os, "amd64"))
        for (arch in listOf("x86", "armv7", "")) assertFalse(nativeDesktopAudio("Linux", arch))
    }

    @Test fun spokenRepliesAlsoNeedTheSpeechDecoder() {
        assertTrue(desktopSpeechPlayback(nativeAudio = true, speechDecoder = true))
        // A .deb on a system without /usr/bin/ffmpeg keeps dictation and calls, but nothing that would speak.
        assertFalse(desktopSpeechPlayback(nativeAudio = true, speechDecoder = false))
        assertFalse(desktopSpeechPlayback(nativeAudio = false, speechDecoder = true))
    }
}
