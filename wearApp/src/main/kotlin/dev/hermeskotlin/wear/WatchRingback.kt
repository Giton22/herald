package dev.hermeskotlin.wear

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A connection cue for the Call Hermes entry point; manual calls have no default request. */
internal suspend fun ringCall(defaultRequestId: String?, play: suspend () -> Unit) {
    if (defaultRequestId == null) return
    try {
        while (true) {
            play()
            delay(3_000)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Optional audio must never prevent the call.
    }
}

internal fun endsRingback(type: String?): Boolean = type in setOf(
    "session.started", "session.output_transcript.delta", "session.closed", "error",
)

/** Main thread, like the watch's call flow. Cancellation releases even a clip still preparing. */
internal suspend fun playCallRingback(context: Context) {
    val player = MediaPlayer()
    try {
        suspendCancellableCoroutine<Unit> { waiting ->
            player.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            context.assets.openFd("call_ringback.wav").use { asset ->
                player.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
            }
            player.setOnCompletionListener { if (waiting.isActive) waiting.resume(Unit) }
            player.setOnErrorListener { _, _, _ ->
                if (waiting.isActive) waiting.resumeWithException(IllegalStateException("Couldn't play ringback"))
                true
            }
            player.setOnPreparedListener {
                if (waiting.isActive) runCatching { it.start() }.onFailure { error ->
                    if (waiting.isActive) waiting.resumeWithException(error)
                }
            }
            player.prepareAsync()
        }
    } finally {
        player.release()
    }
}
