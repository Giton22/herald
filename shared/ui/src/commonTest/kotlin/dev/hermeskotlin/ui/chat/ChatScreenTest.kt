package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.ui.voice.DictationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The small decisions [ChatScreen] makes: what the dictate button says, how the list follows, the link line. */
class ChatScreenTest {

    @Test
    fun theDictateLabelFollowsTheDictation() {
        assertEquals("Dictate", dictateLabel(DictationState()))
        assertEquals("Finish dictating", dictateLabel(DictationState(recording = true)))
        assertEquals("Transcribing…", dictateLabel(DictationState(transcribing = true)))
        // An error leaves it ready to try again.
        assertEquals("Dictate", dictateLabel(DictationState(error = "Didn't catch anything.")))
    }

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

    @Test
    fun theLinkLineSaysWhyTheChatIsOffline() {
        assertEquals("Waiting for network…", linkStatus(ConnectionState.Reconnecting(3, 0, "Can't reach gateway: Unable to resolve host")))
        assertEquals("Reconnecting…", linkStatus(ConnectionState.Reconnecting(1, 0, "Connection lost")))
        // A retry under way still says reconnecting.
        assertEquals("Reconnecting…", linkStatus(ConnectionState.Connecting(2)))
    }

    @Test
    fun theFirstConnectAndAnUpOrFailedLinkSayNothing() {
        assertNull(linkStatus(ConnectionState.Connecting(1)))
        assertNull(linkStatus(ConnectionState.Idle))
        assertNull(linkStatus(ConnectionState.SessionExpired))
        assertNull(linkStatus(ConnectionState.Failed("4403")))
    }
}
