package dev.hermeskotlin.wear.assist

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Makes Herald selectable as the watch's digital assistant (Settings → Digital assistant app). Once picked, the
 * watch's assist gesture opens [HeraldWearAssistSession] over the watch face: Herald's own listening screen.
 * Nothing runs here — Android keeps this service bound while Herald is the assistant, and it has nothing to
 * listen for (hotwords need a preinstalled assistant; `MANAGE_HOTWORD_DETECTION` is internal on Wear OS).
 */
class HeraldWearAssistService : VoiceInteractionService()

class HeraldWearAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = HeraldWearAssistSession(this)
}

/**
 * Android only accepts an assistant that names a speech recognizer of its own. Herald records on the watch and
 * the gateway transcribes, or the watch's own recognizer does it inside Herald, so this one turns other apps away.
 */
class HeraldWearRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
    }

    override fun onCancel(listener: Callback) = Unit

    override fun onStopListening(listener: Callback) = Unit
}
