package dev.hermeskotlin.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppLinkTest {

    @Test
    fun aVoiceChatLinkIsRecognized() {
        assertEquals(AppLink.VoiceChat, AppLink.parse("hermes://voice-chat"))
        assertEquals(AppLink.VoiceChat, AppLink.parse("HERMES://Voice-Chat/?from=launcher#top"))
        assertNull(AppLink.parse("hermes://voice-chat/extra"))
    }

    @Test
    fun readsEachKindOfLink() {
        assertEquals(AppLink.NewChat, AppLink.parse("hermes://new-chat"))
        assertEquals(AppLink.NewChatVoice, AppLink.parse("hermes://new-chat-voice"))
        assertEquals(AppLink.Session("20260105_101500_ab12"), AppLink.parse("hermes://session/20260105_101500_ab12"))
        assertEquals(AppLink.Bot("researcher"), AppLink.parse("hermes://bot/researcher"))
    }

    @Test
    fun theDesktopsOwnSchemeReadsTheSame() {
        assertEquals(AppLink.Session("s1"), AppLink.parse("herald://session/s1"))
        assertEquals(AppLink.NewChat, AppLink.parse("Herald://new-chat"))
        assertNull(AppLink.parse("heraldx://new-chat"))
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

    @Test
    fun readsTheWidgetLinks() {
        assertEquals(AppLink.Kanban, AppLink.parse("hermes://kanban"))
        assertEquals(AppLink.KanbanTask("waypoint", "t_1"), AppLink.parse("hermes://kanban/waypoint/t_1"))
        assertEquals(AppLink.Scheduled, AppLink.parse("hermes://scheduled"))
        assertEquals(AppLink.ScheduledJob("job-1"), AppLink.parse("hermes://scheduled/job-1"))
        assertEquals(AppLink.Now, AppLink.parse("hermes://now"))
    }

    @Test
    fun widgetLinksTolerateTrailingSlashesQueriesAndCase() {
        assertEquals(AppLink.Kanban, AppLink.parse("HERMES://Kanban/?from=widget"))
        assertEquals(AppLink.KanbanTask("b", "t"), AppLink.parse("hermes://kanban/b/t/#top"))
        assertEquals(AppLink.Scheduled, AppLink.parse("herald://Scheduled/"))
        assertEquals(AppLink.Now, AppLink.parse("herald://now"))
    }

    @Test
    fun widgetLinksDecodeEscapedIds() {
        assertEquals(AppLink.KanbanTask("my board", "t 1"), AppLink.parse("hermes://kanban/my%20board/t%201"))
        assertEquals(AppLink.ScheduledJob("job/2"), AppLink.parse("hermes://scheduled/job%2F2"))
    }

    @Test
    fun malformedWidgetLinksParseToNull() {
        // A board-only kanban link is deliberately not one: a board tap always carries its task id.
        assertNull(AppLink.parse("hermes://kanban/waypoint"))
        assertNull(AppLink.parse("hermes://kanban/a/b/c"))
        assertNull(AppLink.parse("hermes://kanban//t"))
        assertNull(AppLink.parse("hermes://kanban/b/"))
        assertNull(AppLink.parse("hermes://scheduled/a/b"))
        assertNull(AppLink.parse("hermes://now/x"))
        assertNull(AppLink.parse("hermes://nowhere"))
    }
}
