package dev.hermeskotlin.wear

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal val PROFILE_CHANGED = WatchLine.Res(R.string.wear_profile_changed)
internal val NOTHING_HEARD = WatchLine.Res(R.string.wear_nothing_heard)

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
    } catch (e: MicrophoneUnavailableException) {
        listening.value = Listening(error = WatchLine.Res(R.string.wear_microphone_unavailable))
        return
    } catch (e: Exception) {
        listening.value = Listening(error = e.message?.let { WatchLine.Raw(it) } ?: WatchLine.Res(R.string.wear_microphone_failed))
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
