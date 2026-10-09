package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.SendCheck
import kotlin.test.Test
import kotlin.test.assertEquals

class DeliveryLabelTest {

    @Test
    fun eachDoubtSaysWhereThePromptStands() {
        assertEquals("Not delivered", deliveryLabel(SendCheck.NotReceived))
        assertEquals("May not have arrived", deliveryLabel(SendCheck.Unknown))
        assertEquals("Checking whether Hermes got this…", deliveryLabel(SendCheck.Checking))
    }
}
