package dev.hermeskotlin.core.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioPCMBuffer
import platform.Foundation.NSError
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dictation with Apple's speech recognizer, on the phone itself where the language allows it. The
 * recognizer doesn't stop on its own when the speaker does, so the input level goes through the same
 * [EndOfSpeech] as a recording, and the audio is closed when it says the speaker is done.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDeviceDictation : DeviceDictation {

    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var finishRequested = false

    override fun available(): Boolean = SFSpeechRecognizer()?.isAvailable() == true

    override suspend fun listen(onPartial: (String) -> Unit, onLevel: (Float) -> Unit): String {
        finishRequested = false
        if (authorization() != SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
            throw DeviceDictationUnavailable()
        }
        val recognizer = SFSpeechRecognizer()?.takeIf { it.isAvailable() } ?: throw DeviceDictationUnavailable()
        return VoiceAudioSession.use { withContext(Dispatchers.Main) { listenWith(recognizer, onPartial, onLevel) } }
    }

    override fun finish() {
        finishRequested = true
        request?.endAudio()
    }

    private suspend fun listenWith(recognizer: SFSpeechRecognizer, onPartial: (String) -> Unit, onLevel: (Float) -> Unit): String {
        val request = SFSpeechAudioBufferRecognitionRequest().apply {
            shouldReportPartialResults = true
            // Never off the phone when it can be helped.
            if (recognizer.supportsOnDeviceRecognition) requiresOnDeviceRecognition = true
        }
        val engine = AVAudioEngine()
        val input = engine.inputNode
        val format = input.outputFormatForBus(0u)
        if (format.sampleRate <= 0.0) throw DeviceDictationUnavailable()
        val endOfSpeech = EndOfSpeech(VoiceActivity())
        var frames = 0L
        var ended = false
        input.installTapOnBus(0u, TAP_FRAMES, format) { buffer, _ ->
            if (buffer == null || ended) return@installTapOnBus
            request.appendAudioPCMBuffer(buffer)
            val level = level(buffer)
            onLevel(level)
            frames += buffer.frameLength.toLong()
            if (endOfSpeech.onFrame(level, frames * 1000 / format.sampleRate.toLong())) {
                ended = true
                request.endAudio()
            }
        }
        var task: SFSpeechRecognitionTask? = null
        try {
            engine.prepare()
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                if (!engine.startAndReturnError(error.ptr)) throw DeviceDictationUnavailable()
            }
            this.request = request
            // Stopped before it started listening.
            if (finishRequested) request.endAudio()
            return suspendCancellableCoroutine { continuation ->
                var heard = ""
                task = recognizer.recognitionTaskWithRequest(request) { result, error ->
                    if (!continuation.isActive) return@recognitionTaskWithRequest
                    result?.bestTranscription?.formattedString?.takeIf { it.isNotBlank() }?.let {
                        heard = it
                        onPartial(it)
                    }
                    when {
                        result?.isFinal() == true -> continuation.resume(heard)
                        // Nothing said, or nothing it could make out: what was heard so far, if anything.
                        error != null && (heard.isNotEmpty() || error.code in NO_SPEECH) -> continuation.resume(heard)
                        error != null -> continuation.resumeWithException(IllegalStateException("The speech recognizer failed: ${error.localizedDescription}"))
                    }
                }
            }
        } finally {
            ended = true
            this.request = null
            input.removeTapOnBus(0u)
            engine.stop()
            task?.cancel()
            onLevel(0f)
        }
    }

    private suspend fun authorization(): SFSpeechRecognizerAuthorizationStatus {
        val now = SFSpeechRecognizer.authorizationStatus()
        if (now != SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusNotDetermined) return now
        return suspendCancellableCoroutine { continuation ->
            SFSpeechRecognizer.requestAuthorization { status -> if (continuation.isActive) continuation.resume(status) }
        }
    }

    /** The buffer's RMS, scaled like the recorder's level so the same thresholds hold. */
    private fun level(buffer: AVAudioPCMBuffer): Float {
        val samples = buffer.floatChannelData?.get(0) ?: return 0f
        val count = buffer.frameLength.toInt()
        if (count == 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return min(1.0, sqrt(sum / count) * 32_768.0 / (256.0 * 42.0)).toFloat()
    }

    private companion object {
        const val TAP_FRAMES = 1024u

        /** The recognizer's "no speech detected" and "retry" (kAFAssistantErrorDomain 1110, 203). */
        val NO_SPEECH = setOf(1110L, 203L)
    }
}
