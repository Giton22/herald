package dev.hermeskotlin.core.sessions

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeenStoreTest {

    private val home = GatewayUrl.parse("https://hermes.example.ts.net")
    private var clock = 1_000.0

    private fun session(id: String, at: Double, active: Boolean = false) = SessionSummary(id, lastActive = at, isActive = active)

    @Test
    fun historyFromBeforeTrackingStartedIsRead() = runTest {
        val seen = SeenStore(InMemoryKeyValueStore()) { clock }.seen(home).first()

        assertFalse(seen.isUnread(session("old", at = 900.0)))
        assertTrue(seen.isUnread(session("new", at = 1_100.0)))
    }

    @Test
    fun readUpToIsWhereTheUserLastLookedAndOnlyWhenSomethingIsNew() = runTest {
        val store = SeenStore(InMemoryKeyValueStore()) { clock }
        store.markSeen(home, session("s1", at = 1_200.0))
        val seen = store.seen(home).first()

        assertEquals(1_200.0, seen.readUpTo(session("s1", at = 1_300.0)))
        assertNull(seen.readUpTo(session("s1", at = 1_200.0)))
        // Never opened on this phone: everything since tracking started is new.
        assertEquals(1_000.0, seen.readUpTo(session("s2", at = 1_100.0)))
    }

    @Test
    fun aChatIsReadUntilItHasNewActivity() = runTest {
        val store = SeenStore(InMemoryKeyValueStore()) { clock }
        store.markSeen(home, session("s1", at = 1_200.0))

        assertFalse(store.seen(home).first().isUnread(session("s1", at = 1_200.0)))
        assertTrue(store.seen(home).first().isUnread(session("s1", at = 1_300.0)))
    }

    @Test
    fun aChatTheGatewayStillCallsActiveCanHaveAnUnreadReply() = runTest {
        val seen = SeenStore(InMemoryKeyValueStore()) { clock }.seen(home).first()

        assertTrue(seen.isUnread(session("s1", at = 1_500.0, active = true)))
    }

    @Test
    fun whatWasSeenSurvivesARestart() = runTest {
        val disk = InMemoryKeyValueStore()
        SeenStore(disk) { clock }.markSeen(home, session("s1", at = 1_200.0))
        clock = 5_000.0

        val seen = SeenStore(disk) { clock }.seen(home).first()

        assertFalse(seen.isUnread(session("s1", at = 1_200.0)))
        // The baseline is the first launch's, not this one's.
        assertTrue(seen.isUnread(session("s2", at = 1_300.0)))
    }

    @Test
    fun aChatOpenedFromOutsideTheListGetsTheSameLastLookAsFromTheList() = runTest {
        val store = SeenStore(InMemoryKeyValueStore()) { clock }
        store.markSeen(home, session("s1", at = 1_200.0))
        val inList = session("s1", at = 1_500.0)

        // A notification has only the chat's id, not its summary: same answer as the list's.
        assertEquals(store.seen(home).first().readUpTo(inList), store.lastLook(home, "s1"))
        assertEquals(1_200.0, store.lastLook(home, "s1"))
    }

    @Test
    fun aChatNeverOpenedHereWasLastLookedAtWhenTrackingBegan() = runTest {
        val store = SeenStore(InMemoryKeyValueStore()) { clock }

        assertEquals(1_000.0, store.lastLook(home, "never-opened"))
    }

    @Test
    fun eachProfileKeepsItsOwnLastLook() = runTest {
        val store = SeenStore(InMemoryKeyValueStore()) { clock }
        store.markSeen(home, session("s1", at = 1_200.0), profile = "scribe")

        assertEquals(1_200.0, store.lastLook(home, "s1", profile = "scribe"))
        assertEquals(1_000.0, store.lastLook(home, "s1"))
    }

    @Test
    fun leavingTheAppOnAChatRecordsWhatWasOnScreenAsRead() = runTest {
        val store = SeenStore(InMemoryKeyValueStore()) { clock }
        store.markLooked(home, "s1", at = 1_300.0)

        assertEquals(1_300.0, store.lastLook(home, "s1"))
        // The reply that comes in afterwards is new.
        assertTrue(store.seen(home).first().isUnread(session("s1", at = 1_400.0)))
    }

    @Test
    fun aLastLookNeverMovesBack() = runTest {
        val store = SeenStore(InMemoryKeyValueStore()) { clock }
        store.markLooked(home, "s1", at = 1_300.0)
        store.markLooked(home, "s1", at = 1_100.0)

        assertEquals(1_300.0, store.lastLook(home, "s1"))
    }

    private fun from(source: String?, id: String, at: Double, unread: Boolean = false) =
        SessionSummary(id, source = source, lastActive = at, unread = unread)

    @Test
    fun aChatFromOutsideTheGatewaysClientsIsReadWhereItCameIn() = runTest {
        val seen = SeenStore(InMemoryKeyValueStore()) { clock }.seen(home).first()

        assertFalse(seen.isUnread(from("telegram", "tg", at = 1_100.0)))
        // A plugin's bridge with a source id of its own is outside too.
        assertFalse(seen.isUnread(from("my-discord-bridge", "bridge", at = 1_100.0)))
        assertNull(seen.readUpTo(from("telegram", "tg", at = 1_100.0)))
        // Herald's and Desktop's own chats, and rows without a source, still get their replies marked.
        assertTrue(seen.isUnread(from("desktop", "app", at = 1_100.0)))
        assertTrue(seen.isUnread(from("cli", "cli", at = 1_100.0)))
        assertTrue(seen.isUnread(from(null, "legacy", at = 1_100.0)))
    }

    @Test
    fun theGatewaysReadStateMarksAMessagingChatUntilItIsLookedAtHere() = runTest {
        val store = SeenStore(InMemoryKeyValueStore()) { clock }
        val flagged = from("telegram", "tg", at = 1_100.0, unread = true)

        assertTrue(store.seen(home).first().isUnread(flagged))
        assertEquals(1_000.0, store.seen(home).first().readUpTo(flagged))

        store.markSeen(home, flagged)

        // The list may still carry the flag until it is read again: the look here outranks it.
        assertFalse(store.seen(home).first().isUnread(flagged))
        assertTrue(store.seen(home).first().isUnread(from("telegram", "tg", at = 1_200.0, unread = true)))
    }

    @Test
    fun aTurnLeftRunningMarksItsReplyEvenInAMessagingChat() = runTest {
        val disk = InMemoryKeyValueStore()
        val store = SeenStore(disk) { clock }
        store.markLooked(home, "tg", at = 1_100.0, running = true)

        // The reply lands after the user left, and stays new across a restart.
        assertTrue(SeenStore(disk) { clock }.seen(home).first().isUnread(from("telegram", "tg", at = 1_150.0)))

        // Looking at it again, with no turn running, ends that.
        store.markLooked(home, "tg", at = 1_150.0)
        assertFalse(store.seen(home).first().isUnread(from("telegram", "tg", at = 1_300.0)))
    }

    @Test
    fun leavingAfterTheTurnEndedClearsTheRunningMarkEvenWithoutNewMessages() = runTest {
        val store = SeenStore(InMemoryKeyValueStore()) { clock }
        store.markLooked(home, "tg", at = 1_100.0, running = true)
        store.markLooked(home, "tg", at = 1_100.0)

        assertFalse(store.seen(home).first().isUnread(from("telegram", "tg", at = 1_300.0)))
    }

    @Test
    fun sourcesOutsideTheGatewaysClients() {
        assertTrue(isExternalSource("telegram"))
        assertTrue(isExternalSource(" Discord "))
        assertTrue(isExternalSource("api_server"))
        assertTrue(isExternalSource("some-plugin"))
        assertFalse(isExternalSource("desktop"))
        assertFalse(isExternalSource("tui"))
        assertFalse(isExternalSource("cron"))
        assertFalse(isExternalSource("unknown"))
        assertFalse(isExternalSource(""))
        assertFalse(isExternalSource(null))
    }
}
