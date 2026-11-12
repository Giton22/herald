package dev.hermeskotlin.ui.screen

import kotlin.test.Test
import kotlin.test.assertEquals

/** How much closer the screen can be looked at. */
class ScreenZoomTest {

    @Test
    fun theWholeScreenIsTheFarEnd() {
        assertEquals(1f, SCREEN_ZOOM_STEPS.first())
    }

    @Test
    fun theButtonsGoOneStepAtATime() {
        assertEquals(1.5f, screenZoomIn(1f))
        assertEquals(2f, screenZoomIn(1.5f))
        assertEquals(3f, screenZoomIn(2f))
        assertEquals(1.5f, screenZoomOut(2f))
        assertEquals(1f, screenZoomOut(1.5f))
    }

    @Test
    fun thereIsNoFurtherInThanTheLastStep() {
        assertEquals(8f, screenZoomIn(8f))
        assertEquals(8f, screenZoomIn(20f))
    }

    @Test
    fun thereIsNoFurtherOutThanTheWholeScreen() {
        assertEquals(1f, screenZoomOut(1f))
        assertEquals(1f, screenZoomOut(0.5f))
    }

    @Test
    fun aPinchBetweenTwoStepsGoesToTheNextOne() {
        assertEquals(3f, screenZoomIn(2.4f))
        assertEquals(2f, screenZoomOut(2.4f))
    }

    @Test
    fun theScreenIsNeverLookedAtFromFurtherOutThanItIs() {
        assertEquals(1f, screenZoom(0.2f))
        assertEquals(8f, screenZoom(40f))
        assertEquals(2.5f, screenZoom(2.5f))
    }
}
