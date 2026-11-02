package dev.hermeskotlin.ui.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the page says about the screen it was opened for.
 *
 * The page cannot talk to the app directly: it asks for an address of its own, and the app reads the
 * answer out of it. Nothing else is an answer, and nothing else is believed.
 */
class ScreenViewerEventTest {

    @Test
    fun thePageSaysItIsShowing() {
        assertEquals(ScreenViewerEvent.Connected, screenEvent("herald://screen?state=connected"))
    }

    @Test
    fun thePageSaysItClosed() {
        assertEquals(ScreenViewerEvent.Closed, screenEvent("herald://screen?state=closed"))
    }

    @Test
    fun thePageSaysWhyItCouldNotOpenTheScreen() {
        assertEquals(
            ScreenViewerEvent.Failed("This device's browser is too old to show the screen."),
            screenEvent("herald://screen?state=failed&reason=too-old"),
        )
    }

    @Test
    fun aFailureWithoutAReasonIsStillAFailure() {
        assertEquals(
            ScreenViewerEvent.Failed("The screen could not be opened."),
            screenEvent("herald://screen?state=failed"),
        )
    }

    @Test
    fun anotherAddressIsNotAnAnswer() {
        assertNull(screenEvent("https://example.com/"))
        assertNull(screenEvent("herald://something-else?state=connected"))
        assertNull(screenEvent("herald://screen?state=whatever"))
    }
}
