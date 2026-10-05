package dev.hermeskotlin.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppLinkTest {

    @Test
    fun readsEachKindOfLink() {
        assertEquals(AppLink.NewChat, AppLink.parse("hermes://new-chat"))
        assertEquals(AppLink.NewChatVoice, AppLink.parse("hermes://new-chat-voice"))
        assertEquals(AppLink.Session("20260105_101500_ab12"), AppLink.parse("hermes://session/20260105_101500_ab12"))
        assertEquals(AppLink.Bot("researcher"), AppLink.parse("hermes://bot/researcher"))
    }

    @Test
    fun toleratesTrailingSlashesQueriesAndCase() {
        assertEquals(AppLink.NewChat, AppLink.parse("hermes://new-chat/"))
        assertEquals(AppLink.NewChat, AppLink.parse("HERMES://New-Chat?from=widget"))
        assertEquals(AppLink.Session("s1"), AppLink.parse("hermes://session/s1/#top"))
    }

    @Test
    fun decodesEscapedIds() {
        assertEquals(AppLink.Session("my chat"), AppLink.parse("hermes://session/my%20chat"))
        assertEquals(AppLink.Session("café"), AppLink.parse("hermes://session/caf%C3%A9"))
        assertEquals(AppLink.Session("a%zz"), AppLink.parse("hermes://session/a%zz"))
        assertEquals("a😀b", AppLink.percentDecode("a😀%62"))
    }

    @Test
    fun ignoresOtherLinks() {
        assertNull(AppLink.parse("https://example.com/new-chat"))
        assertNull(AppLink.parse("hermes://settings"))
        assertNull(AppLink.parse("hermes://session/"))
        assertNull(AppLink.parse("hermes://session/a/b"))
        assertNull(AppLink.parse("hermes://session"))
        assertNull(AppLink.parse("hermes://bot/"))
        assertNull(AppLink.parse("hermes://bot/a/b"))
        assertNull(AppLink.parse("hermes://new-chat/extra"))
        assertNull(AppLink.parse("not a link"))
    }
}
