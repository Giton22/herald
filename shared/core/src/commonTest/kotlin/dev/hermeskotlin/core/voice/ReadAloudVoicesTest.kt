package dev.hermeskotlin.core.voice

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.TestTimeSource

class ReadAloudVoicesTest {

    private fun clip(text: String) = SpokenAudio(text.encodeToByteArray(), "audio/mpeg")

    @Test
    fun aPieceOnlyPlaysForTheWordsItSays() = runTest {
        val voices = InMemoryReadAloudVoices()
        voices.keep("r", "Built it.", clip("Built it."))

        assertEquals("Built it.", voices.piece("r", "Built it.")!!.bytes.decodeToString())
        // The same reply cut into other pieces (an update that cuts it differently) is voiced anew.
        assertNull(voices.piece("r", "Built it. All green."))
    }

    @Test
    fun lookingForAReplyNotKeptLetsNoneGo() = runTest {
        val voices = InMemoryReadAloudVoices(replies = 2)
        voices.keep("one", "One.", clip("One."))
        voices.keep("two", "Two.", clip("Two."))
        repeat(3) { assertNull(voices.piece("other $it", "Other.")) }

        assertNotNull(voices.piece("one", "One."))
        assertNotNull(voices.piece("two", "Two."))
    }

    @Test
    fun aTakeKeptADayIsVoicedAnewWhileTheAppStaysOpen() = runTest {
        val time = TestTimeSource()
        val voices = InMemoryReadAloudVoices(time = time)
        voices.keep("r", "Hi.", clip("Hi."))
        // One read back from the disk counts the time it already spent there.
        voices.keep("r", "Bye.", clip("Bye."), age = 20.hours)

        time += 5.hours
        assertNotNull(voices.piece("r", "Hi."))
        assertNull(voices.piece("r", "Bye."))
        time += 20.hours
        assertNull(voices.piece("r", "Hi."))
    }

    @Test
    fun theOldestReplyGoesFirst() = runTest {
        val voices = InMemoryReadAloudVoices(replies = 2)
        voices.keep("one", "One.", clip("One."))
        voices.keep("two", "Two.", clip("Two."))
        // Read again, "one" is the most recent.
        voices.piece("one", "One.")
        voices.keep("three", "Three.", clip("Three."))

        assertNotNull(voices.piece("one", "One."))
        assertNull(voices.piece("two", "Two."))
    }
}
