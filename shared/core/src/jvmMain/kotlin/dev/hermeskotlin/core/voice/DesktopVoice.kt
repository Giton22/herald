package dev.hermeskotlin.core.voice

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
