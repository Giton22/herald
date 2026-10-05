package dev.hermeskotlin.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatLinksTest {

    @Test
    fun aSessionLinkOpensThatChat() {
        val links = ChatLinks()
        links.follow(AppLink.Session("s1"))
        assertEquals(ChatLink(storedSessionId = "s1", title = null), links.pending.value)
    }

    @Test
    fun newChatLinksOpenANewChatWithOrWithoutDictation() {
        val links = ChatLinks()
        links.follow(AppLink.NewChat)
        val plain = assertNotNull(links.pending.value)
        assertNull(plain.storedSessionId)
        assertFalse(assertNotNull(plain.draft).dictate)

        links.follow(AppLink.NewChatVoice)
        assertTrue(assertNotNull(links.pending.value?.draft).dictate)
    }

    @Test
    fun aShareToABotOpensItsChatWithTheDraft() {
        val links = ChatLinks()
        val draft = ComposeDraft(text = "look at this")
        links.shareToBot("scribe", draft)
        val link = assertNotNull(links.pending.value)
        assertEquals("scribe", link.bot)
        // The bot's chat is looked up when it opens.
        assertNull(link.storedSessionId)
        assertEquals(draft, link.draft)
    }

    @Test
    fun onlyTheLatestLinkIsKeptAndConsumedOnce() {
        val links = ChatLinks()
        links.newChat(ComposeDraft(text = "first"))
        val first = assertNotNull(links.pending.value)
        links.newChat(ComposeDraft(text = "second"))
        links.consume(first)
        assertEquals("second", links.pending.value?.draft?.text)

        links.consume(assertNotNull(links.pending.value))
        assertNull(links.pending.value)
    }
}
