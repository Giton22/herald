package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.models.ModelOption
import dev.hermeskotlin.core.models.ReasoningEffort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelSheetEffortTest {
    private val thinking = ModelOption("m1", "p1", reasoning = true)

    @Test
    fun aChatsOwnLevelWinsOverTheProfiles() {
        assertEquals(ReasoningEffort.Low, ChatState(reasoningEffort = "low", profileReasoningEffort = "max").effort(thinking))
    }

    @Test
    fun aChatWithoutItsOwnLevelShowsTheProfiles() {
        assertEquals(ReasoningEffort.Max, ChatState(profileReasoningEffort = "max").effort(thinking))
    }

    @Test
    fun withNeitherKnownTheGatewayDefaultShows() {
        assertEquals(ReasoningEffort.Default, ChatState().effort(thinking))
    }

    @Test
    fun aModelWithoutThinkingOrUnknownShowsNoProfileLevel() {
        assertNull(ChatState(profileReasoningEffort = "max").effort(ModelOption("m2", "p1")))
        assertNull(ChatState(profileReasoningEffort = "max").effort(null))
    }
}
