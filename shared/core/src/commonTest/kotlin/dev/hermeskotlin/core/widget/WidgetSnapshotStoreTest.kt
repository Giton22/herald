package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** T1 (`docs/WIDGETS-SPEC.md` §11): round-trips, scope isolation, the two forget calls, the size guard. */
class WidgetSnapshotStoreTest {

    private val g1 = "https://one.example.ts.net"
    private val g2 = "https://two.example.ts.net"

    private fun now(gateway: String = g1, profile: String? = "p1") = NowWidgetSnapshot(
        gatewayUrl = gateway,
        profile = profile,
        needsYou = listOf(
            NowNeedsYouItem(
                kind = NowNeedsYouKind.ChatWaiting,
                title = "Deploy",
                target = NowTarget(storedSessionId = "s1", title = "Deploy"),
            ),
        ),
        savedAtEpochSeconds = 100,
    )

    @Test
    fun everyWidgetRoundTrips() = runTest {
        val store = WidgetSnapshotStore(InMemoryKeyValueStore())
        store.saveNow(now())
        assertEquals(now(), store.readNow(g1, "p1"))

        store.saveChats(ChatsWidgetSnapshot(g1, "p1", rows = listOf(chatRow()), savedAtEpochSeconds = 200))
        assertEquals("s1", store.readChats(g1, "p1")?.rows?.single()?.storedSessionId)

        store.saveKanban(kanbanSnapshot())
        assertEquals("waypoint", store.readKanban(g1, "waypoint")?.boardSlug)

        store.saveScheduled(ScheduledWidgetSnapshot(g1, rows = listOf(scheduledRow()), savedAtEpochSeconds = 300))
        assertEquals("j1", store.readScheduled(g1)?.rows?.single()?.jobId)
    }

    @Test
    fun aScopeNeverShadowsAnother() = runTest {
        val store = WidgetSnapshotStore(InMemoryKeyValueStore())
        store.saveNow(now(g1, "p1"))
        assertNull(store.readNow(g1, "p2"), "another profile on the same gateway")
        assertNull(store.readNow(g2, "p1"), "the same profile on another gateway")
        assertNull(store.readChats(g1, "p1"), "another widget in the same scope")

        store.saveNow(now(g1, "p1"))
        store.saveKanban(kanbanSnapshot(g1, "board-two"))
        assertNull(store.readKanban(g1, "waypoint"), "another board on the same gateway")
        store.saveKanban(kanbanSnapshot())
        assertEquals("waypoint", store.readKanban(g1, "waypoint")?.boardSlug)
    }

    @Test
    fun forgetGatewayRemovesEveryWidgetKeyForItAlone() = runTest {
        val store = WidgetSnapshotStore(InMemoryKeyValueStore())
        store.saveNow(now(g1, "p1"))
        store.saveNow(now(g1, "p2"))
        store.saveChats(ChatsWidgetSnapshot(g1, "p1", savedAtEpochSeconds = 1))
        store.saveKanban(kanbanSnapshot())
        store.saveScheduled(ScheduledWidgetSnapshot(g1, savedAtEpochSeconds = 2))
        store.saveNow(now(g2, "p1"))

        store.forgetGateway(g1)

        assertNull(store.readNow(g1, "p1"))
        assertNull(store.readNow(g1, "p2"))
        assertNull(store.readChats(g1, "p1"))
        assertNull(store.readKanban(g1, "waypoint"))
        assertNull(store.readScheduled(g1))
        assertNotNull(store.readNow(g2, "p1"), "the other gateway stands")
    }

    @Test
    fun forgetProfileRemovesOnlyThatProfile() = runTest {
        val store = WidgetSnapshotStore(InMemoryKeyValueStore())
        store.saveNow(now(g1, "p1"))
        store.saveNow(now(g1, "p2"))
        store.saveChats(ChatsWidgetSnapshot(g1, "p1", savedAtEpochSeconds = 1))
        store.saveChats(ChatsWidgetSnapshot(g1, "p2", savedAtEpochSeconds = 2))
        store.saveKanban(kanbanSnapshot())
        store.saveScheduled(ScheduledWidgetSnapshot(g1, savedAtEpochSeconds = 3))

        store.forgetProfile(g1, "p1")

        assertNull(store.readNow(g1, "p1"))
        assertNull(store.readChats(g1, "p1"))
        assertNotNull(store.readNow(g1, "p2"), "the other profile stands")
        assertNotNull(store.readChats(g1, "p2"))
        assertNotNull(store.readKanban(g1, "waypoint"), "board-scoped widgets have no profile to forget")
        assertNotNull(store.readScheduled(g1))
    }

    @Test
    fun anOverLimitSnapshotIsRefusedAndThePreviousValueStands() = runTest {
        val store = WidgetSnapshotStore(InMemoryKeyValueStore())
        store.saveNow(now())
        val huge = now().copy(
            needsYou = List(4_000) { index ->
                NowNeedsYouItem(
                    kind = NowNeedsYouKind.ChatWaiting,
                    title = "chat $index ".repeat(20),
                    target = NowTarget(storedSessionId = "s$index", title = "chat $index"),
                )
            },
        )
        store.saveNow(huge)
        assertEquals(now(), store.readNow(g1, "p1"))
    }

    private fun chatRow() = ChatWidgetRow(storedSessionId = "s1", title = "Deploy")

    private fun kanbanSnapshot(gateway: String = g1, slug: String = "waypoint") = KanbanWidgetSnapshot(
        gatewayUrl = gateway,
        boardSlug = slug,
        boardName = "Waypoint",
        counts = mapOf("running" to 1, "blocked" to 0, "review" to 0, "ready" to 2),
        rows = listOf(KanbanWidgetRow(taskId = "t1", title = "Ship it", status = "ready")),
        savedAtEpochSeconds = 150,
    )

    private fun scheduledRow() = ScheduledWidgetRow(jobId = "j1", displayName = "Nightly", state = "scheduled")
}
