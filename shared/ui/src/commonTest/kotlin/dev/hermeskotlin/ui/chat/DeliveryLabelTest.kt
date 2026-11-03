package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.SendCheck
import dev.hermeskotlin.ui.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals

class DeliveryLabelTest {

    @Test
    fun eachDoubtSaysWhereThePromptStands() {
        assertEquals(Res.string.chat_delivery_not_received, deliveryLabel(SendCheck.NotReceived))
        assertEquals(Res.string.chat_delivery_unknown, deliveryLabel(SendCheck.Unknown))
        assertEquals(Res.string.chat_delivery_checking, deliveryLabel(SendCheck.Checking))
    }
}
