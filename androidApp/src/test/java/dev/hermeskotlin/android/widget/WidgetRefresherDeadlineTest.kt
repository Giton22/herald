package dev.hermeskotlin.android.widget

import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.widget.ChatsWidgetSnapshot
import dev.hermeskotlin.core.widget.KanbanWidgetSnapshot
import dev.hermeskotlin.core.widget.WidgetDataAssembler
import dev.hermeskotlin.core.widget.NowNeedsYouItem
import dev.hermeskotlin.core.widget.NowNeedsYouKind
import dev.hermeskotlin.core.widget.NowTarget
import dev.hermeskotlin.core.widget.NowWidgetSnapshot
import dev.hermeskotlin.core.widget.ScheduledWidgetSnapshot
import dev.hermeskotlin.core.widget.WidgetRefreshPipelines
import dev.hermeskotlin.core.widget.WidgetSnapshotStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * B4 + B5 (docs/WIDGETS-SPEC.md D14, D26): the refresh pass honours its deadline — a hung pipeline
 * cancels instead of being swallowed, nothing overwrites the stored snapshots, and every widget
 * falls back cleanly. Cold refreshes take the D14 merge path with the known/saved roster; live
 * refreshes never do.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = WidgetTestApp::class)
class WidgetRefresherDeadlineTest {

    private val url = GatewayUrl.parse("https://gw.example.ts.net")

    private class FakePipelines(
        val now: NowWidgetSnapshot,
        val hang: Boolean = false,
    ) : WidgetRefreshPipelines {
        var nowCalls = 0
        var nowColdCalls = 0
        var allColdCalls = 0
        var chatsCalls = 0
        var coldKnownBots: List<Bot>? = null

        override suspend fun refreshAllCold(
            url: GatewayUrl,
            profile: String?,
            knownBots: List<Bot>,
            threadIds: Set<String>,
        ): WidgetDataAssembler.AllWidgets {
            allColdCalls++
            coldKnownBots = knownBots
            return WidgetDataAssembler.AllWidgets(
                now = now,
                chats = ChatsWidgetSnapshot(url.value, profile, rows = emptyList(), savedAtEpochSeconds = 1),
                kanban = null,
                scheduled = ScheduledWidgetSnapshot(url.value, rows = emptyList(), savedAtEpochSeconds = 1),
            )
        }

        override suspend fun refreshNow(url: GatewayUrl, profile: String?, threadIds: Set<String>): NowWidgetSnapshot {
            nowCalls++
            if (hang) awaitCancellation()
            return now
        }

        override suspend fun refreshNowCold(
            url: GatewayUrl,
            profile: String?,
            knownBots: List<Bot>,
            threadIds: Set<String>,
        ): NowWidgetSnapshot {
            nowColdCalls++
            coldKnownBots = knownBots
            return now
        }

        override suspend fun refreshChats(url: GatewayUrl, profile: String?, threadIds: Set<String>): ChatsWidgetSnapshot {
            chatsCalls++
            return ChatsWidgetSnapshot(url.value, profile, rows = emptyList(), savedAtEpochSeconds = 1)
        }

        override suspend fun refreshKanban(url: GatewayUrl): KanbanWidgetSnapshot? = null

        override suspend fun refreshScheduled(url: GatewayUrl, live: Boolean): ScheduledWidgetSnapshot =
            ScheduledWidgetSnapshot(url.value, rows = emptyList(), savedAtEpochSeconds = 1)
    }

    private fun gateways(): GatewayRepository {
        val repo = GatewayRepository(InMemoryKeyValueStore())
        kotlinx.coroutines.runBlocking { repo.save(SavedGateway(url = url.value)) }
        return repo
    }

    private fun storedNow() = NowWidgetSnapshot(
        gatewayUrl = url.value,
        profile = null,
        needsYou = listOf(
            NowNeedsYouItem(
                kind = NowNeedsYouKind.ChatWaiting,
                title = "Design review",
                detail = "Needs approval",
                target = NowTarget(storedSessionId = "s-1"),
                carried = true,
            ),
        ),
        socketSavedAtEpochSeconds = 1_700_000_000L,
        savedAtEpochSeconds = 1_700_000_000L,
    )

