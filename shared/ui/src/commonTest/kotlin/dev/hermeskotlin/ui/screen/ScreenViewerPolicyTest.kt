package dev.hermeskotlin.ui.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The two things the app has to get right about the browser and the viewer.
 *
 * A browser that is still starting is not a failure: the page has not had its chance yet, and a screen
 * that is taken away before it was opened is a screen the user cannot watch.
 *
 * And a viewer that was replaced — another bot, another gateway, another ticket — says nothing about the
 * screen in front of the user now.
 */
class ScreenViewerPolicyTest {

    @Test
    fun aBrowserThatIsStillStartingIsNotAFailure() {
        assertNull(screenBrowserEvent(ScreenBrowserStage.Starting))
    }

    @Test
    fun aBrowserThatIsUpIsNotAFailure() {
        assertNull(screenBrowserEvent(ScreenBrowserStage.Ready))
    }

    @Test
    fun aBrowserThatWillNotStartIsAFailure() {
        assertEquals(
            ScreenViewerEvent.Failed("The browser didn't start."),
            screenBrowserEvent(ScreenBrowserStage.Failed),
        )
    }

    @Test
    fun theViewerOfTheScreenInHandIsBelieved() {
        assertEquals(
            ScreenViewerEvent.Connected,
            screenEventAbout("wss://gateway.example/display", "wss://gateway.example/display", ScreenViewerEvent.Connected),
        )
    }

    @Test
    fun aViewerThatWasReplacedSaysNothingAboutThisOne() {
        assertNull(
            screenEventAbout(
                "wss://gateway.example/display/old",
                "wss://gateway.example/display",
                ScreenViewerEvent.Closed,
            ),
        )
    }
}
