package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.platform.toByteArray
import dev.hermeskotlin.core.platform.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetoothA2DP
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
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
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfURL
import platform.darwin.NSObject
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.TimeSource

/**
 * The app's audio session while the microphone or the speaker is in use. Recording takes play-and-record
 * (out of the loudspeaker, a headset's mic over Bluetooth); playing alone takes playback, so AirPods play a
 * reply in full quality rather than call quality. Held by count and let go a moment after the last user, so
 * the pause between a reply's clips, or between a reply and listening, doesn't hand the audio back to
 * another app for a second.
 */
@OptIn(ExperimentalForeignApi::class)
internal object VoiceAudioSession {
    enum class Use { Record, Play }

    private val main = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var recorders = 0
    private var players = 0
    private var active = false
    private var release: Job? = null

    init {
        // A call or an alarm takes the session away: it has to be asked for again afterwards.
        NSNotificationCenter.defaultCenter.addObserverForName(AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue) { _ ->
            active = false
        }
    }

    suspend fun <T> use(use: Use, block: suspend () -> T): T {
        withContext(Dispatchers.Main) { acquire(use) }
        try {
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { release(use) }
        }
    }

    private fun acquire(use: Use) {
        release?.cancel()
        release = null
        if (use == Use.Record) recorders++ else players++
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val session = AVAudioSession.sharedInstance()
            // Each time: a recording that starts while a reply plays needs the microphone added.
            val categorized = if (recorders > 0) {
                session.setCategory(
                    AVAudioSessionCategoryPlayAndRecord,
                    AVAudioSessionModeDefault,
                    AVAudioSessionCategoryOptionDefaultToSpeaker or AVAudioSessionCategoryOptionAllowBluetooth or AVAudioSessionCategoryOptionAllowBluetoothA2DP,
                    error.ptr,
                )
            } else {
                session.setCategory(AVAudioSessionCategoryPlayback, AVAudioSessionModeSpokenAudio, 0u, error.ptr)
            }
            val ready = categorized && (active || session.setActive(true, error.ptr))
            if (!ready) {
                if (use == Use.Record) recorders-- else players--
                error("The microphone isn't available: ${error.value?.localizedDescription ?: "audio session refused"}.")
            }
            active = true
        }
    }

    private fun release(use: Use) {
        if (use == Use.Record) recorders-- else players--
        if (recorders + players > 0) return
        release = main.launch {
            delay(LINGER_MS)
            if (recorders + players == 0 && active) {
                active = false
                AVAudioSession.sharedInstance().setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
            }
        }
    }

    /** Long enough to bridge synthesizing the next clip or sending a transcript. */
    private const val LINGER_MS = 4_000L
}

/**
 * Records 16 kHz mono 16-bit PCM with [AVAudioRecorder] and hands it over as WAV, like Android. The level
 * is the recorder's metered average power, scaled the way Android scales its RMS, so the same
 * [EndOfSpeech] thresholds hold.
 */
@OptIn(ExperimentalForeignApi::class)
class IosVoiceRecorder : VoiceRecorder {

    @Volatile private var finishRequested = false

    override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
        // Before the session is set up, so a stop tapped meanwhile still counts.
        finishRequested = false
        return VoiceAudioSession.use(VoiceAudioSession.Use.Record) { recordNow(activity, onLevel, onSpeech) }
    }

    private suspend fun recordNow(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + "herald-voice-${NSUUID().UUIDString}.wav")
        val recorder = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            AVAudioRecorder(url, SETTINGS, error.ptr).takeIf { it.prepareToRecord() }
        } ?: error("The microphone isn't available.")
        val endOfSpeech = EndOfSpeech(activity)
        try {
            recorder.meteringEnabled = true
            check(recorder.record()) { "The microphone isn't available." }
            // The wall clock, not the recorder's: a recording paused by a call must still time out.
            val started = TimeSource.Monotonic.markNow()
            while (!finishRequested) {
                delay(FRAME_MS)
                currentCoroutineContext().ensureActive()
                // Interrupted (a call, Siri): keep what was said before it.
                if (!recorder.recording) break
                recorder.updateMeters()
                // dBFS to a linear amplitude; Android's 16-bit RMS / 256 / 42 is the same amplitude × 32768 / 10752.
                val amplitude = 10.0.pow(recorder.averagePowerForChannel(0u).toDouble() / 20.0)
                val level = min(1.0, amplitude * FULL_SCALE / ANDROID_LOUD).toFloat()
                onLevel(level)
                val heardBefore = endOfSpeech.heardSpeech
                val done = endOfSpeech.onFrame(level, started.elapsedNow().inWholeMilliseconds)
                if (endOfSpeech.heardSpeech && !heardBefore) onSpeech()
                if (done) break
            }
        } finally {
            recorder.stop()
            onLevel(0f)
        }
        try {
            val pcm = NSData.dataWithContentsOfURL(url)?.toByteArray()?.let(::wavSamples) ?: error("The recording couldn't be read.")
            return Recording(pcm16Wav(pcm, SAMPLE_RATE), "audio/wav", endOfSpeech.heardSpeech || finishRequested && pcm.size > SAMPLE_RATE / 2)
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

/**
 * Plays each reply clip with [AVAudioPlayer]. It decodes MP3, AAC, WAV and FLAC, which covers the gateway's
 * speech providers except an Ogg/Opus setting; such a clip fails with a message saying so.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSpeechPlayer : SpeechPlayer {

    override suspend fun play(audio: SpokenAudio) = VoiceAudioSession.use(VoiceAudioSession.Use.Play) {
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
            // An interrupted player pauses and never finishes: end the wait instead of hanging in it.
            val interruptions = NSNotificationCenter.defaultCenter.addObserverForName(
                AVAudioSessionInterruptionNotification,
                null,
                NSOperationQueue.mainQueue,
            ) { _ -> ended?.invoke("The reply was interrupted.") }
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
                NSNotificationCenter.defaultCenter.removeObserver(interruptions)
                player.stop()
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
