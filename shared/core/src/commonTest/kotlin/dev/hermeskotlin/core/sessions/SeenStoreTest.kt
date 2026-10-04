package dev.hermeskotlin.core.sessions

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
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
}
