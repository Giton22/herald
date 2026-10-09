package dev.hermeskotlin.core.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Dictation with the phone's own [SpeechRecognizer]. Recognizers are tried in turn — the on-device one
 * where Android has it, then the installed recognition services, Google's app first — and the next one
 * takes over when one can't start (its language pack is missing, say). Never Herald's own: as the
 * digital assistant it becomes the system's default recognizer, and that one only turns requests away.
 */
class AndroidDeviceDictation(private val context: Context) : DeviceDictation {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var active: SpeechRecognizer? = null

    /** [finish] came while no recognizer was listening (between two of them): the next one doesn't start. */
    @Volatile private var finishRequested = false

    override fun available(): Boolean = onDevice() || services().isNotEmpty()

    override suspend fun listen(onPartial: (String) -> Unit, onLevel: (Float) -> Unit): String = withContext(Dispatchers.Main) {
        finishRequested = false
        val recognizers = buildList<() -> SpeechRecognizer> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                add { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
            }
            services().forEach { component -> add { SpeechRecognizer.createSpeechRecognizer(context, component) } }
        }
        for (create in recognizers) {
            // Stopped before anything was heard.
            if (finishRequested) return@withContext ""
            val recognizer = try {
                create()
            } catch (_: RuntimeException) {
                continue
            }
            try {
                return@withContext listenWith(recognizer, onPartial, onLevel)
            } catch (_: CantStart) {
                // Nothing was heard yet: the next recognizer may manage.
            }
        }
        if (finishRequested) "" else throw DeviceDictationUnavailable()
    }

    override fun finish() {
        finishRequested = true
        // The recognizer lives on the main thread; this may be called from anywhere.
        val recognizer = active ?: return
        main.post { if (active === recognizer) recognizer.stopListening() }
    }

    private suspend fun listenWith(recognizer: SpeechRecognizer, onPartial: (String) -> Unit, onLevel: (Float) -> Unit): String =
        try {
            active = recognizer
            suspendCancellableCoroutine { continuation ->
                var heard = ""
                var started = false
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() {
                        started = true
                    }
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        if (continuation.isActive) onLevel(0f)
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onRmsChanged(rmsdB: Float) {
                        // Recognizers report roughly -2..10 dB.
                        if (continuation.isActive) onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        // A cancelled dictation is over, even when the recognizer still had words queued.
                        if (!continuation.isActive) return
                        val text = partialResults.bestText() ?: return
                        if (text.isNotBlank()) {
                            heard = text
                            onPartial(text)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        if (continuation.isActive) continuation.resume(results.bestText()?.takeIf { it.isNotBlank() } ?: heard)
                    }

                    override fun onError(error: Int) {
                        if (!continuation.isActive) return
                        when {
                            // Nothing said, or nothing it could make out: what was heard so far, if anything.
                            error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> continuation.resume(heard)
                            // Failed before the speaker got going: another recognizer can take over.
                            heard.isEmpty() && !started && error in CANT_START -> continuation.resumeWithException(CantStart())
                            else -> continuation.resumeWithException(IllegalStateException(describe(error)))
                        }
                    }
                })
                try {
                    recognizer.startListening(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName),
                    )
                } catch (_: RuntimeException) {
                    continuation.resumeWithException(CantStart())
                }
            }
        } finally {
            // Also on cancellation: destroying the recognizer cancels what it was listening to.
            if (active === recognizer) active = null
            onLevel(0f)
            recognizer.destroy()
        }

    private fun onDevice(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** The installed recognition services other than Herald's, in the order to try them. */
    private fun services(): List<ComponentName> =
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo }
            .filter { it.packageName != context.packageName }
            .sortedBy { PREFERRED.indexOf(it.packageName).let { rank -> if (rank < 0) PREFERRED.size else rank } }
            .map { ComponentName(it.packageName, it.name) }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "The microphone isn't available."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The speech recognizer isn't allowed to use the microphone."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech recognizer needs a connection."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy. Try again."
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_CLIENT -> "The speech recognizer stopped. Try again."
        else -> "The speech recognizer failed ($error)."
    }

    private class CantStart : Exception()

    private companion object {
        /**
         * The Google app first; then Google's speech services. Android System Intelligence comes last: it is
         * the same engine as the on-device recognizer, so it fails the same way when that one did.
         */
        val PREFERRED = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts", "com.google.android.as")

        /**
         * A missing language pack or an unsupported language (ERROR_LANGUAGE_NOT_SUPPORTED, _UNAVAILABLE, Android 12),
         * a support check that failed (ERROR_CANNOT_CHECK_SUPPORT, _LISTEN_TO_DOWNLOAD_EVENTS, Android 13), a
         * recognizer that's busy or may not use the microphone, or a service that dropped the connection.
         */
        val CANT_START = setOf(
            12, 13, 14, 15,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_CLIENT,
        )

        fun Bundle?.bestText(): String? = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    }
}
