package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.platform.toByteArray
import dev.hermeskotlin.core.platform.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.setActive
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfURL
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.pow

/**
 * The app's audio session while the microphone or the speaker is in use: play-and-record, out of the
 * loudspeaker (or a headset), the way a voice assistant sounds. Held by count, so recording right after
 * playing doesn't drop it; given up when the last user is done, so other apps' audio comes back.
 */
@OptIn(ExperimentalForeignApi::class)
internal object VoiceAudioSession {
    private var users = 0

    suspend fun <T> use(block: suspend () -> T): T {
        withContext(Dispatchers.Main) { acquire() }
        try {
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { release() }
        }
    }

    private fun acquire() {
        if (users++ > 0) return
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val session = AVAudioSession.sharedInstance()
            val ready = session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                AVAudioSessionModeDefault,
                AVAudioSessionCategoryOptionDefaultToSpeaker or AVAudioSessionCategoryOptionAllowBluetooth,
                error.ptr,
            ) && session.setActive(true, error.ptr)
            if (!ready) {
                users--
                error("The microphone isn't available: ${error.value?.localizedDescription ?: "audio session refused"}.")
            }
        }
    }

    private fun release() {
        if (--users > 0) return
        AVAudioSession.sharedInstance().setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
    }
}

/**
 * Records 16 kHz mono 16-bit PCM with [AVAudioRecorder] and hands it over as WAV, like Android. The level
 * is the recorder's metered average power, scaled the way Android scales its RMS, so the same
 * [EndOfSpeech] thresholds hold.
 */
@OptIn(ExperimentalForeignApi::class)
class IosVoiceRecorder : VoiceRecorder {

    private var finishRequested = false

    override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording = VoiceAudioSession.use {
        finishRequested = false
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + "herald-voice-${NSUUID().UUIDString}.wav")
        val recorder = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            AVAudioRecorder(url, SETTINGS, error.ptr).takeIf { it.prepareToRecord() }
        } ?: error("The microphone isn't available.")
        val endOfSpeech = EndOfSpeech(activity)
        try {
            recorder.meteringEnabled = true
            check(recorder.record()) { "The microphone isn't available." }
            while (!finishRequested) {
                delay(FRAME_MS)
                currentCoroutineContext().ensureActive()
                recorder.updateMeters()
                // dBFS to a linear amplitude; Android's 16-bit RMS / 256 / 42 is the same amplitude × 32768 / 10752.
                val amplitude = 10.0.pow(recorder.averagePowerForChannel(0u).toDouble() / 20.0)
                val level = min(1.0, amplitude * FULL_SCALE / ANDROID_LOUD).toFloat()
                onLevel(level)
                val heardBefore = endOfSpeech.heardSpeech
                val done = endOfSpeech.onFrame(level, (recorder.currentTime * 1000).toLong())
                if (endOfSpeech.heardSpeech && !heardBefore) onSpeech()
                if (done) break
            }
        } finally {
            recorder.stop()
            onLevel(0f)
        }
        try {
            val pcm = NSData.dataWithContentsOfURL(url)?.toByteArray()?.let(::wavSamples) ?: error("The recording couldn't be read.")
            Recording(pcm16Wav(pcm, SAMPLE_RATE), "audio/wav", endOfSpeech.heardSpeech || finishRequested && pcm.size > SAMPLE_RATE / 2)
        } finally {
            NSFileManager.defaultManager.removeItemAtURL(url, null)
        }
    }

    override fun finish() {
        finishRequested = true
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20L
        const val FULL_SCALE = 32_768.0
        const val ANDROID_LOUD = 256.0 * 42.0

        val SETTINGS: Map<Any?, *> = mapOf(
            AVFormatIDKey to kAudioFormatLinearPCM.toInt(),
            AVSampleRateKey to SAMPLE_RATE.toDouble(),
            AVNumberOfChannelsKey to 1,
            AVLinearPCMBitDepthKey to 16,
            AVLinearPCMIsFloatKey to false,
            AVLinearPCMIsBigEndianKey to false,
        )
    }
}

/** Plays each reply clip with [AVAudioPlayer], out of the loudspeaker unless a headset is on. */
@OptIn(ExperimentalForeignApi::class)
class IosSpeechPlayer : SpeechPlayer {

    override suspend fun play(audio: SpokenAudio) = VoiceAudioSession.use {
        withContext(Dispatchers.Main) {
            val player = memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                AVAudioPlayer(audio.bytes.toNSData(), error.ptr)
            } ?: error("Couldn't play the reply (${audio.mimeType} isn't supported here).")
            var ended: ((String?) -> Unit)? = null
            // The player only keeps a weak reference to its delegate: this one is used again after the
            // wait, so the coroutine holds it until the clip is over.
            val delegate = Ended { failure -> ended?.invoke(failure) }
            player.delegate = delegate
            try {
                suspendCancellableCoroutine { continuation ->
                    ended = { failure ->
                        if (continuation.isActive) {
                            if (failure == null) continuation.resume(Unit) else continuation.resumeWithException(IllegalStateException(failure))
                        }
                    }
                    continuation.invokeOnCancellation { player.stop() }
                    if (!player.play()) continuation.resumeWithException(IllegalStateException("Couldn't play the reply."))
                }
            } finally {
                if (player.delegate === delegate) player.delegate = null
            }
        }
    }

    private class Ended(private val onEnd: (failure: String?) -> Unit) : NSObject(), AVAudioPlayerDelegateProtocol {
        override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) =
            onEnd(if (successfully) null else "The reply stopped playing.")

        override fun audioPlayerDecodeErrorDidOccur(player: AVAudioPlayer, error: NSError?) =
            onEnd("Couldn't play the reply: ${error?.localizedDescription ?: "it couldn't be decoded"}.")
    }
}
