package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.core.sessions.SeenChats
import dev.hermeskotlin.core.sessions.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T2 (`docs/WIDGETS-SPEC.md` §11): ordering, the bot-chat exclusions, `SeenChats` unread, live-map-only fields. */
class ChatsWidgetAssemblerTest {

    private fun session(
        id: String,
        title: String? = "Chat $id",
        pinned: Boolean = false,
        activityAt: Double? = null,
        source: String? = null,
        unread: Boolean = false,
        lastReadAt: Double? = null,
        preview: String? = null,
    ) = SessionSummary(
        id = id,
        title = title,
        preview = preview,
        source = source,
        pinned = pinned,
        lastActive = activityAt,
        unread = unread,
        lastReadAt = lastReadAt,
    )

    private fun assemble(
        sessions: List<SessionSummary>,
        seen: SeenChats? = null,
        waiting: Map<String, Waiting> = emptyMap(),
        running: Map<String, Boolean> = emptyMap(),
        botChatIds: Set<String> = emptySet(),
        savedCopy: Boolean = false,
    ) = ChatsWidgetAssembler.assemble(
        gatewayUrl = "https://gw.example.ts.net",
        profile = "p1",
        sessions = sessions,
        seen = seen,
        waiting = waiting,
        runningChats = running,
        botChatIds = botChatIds,
        threadIds = emptySet(),
        savedCopy = savedCopy,
        nowSeconds = 1_000,
    )

    @Test
    fun pinnedFirstThenActivityNullsLastIdsBreakTies() {
        val snapshot = assemble(
            listOf(
                session("b", activityAt = 500.0),
                session("a", activityAt = 500.0),
                session("no-time"),
                session("pin-old", pinned = true, activityAt = 100.0),
                session("pin-new", pinned = true, activityAt = 900.0),
            ),
        )
        assertEquals(
            listOf("pin-new", "pin-old", "a", "b", "no-time"),
            snapshot.rows.map { it.storedSessionId },
        )
    }

    @Test
    fun botChatsAreExcludedWarmByRosterIdAndColdByTitle() {
        val warm = assemble(
            listOf(session("mine"), session("bot-chat-1", title = "Researcher")),
            botChatIds = setOf("bot-chat-1"),
        )
        assertEquals(listOf("mine"), warm.rows.map { it.storedSessionId })

        // Cold: no roster, so the gateway's own "Bot Chat" title is the only tell.
        val cold = assemble(listOf(session("mine"), session("b1", title = "Bot Chat")))
        assertEquals(listOf("mine"), cold.rows.map { it.storedSessionId })
    }

    @Test
    fun aBlankTitleStillShowsAsUntitled() {
        val snapshot = assemble(listOf(session("s1", title = "  ", preview = null)))
        assertEquals("Untitled session", snapshot.rows.single().title)
    }

    @Test
    fun unreadFollowsSeenChatsNotTheRawFlag() {
        // Explicit mark-unread: the watermark is 0 and the gateway's `unread` flag is false; only
        // SeenChats's watermark rule sees it.
        val marked = session("s1", activityAt = 100.0, lastReadAt = 0.0, unread = false)
        val seen = SeenChats(since = 0.0, chats = mapOf("s1" to 1_000.0))
        assertTrue(assemble(listOf(marked), seen = seen).rows.single().unread)

        // The external-source rule: a Telegram chat's activity alone never counts as unread.
        val telegram = session("s2", activityAt = 900.0, source = "telegram")
        assertFalse(assemble(listOf(telegram), seen = seen).rows.single().unread)

        // A local chat with activity past the local look is unread even when the gateway flag says no.
        val local = session("s3", activityAt = 900.0, source = "desktop")
        assertTrue(assemble(listOf(local), seen = seen).rows.single().unread)
    }

    @Test
    fun waitingAndRunningComeOnlyFromTheLiveMaps() {
        // `is_active` means "loaded"; a loaded chat with no map entry is neither waiting nor running.
        val loaded = SessionSummary(id = "s1", title = "Chat", isActive = true, lastActive = 10.0)
        val cold = assemble(listOf(loaded))
        assertNull(cold.rows.single().waitingLabel)
        assertFalse(cold.rows.single().running)

        // Matched by stored id through the lineage: the map's old id names the compressed chat.
        val compressed = SessionSummary(id = "new", title = "Chat", lineageIds = listOf("old"), lastActive = 10.0)
        val warm = assemble(
            listOf(compressed),
            waiting = mapOf("old" to Waiting.Approval),
            running = mapOf("old" to true),
        )
        assertEquals("Needs approval", warm.rows.single().waitingLabel)
        assertTrue(warm.rows.single().running)
    }

    @Test
    fun rowsCapAtTwelveAndSavedCopyMarksTheOfflineFallback() {
        val many = (1..20).map { session("s$it", activityAt = it.toDouble()) }
        assertEquals(ChatsWidgetAssembler.CAP, assemble(many).rows.size)
        assertTrue(assemble(listOf(session("s1")), savedCopy = true).rows.single().savedCopy)
    }
}
