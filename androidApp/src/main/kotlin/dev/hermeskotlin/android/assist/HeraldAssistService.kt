package dev.hermeskotlin.android.assist

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Makes Herald selectable as the phone's digital assistant (Settings → Default apps → Digital assistant
 * app). Once picked, the assist gesture — holding the power or home button, or swiping up from a bottom
 * corner — opens [HeraldAssistSession] over whatever is on screen. Nothing runs here: Android keeps this
 * service bound while Herald is the assistant, and it has nothing to listen for (hotwords are for
 * preinstalled assistants).
 */
class HeraldAssistService : VoiceInteractionService()

class HeraldAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = HeraldAssistSession(this)
}

/**
 * Android only accepts an assistant that names a speech recognizer of its own. Herald's dictation goes
 * through the gateway from inside the panel, so this one turns every other app away.
 */
class HeraldRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
    }

    override fun onCancel(listener: Callback) = Unit

    override fun onStopListening(listener: Callback) = Unit
}
