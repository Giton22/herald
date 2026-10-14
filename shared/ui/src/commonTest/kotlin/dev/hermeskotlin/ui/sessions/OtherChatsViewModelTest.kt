package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.AttentionTracker
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.DraftStore
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.projects.ProjectsApi
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.ActiveSessions
import dev.hermeskotlin.core.sessions.SeenStore
import dev.hermeskotlin.core.sessions.SessionListFilter
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.threads.ThreadsApi
import dev.hermeskotlin.core.threads.ThreadsRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The Sessions badge, the banner and Next, as [OtherChatsViewModel] works them out from the list and the live statuses. */
@OptIn(ExperimentalCoroutinesApi::class)
class OtherChatsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Each listed chat's `last_active` (epoch seconds); the user last looked at every chat at [LOOKED]. */
    private val activity = linkedMapOf("a" to 500.0, "b" to 500.0, "c" to 500.0)

    /** What each chat's newest reply says, for the banner's preview. */
    private val replies = mutableMapOf("b" to "**Done**, all `tests` green.", "bot-b" to "Walked the dog.")

    /** Each preview read: the chat and the profile it was asked in. */
    private val previewReads = mutableListOf<Pair<String, String?>>()

    /** How often the chat list was read. */
    private var listReads = 0

    /** A preview read waits on this, when set. */
    private var previewGate: CompletableDeferred<Unit>? = null

    /** A gateway socket that says it's ready and answers `session.active_list` from [live] (stored id to status). */
    private class FakeSocket(val live: MutableMap<String, String> = mutableMapOf()) : RpcTransport {
        /** How often the live list was asked for. */
        var polls = 0

        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }

        override val incoming: Flow<String> = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            val id = message["id"] ?: return
            val result = if (message["method"]?.jsonPrimitive?.content == "session.active_list") {
                polls++
                live.entries.joinToString(",", """{"sessions":[""", "]}") { (key, status) -> """{"id":"rt-$key","session_key":"$key","status":"$status"}""" }
            } else {
                "{}"
            }
            inbound.send("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }

        override suspend fun close(code: Short, reason: String) {
            inbound.close()
        }
    }

    private fun TestScope.viewModel(socket: FakeSocket = FakeSocket()): OtherChatsViewModel {
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/sessions" -> {
                    listReads++
                    respond(
                        activity.entries.joinToString(",", """{"sessions":[""", """],"total":${activity.size}}""") { (id, at) ->
                            """{"id":"$id","title":"${id.uppercase()}","last_active":$at}"""
                        },
                        HttpStatusCode.OK, json,
                    )
                }
                path.endsWith("/messages") -> {
                    val id = path.removePrefix("/api/sessions/").removeSuffix("/messages")
                    previewReads += id to request.url.parameters["profile"]
                    previewGate?.await()
                    val text = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(replies[id].orEmpty()))
                    respond("""{"session_id":"$id","messages":[{"role":"user","content":"go"},{"role":"assistant","content":$text}]}""", HttpStatusCode.OK, json)
                }
                path == "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                else -> respond("""{"display_name":"Me"}""", HttpStatusCode.OK, json)
            }
        }
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine(config), cookies)
        val auth = AuthApi(client, cookies)
        // In the background: the gateway's live list is asked every 10 s while anything collects it, so these
        // would never let the test run idle.
        val scope = CoroutineScope(backgroundScope.coroutineContext)
        val connection = GatewayConnection(auth, { _, _ -> socket }, scope)
        connection.start(gateway.gatewayUrl)
        val attention = AttentionTracker(connection, ChatHost(connection, SessionsApi(client), scope), ActiveSessions(connection, scope), scope)
        sessions = SessionsViewModel(
            SessionsApi(client), auth, connection, LastChatStore(InMemoryKeyValueStore()), ProfilesApi(client),
            attention, SeenStore(InMemoryKeyValueStore()) { LOOKED }, DraftStore(InMemoryKeyValueStore()), ProjectsApi(connection),
            ThreadsRepository(ThreadsApi(connection), SessionsApi(client), ProfilesApi(client)),
        )
        val vm = OtherChatsViewModel(sessions, SessionsApi(client), connection, attention)
        val scheduler = testScheduler
        val clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(START_MILLIS + scheduler.currentTime)
        }
        sessions.clock = clock
        vm.clock = clock
        return vm
    }

    /** The sidebar's list, which [viewModel] reads: bound, read again and switched to the archive here. */
    private lateinit var sessions: SessionsViewModel

    /**
     * Bound, with "Alerts for other chats" on ([banner] too, unless said) and the chat "a" on screen, past the
     * settling: what's there now is taken stock of.
     */
    private fun TestScope.watching(socket: FakeSocket = FakeSocket(), banner: Boolean = true): OtherChatsViewModel {
        val vm = viewModel(socket)
        sessions.bind(gateway)
        vm.setOpenChat(setOf("a"))
        vm.setOtherChats(enabled = true, banner = banner)
        vm.setChatShown(true)
        advanceTimeBy(SETTLED)
        return vm
    }

    /** [id] has a reply the user hasn't seen, as the next list read says. */
    private fun TestScope.replyIn(id: String) {
        activity[id] = LOOKED + 100
        sessions.refresh()
        advanceTimeBy(QUIET)
    }

    @Test
    fun aReplyShowsAfterItSettlesWithItsPreview() {
        runTest(dispatcher) {
            val vm = watching()
            assertNull(vm.banner.value)
            activity["b"] = LOOKED + 100
            sessions.refresh()
            // The list and the live statuses get a moment to agree first: a turn just starting isn't a reply.
            advanceTimeBy(1_000)
            assertNull(vm.banner.value)
            advanceTimeBy(1_000)
            val banner = assertNotNull(vm.banner.value)
            assertEquals("Reply in B", banner.message)
            assertEquals("Done, all tests green.", banner.preview)
            assertEquals(listOf<Pair<String, String?>>("b" to null), previewReads)
            assertEquals(SessionsBadge(replies = 1), vm.badge.value)
            assertEquals("b", vm.nextChat()?.id)
        }
    }

    @Test
    fun whatWasAlreadyUnreadWhenTheChatCameOnScreenIsNoBanner() {
        runTest(dispatcher) {
            activity["b"] = LOOKED + 100
            val vm = watching()
            advanceTimeBy(QUIET)
            assertNull(vm.banner.value)
            assertEquals(SessionsBadge(), vm.badge.value)
            assertNull(vm.nextChat())
        }
    }

    @Test
    fun aBannerCanBeDismissedAsItWasBeforeItsPreviewCame() {
        runTest(dispatcher) {
            val vm = watching()
            replyIn("b")
            val filled = assertNotNull(vm.banner.value)
            assertNotNull(filled.preview)
            // The screen still holds the event from before the preview filled in.
            vm.dismissBanner(filled.copy(preview = null))
            assertNull(vm.banner.value)
            // The badge stays: the reply is still unread.
            assertEquals(SessionsBadge(replies = 1), vm.badge.value)
        }
    }

    @Test
    fun theOpenChatNeverGetsABanner() {
        runTest(dispatcher) {
            val vm = watching()
            replyIn("a")
            assertNull(vm.banner.value)
            assertEquals(SessionsBadge(), vm.badge.value)
            assertTrue(previewReads.isEmpty())
        }
    }

    @Test
    fun openingTheChatTakesItsBannerAndBadgeAwayAtOnce() {
        runTest(dispatcher) {
            val vm = watching()
            replyIn("b")
            assertNotNull(vm.banner.value)
            // Opened from the sidebar: no waiting for the next look, or Next would go back to it.
            vm.setOpenChat(setOf("b"))
            assertNull(vm.banner.value)
            assertEquals(SessionsBadge(), vm.badge.value)
            assertNull(vm.nextChat())
        }
    }

    @Test
    fun anotherProfileOrGatewayClearsTheBannerAndTheBadge() {
        runTest(dispatcher) {
            val vm = watching()
            replyIn("b")
            assertNotNull(vm.banner.value)
            sessions.bind(gateway, profile = "work")
            assertNull(vm.banner.value)
            assertEquals(SessionsBadge(), vm.badge.value)
            assertNull(vm.nextChat())
            // Its own chats, as they are, are only taken stock of.
            advanceTimeBy(SETTLED)
            assertNull(vm.banner.value)
            assertEquals(SessionsBadge(), vm.badge.value)
        }
    }

    @Test
    fun aPreviewLandingAfterASwitchIsDropped() {
        runTest(dispatcher) {
            val vm = watching()
            previewGate = CompletableDeferred()
            replyIn("b")
            assertEquals(null, assertNotNull(vm.banner.value).preview)
            sessions.bind(gateway, profile = "work")
            previewGate?.complete(Unit)
            advanceTimeBy(SETTLED)
            assertNull(vm.banner.value)
        }
    }

    @Test
    fun theArchiveShowingKeepsWhatIsNews() {
        runTest(dispatcher) {
            val vm = watching()
            replyIn("b")
            assertEquals(SessionsBadge(replies = 1), vm.badge.value)
            // The sidebar beside the chat goes to Archived and back: the recent chats didn't go anywhere.
            sessions.setFilter(SessionListFilter.Archived)
            advanceTimeBy(QUIET)
            assertEquals(SessionsBadge(replies = 1), vm.badge.value)
            assertNotNull(vm.banner.value)
            sessions.setFilter(SessionListFilter.Recent)
            advanceTimeBy(QUIET)
            assertEquals(SessionsBadge(replies = 1), vm.badge.value)
            assertEquals("b", vm.nextChat()?.id)
        }
    }

    @Test
    fun aChatOpenedFromTheBannerWhileTheArchiveShowsStartsAtItsNewMessages() {
        runTest(dispatcher) {
            val vm = watching()
            replyIn("b")
            sessions.setFilter(SessionListFilter.Archived)
            advanceTimeBy(QUIET)
            assertEquals(LOOKED, vm.openListed("b"))
        }
    }

    @Test
    fun aRequestShowsAtOnceAndOutranksAReply() {
        runTest(dispatcher) {
            val socket = FakeSocket()
            val vm = watching(socket)
            activity["c"] = LOOKED + 100
            socket.live["b"] = "waiting"
            sessions.refresh()
            advanceTimeBy(15_000)
            val banner = assertNotNull(vm.banner.value)
            assertEquals("b", banner.chatId)
            assertTrue(banner.waiting)
            assertEquals(SessionsBadge(waiting = 1, replies = 1), vm.badge.value)
            assertEquals("b", vm.nextChat()?.id)
        }
    }

    private fun botRow(unread: Boolean) = OtherChatRow(
        OtherChat("bot-b", "Rex", unread = unread, activityAt = LOOKED + 100, bot = "rex"),
        ids = setOf("bot-a", "bot-b"),
    )

    @Test
    fun aBotChatsPreviewIsReadInTheBotsProfile() {
        runTest(dispatcher) {
            val vm = watching()
            vm.setBotChats(listOf(botRow(unread = false)))
            advanceTimeBy(QUIET)
            vm.setBotChats(listOf(botRow(unread = true)))
            advanceTimeBy(QUIET)
            val banner = assertNotNull(vm.banner.value)
            assertEquals("Reply in Rex", banner.message)
            assertEquals("rex", banner.bot)
            assertEquals("Walked the dog.", banner.preview)
            assertEquals(listOf<Pair<String, String?>>("bot-b" to "rex"), previewReads)
        }
    }

    @Test
    fun aBotChatOpenByItsOlderIdIsTheOpenChat() {
        runTest(dispatcher) {
            val vm = watching()
            vm.setOpenChat(setOf("bot-a"))
            vm.setBotChats(listOf(botRow(unread = false)))
            advanceTimeBy(QUIET)
            vm.setBotChats(listOf(botRow(unread = true)))
            advanceTimeBy(QUIET)
            assertNull(vm.banner.value)
            assertEquals(SessionsBadge(), vm.badge.value)
        }
    }

    @Test
    fun botChatsStillCountAfterAProfileSwitch() {
        runTest(dispatcher) {
            val vm = watching()
            vm.setBotChats(listOf(botRow(unread = false)))
            // Bots belong to the gateway: the roster, and so this list, isn't handed over again for a profile.
            sessions.bind(gateway, profile = "work")
            advanceTimeBy(SETTLED)
            vm.setBotChats(listOf(botRow(unread = true)))
            advanceTimeBy(QUIET)
            assertEquals("bot-b", vm.banner.value?.chatId)
        }
    }

    @Test
    fun nothingIsWatchedWhileTheChatIsOutOfSight() {
        runTest(dispatcher) {
            val vm = watching()
            vm.setChatShown(false)
            replyIn("b")
            assertNull(vm.banner.value)
            // Back: the reply came while away, so it's on the badge, though no banner tells of it.
            vm.setChatShown(true)
            advanceTimeBy(SETTLED)
            assertNull(vm.banner.value)
            assertEquals(SessionsBadge(replies = 1), vm.badge.value)
        }
    }

    @Test
    fun withAlertsOffNothingIsWatchedPolledOrRead() {
        runTest(dispatcher) {
            val socket = FakeSocket()
            val vm = viewModel(socket)
            sessions.bind(gateway)
            vm.setOpenChat(setOf("a"))
            // The switch is off by default: the chat on screen alone watches nothing.
            vm.setChatShown(true)
            advanceTimeBy(SETTLED)
            replyIn("b")
            advanceTimeBy(30_000)
            assertNull(vm.banner.value)
            assertEquals(SessionsBadge(), vm.badge.value)
            assertEquals(0, socket.polls)
            assertTrue(previewReads.isEmpty())
        }
    }

    @Test
    fun turningAlertsOffForgetsWhatWasNews() {
        runTest(dispatcher) {
            val vm = watching()
            replyIn("b")
            assertNotNull(vm.banner.value)
            vm.setOtherChats(enabled = false, banner = true)
            assertNull(vm.banner.value)
            assertEquals(SessionsBadge(), vm.badge.value)
            // On again: what was there is only taken stock of.
            vm.setOtherChats(enabled = true, banner = true)
            advanceTimeBy(SETTLED)
            assertNull(vm.banner.value)
            assertEquals(SessionsBadge(), vm.badge.value)
        }
    }

    @Test
    fun withTheBannerOffAReplyOnlyCountsOnTheBadge() {
        runTest(dispatcher) {
            val vm = watching(banner = false)
            replyIn("b")
            assertNull(vm.banner.value)
            assertEquals(SessionsBadge(replies = 1), vm.badge.value)
            assertTrue(previewReads.isEmpty())
        }
    }

    @Test
    fun comingBackSoonAfterDoesNotReadTheListAgain() {
        runTest(dispatcher) {
            val vm = watching()
            val reads = listReads
            // Settings opened and closed a few seconds later.
            vm.setChatShown(false)
            advanceTimeBy(5_000)
            vm.setChatShown(true)
            advanceTimeBy(SETTLED)
            assertEquals(reads, listReads)
            // Still watched: a reply after it shows.
            replyIn("b")
            assertNotNull(vm.banner.value)
            // Away for longer, the list is read again.
            vm.setChatShown(false)
            advanceTimeBy(60_000)
            vm.setChatShown(true)
            advanceTimeBy(SETTLED)
            assertTrue(listReads > reads + 1)
        }
    }

    @Test
    fun anArchiveReadWhileAwayDoesNotStandForTheRecentChats() {
        runTest(dispatcher) {
            val vm = watching()
            sessions.setFilter(SessionListFilter.Archived)
            advanceTimeBy(QUIET)
            vm.setChatShown(false)
            // "b" replies while Settings shows; the change read is the archive's, which says nothing of "b".
            replyIn("b")
            val reads = listReads
            vm.setChatShown(true)
            advanceTimeBy(SETTLED)
            // Read again on the way back, so the reply is on the badge at once, not a banner later.
            assertTrue(listReads > reads)
            assertEquals(SessionsBadge(replies = 1), vm.badge.value)
            assertNull(vm.banner.value)
        }
    }

    private companion object {
        /** When the user last looked at every chat (epoch seconds). */
        const val LOOKED = 1_000.0

        /** The test clock's start: after [LOOKED], so time away counts from then. */
        const val START_MILLIS = 1_000_000L

        /** Past the reconnect's settling (3 s) and the other chats' (1.5 s), with room for the list read. */
        const val SETTLED = 6_000L

        /** Past the other chats' settling. */
        const val QUIET = 2_000L
    }
}
