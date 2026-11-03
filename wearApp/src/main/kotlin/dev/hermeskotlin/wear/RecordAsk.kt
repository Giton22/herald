package dev.hermeskotlin.wear

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal const val PROFILE_CHANGED = "Phone profile changed. Please try again."
internal const val NOTHING_HEARD = "I didn't hear anything."

/**
 * One recorded ask, as the talk screen and the assistant session both make it: [listening] shows the recording
 * and its level, and the audio goes to [ask] only while the phone's profile is still [generation], the one it was
 * made in. The caller has already checked the phone and the microphone.
 */
internal suspend fun recordAsk(
    listening: MutableStateFlow<Listening>,
    generation: Long,
    currentGeneration: () -> Long,
    record: suspend (onLevel: (Float) -> Unit) -> ByteArray?,
    ask: (ByteArray) -> Unit,
) {
    listening.value = Listening(recording = true)
    val audio = try {
        // Levels arrive on the recorder's thread: never over a state the main thread has moved on to.
        record { level -> listening.update { if (it.recording) it.copy(level = level) else it } }
    } catch (e: CancellationException) {
        // Left the screen: nothing to tell.
        throw e
    } catch (e: Exception) {
        listening.value = Listening(error = e.message ?: "Couldn't use the microphone.")
        return
    } finally {
        if (listening.value.recording) listening.value = Listening()
    }
    when {
        generation != currentGeneration() -> listening.value = Listening(error = PROFILE_CHANGED)
        audio == null -> listening.value = Listening(error = NOTHING_HEARD)
        else -> ask(audio)
    }
}
