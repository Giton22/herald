package dev.hermeskotlin.ui.screen

import dev.hermeskotlin.core.screen.DisplayLease
import dev.hermeskotlin.core.screen.DisplayStatus
import dev.hermeskotlin.core.screen.ScreenStage
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the pane shows for the screen's state. */
class ScreenPaneTest {

    private val status = DisplayStatus(profile = "scribe", supported = true, installed = true, running = true)

    @Test
    fun aGatewayWithoutABotScreenHasNone() {
        assertEquals(ScreenPaneStage.Unsupported, screenStage(ScreenUiState(supported = false)))
    }

    @Test
    fun aGatewayThatHasNotAnsweredYetIsLookedAt() {
        assertEquals(ScreenPaneStage.Looking, screenStage(ScreenUiState()))
    }

    @Test
    fun aScreenThatIsNotInstalledSaysSo() {
        assertEquals(ScreenPaneStage.NotInstalled, screenStage(ScreenUiState(status = status.copy(installed = false))))
    }

    @Test
    fun aScreenTheGatewayRefusesSaysSo() {
        assertEquals(ScreenPaneStage.Blocked, screenStage(ScreenUiState(status = status.copy(blocker = "no memory"))))
    }

    @Test
    fun aStoppedScreenCanBeStarted() {
        assertEquals(ScreenPaneStage.Stopped, screenStage(ScreenUiState(status = status.copy(running = false))))
    }

    @Test
    fun aScreenThatIsOpeningIsOpening() {
        assertEquals(ScreenPaneStage.Opening, screenStage(ScreenUiState(status = status, stage = ScreenStage.Opening)))
    }

    @Test
    fun aScreenThatCouldNotBeOpenedOffersAnotherTry() {
        assertEquals(ScreenPaneStage.Failed, screenStage(ScreenUiState(status = status, stage = ScreenStage.Failed)))
    }

    @Test
    fun aScreenThatIsShowingIsWatched() {
        assertEquals(ScreenPaneStage.Watching, screenStage(ScreenUiState(status = status, stage = ScreenStage.Connected)))
    }

    @Test
    fun whoHoldsTheScreen() {
        assertEquals("The bot is in control", controlLabel(DisplayLease(DisplayLease.AGENT), false))
        assertEquals("Someone else is in control", controlLabel(DisplayLease(DisplayLease.HUMAN), false))
        assertEquals("You are in control", controlLabel(DisplayLease(DisplayLease.HUMAN), true))
    }
}
