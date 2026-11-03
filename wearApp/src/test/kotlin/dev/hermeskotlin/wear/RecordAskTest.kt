package dev.hermeskotlin.wear

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordAskTest {

    private val listening = MutableStateFlow(Listening(waiting = true))
    private val asked = mutableListOf<ByteArray>()

    private fun run(generation: Long = 1, current: Long = 1, record: suspend ((Float) -> Unit) -> ByteArray?) = runBlocking {
        recordAsk(listening, generation, { current }, record) { asked += it }
    }

    @Test
    fun theMicrophoneShowsOnlyOnceItRecords() {
        val seen = mutableListOf<Listening>()
        run { onLevel ->
            seen += listening.value
            onLevel(0.5f)
            seen += listening.value
            byteArrayOf(1)
        }
        assertEquals(listOf(Listening(recording = true), Listening(recording = true, level = 0.5f)), seen)
        assertEquals(Listening(), listening.value)
        assertEquals(1, asked.size)
    }

    @Test
    fun aLateLevelDoesNotBringTheRecordingBack() {
        run(generation = 1, current = 2) { onLevel ->
            // The main thread moved on (a profile change) while the recorder still reports a level.
            listening.value = Listening(error = PROFILE_CHANGED)
            onLevel(0.5f)
            byteArrayOf(1)
        }
        assertEquals(Listening(error = PROFILE_CHANGED), listening.value)
    }

    @Test
    fun silenceIsSaidAndNotSent() {
        run { null }
        assertEquals(NOTHING_HEARD, listening.value.error)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aProfileChangeDropsTheRecording() {
        run(generation = 1, current = 2) { byteArrayOf(1) }
        assertEquals(PROFILE_CHANGED, listening.value.error)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aMicrophoneFailureIsShown() {
        run { error("The microphone isn't available.") }
        assertEquals("The microphone isn't available.", listening.value.error)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun leavingClearsTheRecordingQuietly() {
        assertFailsWith<CancellationException> { run { throw CancellationException("left") } }
        assertNull(listening.value.error)
        assertEquals(Listening(), listening.value)
    }
}
