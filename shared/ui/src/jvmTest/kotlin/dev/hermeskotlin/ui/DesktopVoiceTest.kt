package dev.hermeskotlin.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopVoiceTest {
    @Test fun onlySupportedLinuxHostsAdvertiseTheImplementedVoiceBackend() {
        for (arch in listOf("amd64", "x86_64", "aarch64")) assertTrue(desktopVoiceSupported("Linux", arch, speechDecoder = true))
        for (os in listOf("Windows 11", "Mac OS X", "FreeBSD", "")) assertFalse(desktopVoiceSupported(os, "amd64", speechDecoder = true))
        for (arch in listOf("x86", "armv7", "")) assertFalse(desktopVoiceSupported("Linux", arch, speechDecoder = true))
    }

    @Test fun aHostWithoutTheSpeechDecoderHidesVoice() {
        // A .deb on a system without /usr/bin/ffmpeg gets no speech player, so no controls that would need one.
        assertFalse(desktopVoiceSupported("Linux", "amd64", speechDecoder = false))
    }
}
