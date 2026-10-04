package dev.hermeskotlin.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class FollowStepTest {

    @Test
    fun aPinnedListFollowsTheEndWhenItMovesOutOfView() {
        assertEquals(FollowStep.ScrollToEnd, followStep(pinned = true, hiddenBelow = 69, readerScrolling = false))
        assertEquals(FollowStep.None, followStep(pinned = true, hiddenBelow = 0, readerScrolling = false))
    }

    @Test
    fun aReplyGrowingBelowTheReaderDoesNotMoveThem() {
        assertEquals(FollowStep.None, followStep(pinned = false, hiddenBelow = 4000, readerScrolling = false))
    }

    @Test
    fun scrollingAwayUnpinsAndScrollingBackToTheEndPins() {
        assertEquals(FollowStep.Unpin, followStep(pinned = true, hiddenBelow = 300, readerScrolling = true))
        assertEquals(FollowStep.Pin, followStep(pinned = false, hiddenBelow = 0, readerScrolling = true))
    }

    @Test
    fun theListNeverPullsAgainstTheReaderWhileTheyScroll() {
        assertEquals(FollowStep.None, followStep(pinned = true, hiddenBelow = 0, readerScrolling = true))
        assertEquals(FollowStep.None, followStep(pinned = false, hiddenBelow = 300, readerScrolling = true))
    }
}