    @Test
    fun aHungRefreshCancelsAtTheDeadlineAndFallsBackToTheStoredSnapshot() = runTest {
        val store = WidgetSnapshotStore(InMemoryKeyValueStore())
        store.saveNow(storedNow())
        val fake = FakePipelines(now = storedNow(), hang = true)
        val refresher = WidgetRefresher(fake, store, gateways(), deadlineMs = 1_000L)

        val outcome = refresher.refresh(profile = null, connected = true) { "Couldn't refresh · Saved" }

        // The deadline expired: the pass cancelled mid-flight rather than swallowing the
        // CancellationException and continuing into the other pipelines (D26/M4).
        assertEquals(1, fake.nowCalls)
        assertEquals(0, fake.chatsCalls)
        // Nothing was written over the stored snapshot; Now shows it with its saved-notice.
        assertEquals(storedNow(), store.readNow(url.value, null))
        val now = outcome.now as WidgetViews.UiState.Content
        assertEquals(storedNow(), now.snapshot)
        assertEquals("Couldn't refresh · Saved", now.savedNotice)
        assertTrue(outcome.nowCold)
    }

    @Test
    fun aCompletedRefreshWithinTheDeadlineWins() = runTest {
        val store = WidgetSnapshotStore(InMemoryKeyValueStore())
        val fresh = storedNow().copy(savedAtEpochSeconds = 1_800_000_000L)
        val fake = FakePipelines(now = fresh)
        val refresher = WidgetRefresher(fake, store, gateways(), deadlineMs = 60_000L)

        val outcome = refresher.refresh(profile = null, connected = true) { "unused" }

        assertEquals(WidgetViews.UiState.Content(fresh), outcome.now)
        assertEquals(0, fake.nowColdCalls)
        assertEquals(0, fake.allColdCalls)
    }

    @Test
    fun coldRefreshTakesTheD14MergePathWithTheKnownRoster() = runTest {
        val roster = listOf(Bot(name = "researcher"))
        val carried = storedNow()
        val fake = FakePipelines(now = carried)
        val refresher = WidgetRefresher(fake, WidgetSnapshotStore(InMemoryKeyValueStore()), gateways(), knownBots = { roster })

        val outcome = refresher.refresh(profile = null, connected = false) { "unused" }

        // The combined REST-only path ran with the saved roster; no live/socket pipeline ran.
        assertEquals(0, fake.nowCalls)
        assertEquals(0, fake.nowColdCalls)
        assertEquals(0, fake.chatsCalls)
        assertEquals(1, fake.allColdCalls)
        assertEquals(roster, fake.coldKnownBots)
        // …and the carried BotAnswer survives with its socket stamp, for the "as of" line (§6.5).
        val now = outcome.now as WidgetViews.UiState.Content
        assertEquals(carried, now.snapshot)
        assertTrue(now.snapshot.needsYou.single().carried)
        assertTrue(now.snapshot.socketSavedAtEpochSeconds != null)
        assertTrue(outcome.nowCold)
    }

    @Test
    fun coldRefreshLoadsTheSavedGatewayBeforeChoosingSignedOut() = runTest {
        val storage = InMemoryKeyValueStore()
        GatewayRepository(storage).save(SavedGateway(url = url.value))
        // A broadcast-started process has a fresh repository whose StateFlow is still empty.
        val coldGateways = GatewayRepository(storage)
        val fake = FakePipelines(now = storedNow())
        val refresher = WidgetRefresher(fake, WidgetSnapshotStore(InMemoryKeyValueStore()), coldGateways)

        val outcome = refresher.refresh(profile = null, connected = false) { "unused" }

        assertEquals(url.value, outcome.gatewayUrl)
        assertEquals(WidgetViews.UiState.Content(storedNow()), outcome.now)
        assertEquals(1, fake.allColdCalls)
    }
}
