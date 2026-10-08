package dev.hermeskotlin.core.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelPickerTest {

    private val catalog = ModelCatalog(
        providers = listOf(
            ModelProvider("openrouter", "OpenRouter", listOf(model("z-ai/glm-5.2", "openrouter"), model("deepseek-v4", "openrouter"))),
            ModelProvider("openai-codex", "OpenAI Codex", listOf(model("gpt-6-sol", "openai-codex"))),
            ModelProvider("anthropic", "Anthropic", listOf(model("claude-opus-5-5", "anthropic"), model("deepseek-v4", "anthropic"))),
        ),
    )

    @Test
    fun emptyQueryShowsEveryProvider() {
        assertEquals(listOf("OpenRouter", "ChatGPT subscription", "Anthropic"), catalog.pickerGroups("").map { it.title })
    }

    @Test
    fun providerNameFindsAllItsModels() {
        val groups = catalog.pickerGroups("openrouter")
        assertEquals(listOf("OpenRouter"), groups.map { it.title })
        assertEquals(listOf("z-ai/glm-5.2", "deepseek-v4"), groups.single().models.map { it.id })
    }

    @Test
    fun displayedProviderNameAndSlugBothMatch() {
        assertEquals(listOf("ChatGPT subscription"), catalog.pickerGroups("chatgpt").map { it.title })
        assertEquals(listOf("ChatGPT subscription"), catalog.pickerGroups("codex").map { it.title })
    }

    @Test
    fun modelNameStillMatchesAcrossProviders() {
        val groups = catalog.pickerGroups("DeepSeek")
        assertEquals(listOf("OpenRouter", "Anthropic"), groups.map { it.title })
        assertTrue(groups.all { g -> g.models.all { it.id == "deepseek-v4" } })
        assertEquals(listOf("claude-opus-5-5"), catalog.pickerGroups("opus 5.5").single().models.map { it.id })
    }

    @Test
    fun starredComeFirstInStarOrderAndStayInTheirGroup() {
        val groups = catalog.pickerGroups("", starred = listOf("anthropic/claude-opus-5-5", "openrouter/z-ai/glm-5.2"))
        val top = groups.first()
        assertTrue(top.starred)
        assertEquals(listOf("claude-opus-5-5", "z-ai/glm-5.2"), top.models.map { it.id })
        assertTrue(groups.first { it.title == "Anthropic" }.models.any { it.id == "claude-opus-5-5" })
    }

    @Test
    fun starKeepsProvidersApartAndForgetsMissingModels() {
        // Same id on two providers: only the starred provider's copy is starred. A model the gateway no longer
        // offers doesn't leave an empty row.
        val groups = catalog.pickerGroups("", starred = listOf("anthropic/deepseek-v4", "gone/old-model"))
        assertEquals(listOf("anthropic"), groups.first().models.map { it.provider })
    }

    @Test
    fun searchFiltersTheStarredGroupToo() {
        val starred = listOf("anthropic/claude-opus-5-5")
        assertTrue(catalog.pickerGroups("glm", starred).none { it.starred })
        assertTrue(catalog.pickerGroups("opus", starred).first().starred)
    }

    @Test
    fun nothingMatches() {
        assertTrue(catalog.pickerGroups("llama").isEmpty())
    }

    private fun model(id: String, provider: String) = ModelOption(id = id, provider = provider)
}
