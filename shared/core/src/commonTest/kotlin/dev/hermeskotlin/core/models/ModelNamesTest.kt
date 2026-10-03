package dev.hermeskotlin.core.models

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelNamesTest {
    @Test
    fun readsLikeDesktop() {
        assertEquals("GPT-6-sol", displayModelName("openai/gpt-6-sol"))
        assertEquals("Opus 5.5", displayModelName("claude-opus-5-5"))
        assertEquals("GLM 5.2", displayModelName("z-ai/glm-5.2"))
        assertEquals("Gemini 2.5 Pro", displayModelName("gemini-2.5-pro"))
        assertEquals("Qwen3 235B A22B", displayModelName("qwen/qwen3-235b-a22b"))
        assertEquals("DeepSeek V4", displayModelName("deepseek-v4"))
        assertEquals("ChatGPT subscription", displayProviderName("openai-codex", "OpenAI Codex"))
        assertEquals("My Relay", displayProviderName("custom:relay", "My Relay"))
    }
}
