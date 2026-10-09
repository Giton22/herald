package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.cron.DeliveryTarget
import dev.hermeskotlin.core.cron.Routines
import kotlin.test.Test
import kotlin.test.assertEquals

class DeliveryLabelTest {

    private val targets = listOf(DeliveryTarget("telegram", "Telegram"), LOCAL_DELIVERY)

    @Test
    fun aListedTargetGoesByItsName() {
        assertEquals("Telegram", deliveryLabel("telegram", targets, botLabel = null))
    }

    @Test
    fun aBotsChatNamesTheBot() {
        assertEquals("Ops's chat", deliveryLabel(Routines.BOT_CHAT_DELIVERY, targets, botLabel = "Ops"))
        assertEquals("the bot's chat", deliveryLabel(Routines.BOT_CHAT_DELIVERY, targets, botLabel = null))
    }

    @Test
    fun oneChatSaysItsPlatform() {
        assertEquals("Telegram · -1001234", deliveryLabel("telegram:-1001234", targets, botLabel = null))
    }

    @Test
    fun anUnknownPlainIdStaysAsIs() {
        assertEquals("origin", deliveryLabel("origin", targets, botLabel = null))
    }
}
