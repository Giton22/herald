package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextBreakdownTest {

    @Test
    fun parsesCategoriesTotalsAndFiles() {
        val result = HermesJson.parseToJsonElement(
            """{"categories":[{"color":"var(--context-usage-system)","id":"system_prompt","label":"System prompt","tokens":4200},
               {"color":"var(--context-usage-conversation)","id":"conversation","label":"Conversation","tokens":12800}],
               "context_max":200000,"context_percent":9,"context_used":17000,"context_source":"provider_usage",
               "context_estimated":false,"estimated_total":17000,"model":"glm-5.3-flash",
               "context_files":[{"label":"AGENTS.md","path":"/srv/app/AGENTS.md","chars":8000,"est_tokens":2000,"loaded":true,"status":"ok"}]}""",
        ).jsonObject

        val breakdown = ContextBreakdown.parse(result)!!

        assertEquals(listOf("system_prompt", "conversation"), breakdown.categories.map { it.id })
        assertEquals(12800, breakdown.categories[1].tokens)
        assertEquals(17000, breakdown.used)
        assertEquals(200000, breakdown.max)
        assertFalse(breakdown.estimated)
        assertEquals("AGENTS.md", breakdown.files.single().label)
        assertFalse(breakdown.isEmpty)
    }

    @Test
    fun beforeTheAgentExistsItIsEmpty() {
        val breakdown = ContextBreakdown.parse(JsonObject(emptyMap()))!!
        assertTrue(breakdown.isEmpty)
        assertTrue(breakdown.estimated)
        assertNull(ContextBreakdown.parse(null))
    }
}
