package dev.hermeskotlin.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadBackDetectorTest {

    private var readBacks = 0
    private val detector = ReadBackDetector(travel = 100f) { readBacks++ }

    private fun scroll(y: Float) = detector.onPostScroll(Offset(0f, y), Offset.Zero, NestedScrollSource.UserInput)

    @Test
    fun scrollingBackFarEnoughFoldsTheComposer() {
        scroll(60f)
        assertEquals(0, readBacks)
        scroll(50f)
        assertEquals(1, readBacks)
    }

    @Test
    fun movingTowardsTheEndStartsTheCountOver() {
        scroll(80f)
        scroll(-5f)
        scroll(80f)
        assertEquals(0, readBacks)
    }

    @Test
    fun theComposerOpensAsTheEndComesNear() {
        assertEquals(1f, foldFor(hiddenBelow = 0, range = 200f))
        assertEquals(0.5f, foldFor(hiddenBelow = 100, range = 200f))
        assertEquals(0f, foldFor(hiddenBelow = 500, range = 200f))
        // The last message not even laid out: far from the end.
        assertEquals(0f, foldFor(hiddenBelow = Int.MAX_VALUE, range = 200f))
    }

    @Test
    fun aDragThatDoesNotMoveTheListDoesNotCount() {
        // Pulling down at the top of the chat: nothing consumed.
        repeat(5) { detector.onPostScroll(Offset.Zero, Offset(0f, 50f), NestedScrollSource.UserInput) }
        assertEquals(0, readBacks)
    }
}
