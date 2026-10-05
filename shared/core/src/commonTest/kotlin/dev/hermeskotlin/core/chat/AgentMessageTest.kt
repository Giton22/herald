package dev.hermeskotlin.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentMessageTest {

    @Test
    fun readsTheCurrentForm() {
        assertEquals(
            AgentMessage("Research Buddy", "research-buddy", "Here are the findings:\n- one"),
            AgentMessage.parse("Message from 🤖 Research Buddy (@research-buddy): Here are the findings:\n- one"),
        )
        // Cross-connection handles carry the machine.
        assertEquals("hermes", AgentMessage.parse("Message from 🤖 hermes (@hermes@mac-mini): hi")?.handle)
    }

    @Test
    fun readsTheEmojilessAndLegacyForms() {
        assertEquals(AgentMessage("scribe", null, "done"), AgentMessage.parse("Message from scribe: done"))
        assertEquals(AgentMessage("scribe", null, "done"), AgentMessage.parse("[Message from agent 'scribe'] done"))
    }

    @Test
    fun leavesWhatTheUserTypedAlone() {
        assertNull(AgentMessage.parse("Can you check the Message from scribe: earlier?"))
        assertNull(AgentMessage.parse("hello"))
        assertNull(AgentMessage.parse(""))
    }
}
