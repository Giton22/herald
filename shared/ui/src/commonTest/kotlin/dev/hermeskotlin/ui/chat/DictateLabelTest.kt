package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.ui.voice.DictationState
import kotlin.test.Test
import kotlin.test.assertEquals

class DictateLabelTest {

    @Test
    fun idleOffersToDictate() = assertEquals("Dictate", dictateLabel(DictationState()))

    @Test
    fun recordingSaysTheTapFinishes() = assertEquals("Finish dictating", dictateLabel(DictationState(recording = true)))

    @Test
    fun transcribingSaysSo() = assertEquals("Transcribing…", dictateLabel(DictationState(transcribing = true)))

    @Test
    fun anErrorLeavesItReadyToTryAgain() = assertEquals("Dictate", dictateLabel(DictationState(error = "Didn't catch anything.")))
}
