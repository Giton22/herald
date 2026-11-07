package dev.hermeskotlin.core.voice

import java.io.File

private val NATIVE_AUDIO_ARCHES = setOf("amd64", "x86_64", "aarch64")

/** Linux on these CPUs has the native WebRTC audio backend: recording, playback and live calls. */
fun nativeDesktopAudio(
    osName: String = System.getProperty("os.name").orEmpty(),
    osArch: String = System.getProperty("os.arch").orEmpty(),
): Boolean = osName == "Linux" && osArch in NATIVE_AUDIO_ARCHES

/** Spoken replies decode with ffmpeg at the runtime's path: the Flatpak runtime has it, a host install may not. */
fun desktopSpeechDecoder(ffmpeg: File = File("/usr/bin/ffmpeg")): Boolean = ffmpeg.canExecute()

/** No microphone on the desktop yet: voice input says so instead of recording. */
object NoVoiceRecorder : VoiceRecorder {
    override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording =
        throw UnsupportedOperationException("Voice input isn't on the desktop yet.")

    override fun finish() = Unit
}

/** No speaker on the desktop yet: replies aren't read aloud. */
object NoSpeechPlayer : SpeechPlayer {
    override suspend fun play(audio: SpokenAudio) = Unit
}
