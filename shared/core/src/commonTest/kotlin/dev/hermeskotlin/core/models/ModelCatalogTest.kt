package dev.hermeskotlin.core.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCatalogTest {

    private fun parse(json: String) = ModelCatalog.parse(Json.parseToJsonElement(json) as JsonObject)

    @Test
    fun keepsAuthenticatedProvidersWithTheirCapabilitiesAndPrices() {
        val catalog = parse(
            """{"model":"gpt-6","provider":"openai","providers":[
              {"slug":"openai","name":"OpenAI","models":["gpt-6","gpt-6-mini","old"],"unavailable_models":["old"],
               "featured_models":["gpt-6","missing"],
               "capabilities":{"gpt-6":{"fast":true,"reasoning":true,"can_disable_reasoning":false},
                               "gpt-6-mini":{"fast":false,"reasoning":true,"can_disable_reasoning":true}},
               "pricing":{"gpt-6":{"input":"${'$'}1.25","output":"${'$'}10","free":false},"gpt-6-mini":{"input":"","output":"","free":true}}},
              {"slug":"anthropic","name":"Anthropic","models":["claude"],"authenticated":false},
              {"slug":"empty","name":"Empty","models":[]}
            ]}""",
        )
        assertEquals("gpt-6", catalog.currentModel)
        assertEquals(listOf("openai"), catalog.providers.map { it.slug })
        val openai = catalog.providers.single()
        assertEquals(listOf("gpt-6", "gpt-6-mini"), openai.models.map { it.id })
        assertEquals(listOf("gpt-6"), openai.featured)
        val main = catalog.find("openai", "gpt-6")!!
        assertTrue(main.fast && main.reasoning && !main.canDisableReasoning)
        assertEquals("${'$'}1.25 / ${'$'}10", main.price)
        assertEquals("Free", catalog.find(null, "gpt-6-mini")!!.price)
        assertNull(catalog.find("anthropic", "claude"))
    }

    @Test
    fun effortChoicesFollowTheModel() {
        val off = ModelOption("m", "p", reasoning = true, canDisableReasoning = true)
        assertEquals(ReasoningEffort.Off, ReasoningEffort.choicesFor(off).first())
        assertFalse(ReasoningEffort.Off in ReasoningEffort.choicesFor(off.copy(canDisableReasoning = false)))
        assertTrue(ReasoningEffort.choicesFor(off.copy(reasoning = false)).isEmpty())
        assertEquals(ReasoningEffort.XHigh, ReasoningEffort.fromWire(" XHIGH "))
    }
}
