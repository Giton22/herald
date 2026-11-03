package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.cron.DeliveryTarget
import dev.hermeskotlin.core.cron.Routines
import kotlin.test.Test
import kotlin.test.assertEquals

class DeliveryLabelTest {

    private val targets = listOf(DeliveryTarget("telegram", "Telegram"), LOCAL_DELIVERY)

    // The screen's own words for these two, as a screen would read them from the catalogue.
    private fun label(id: String, targets: List<DeliveryTarget> = this.targets) =
        deliveryLabel(id, targets, botChat = "BOT_CHAT", local = "LOCAL")

    @Test
    fun aListedTargetGoesByItsName() {
        assertEquals("Telegram", label("telegram"))
    }

    @Test
    fun aBotsChatIsTheScreensWords() {
        assertEquals("BOT_CHAT", label(Routines.BOT_CHAT_DELIVERY))
        // Even when the list carries the bot's entry under its own name.
        assertEquals("BOT_CHAT", label(Routines.BOT_CHAT_DELIVERY, targets + DeliveryTarget(Routines.BOT_CHAT_DELIVERY, "Ops")))
    }

    @Test
    fun savingOnlyIsTheScreensWords() {
        assertEquals("LOCAL", label(LOCAL_DELIVERY.id))
        assertEquals("LOCAL", label(LOCAL_DELIVERY.id, targets = emptyList()))
    }

    @Test
    fun aGatewayNamedLocalTargetKeepsItsName() {
        assertEquals("On this machine", label("local", listOf(DeliveryTarget("local", "On this machine"))))
    }

    @Test
    fun oneChatSaysItsPlatform() {
        assertEquals("Telegram · -1001234", label("telegram:-1001234"))
    }

    @Test
    fun anUnknownPlainIdStaysAsIs() {
        assertEquals("origin", label("origin"))
    }
}
