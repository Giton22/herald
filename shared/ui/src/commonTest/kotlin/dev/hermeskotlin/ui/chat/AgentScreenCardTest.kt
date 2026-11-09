package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.network.HermesJson
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentScreenCardTest {

    @Test
    fun aTapNoCallWaitsOnSaysWhatWasTappedAndWhatTheFormHeld() {
        val tap = HermesJson.parseToJsonElement(
            """{"event":"save","surface_id":"plan","component_id":"b1","context":{"day":"sat"},"data":{"budget":50}}""",
        )
        assertEquals(
            """Tapped “save” on the screen you showed: {"context":{"day":"sat"},"data":{"budget":50}}""",
            screenTapMessage(tap),
        )
    }

    @Test
    fun aPlainTapIsOneShortLine() {
        val tap = HermesJson.parseToJsonElement("""{"event":"pick-b","surface_id":"s","component_id":"b","context":{}}""")
        assertEquals("Tapped “pick-b” on the screen you showed.", screenTapMessage(tap))
        assertEquals("Tapped a button on the screen you showed.", screenTapMessage(JsonPrimitive("odd")))
    }
}
