package dev.hermeskotlin.ui.chat

import androidx.lifecycle.ViewModelStore
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ComposeDraft
import dev.hermeskotlin.core.chat.DraftStart
import dev.hermeskotlin.core.chat.DraftStore
import dev.hermeskotlin.core.chat.OutgoingAttachment
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.journey.JourneyApi
import dev.hermeskotlin.core.media.MediaApi
import dev.hermeskotlin.core.models.ModelsApi
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.pet.PetApi
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.slash.SlashApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.NoDeviceDictation
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceKeepAlive
import dev.hermeskotlin.core.voice.VoiceRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val scope = CoroutineScope(dispatcher)
    private val viewModels = ViewModelStore()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    /** Stops the chat's own coroutines while the test Main is still set, so none outlive the test. */
    @AfterTest fun tearDown() {
        viewModels.clear()
        scope.cancel()
        Dispatchers.resetMain()
    }

    private object NoRecorder : VoiceRecorder {
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording =
            error("not recording in tests")

        override fun finish() = Unit
    }

    private object NoPlayer : SpeechPlayer {
        override suspend fun play(audio: SpokenAudio) = Unit
    }

    /** A gateway socket that says it's ready and answers every call with an empty result. */
    private class ReadyGateway : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }

        override val incoming: Flow<String> = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            val id = Json.parseToJsonElement(text).jsonObject["id"] ?: return
            inbound.send("""{"jsonrpc":"2.0","id":$id,"result":{}}""")
        }

        override suspend fun close(code: Short, reason: String) {
            inbound.close()
        }
    }

    /** The connection of the last [viewModel]; [GatewayConnection.start] connects it to a [ReadyGateway]. */
    private lateinit var connection: GatewayConnection

    private fun viewModel(drafts: DraftStore = DraftStore(InMemoryKeyValueStore()), fileReads: MutableList<Pair<String, String?>>? = null): Pair<ChatViewModel, ChatHost> {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        // On the test dispatcher, so no request is still finishing on another thread when a test ends.
        val engine = MockEngineConfig()
        engine.dispatcher = dispatcher
        engine.addHandler { request ->
            if (request.url.encodedPath.startsWith("/api/fs/")) {
                fileReads?.add(request.url.host to request.url.parameters["profile"])
                if (request.url.encodedPath.endsWith("read-text")) respond("""{"text":"<p>widget</p>","byte_size":13,"truncated":false,"binary":false}""", HttpStatusCode.OK, json)
                else respond("<p>widget</p>", HttpStatusCode.OK)
            } else if (request.url.encodedPath == "/api/auth/ws-ticket") respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
            else respond("{}", HttpStatusCode.OK, json)
        }
        val client = createHttpClient(MockEngine(engine), cookies)
        connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> ReadyGateway() }, scope)
        val sessions = SessionsApi(client)
        val chatHost = ChatHost(connection, sessions, scope)
        val vm = ChatViewModel(
            connection, chatHost, LastChatStore(InMemoryKeyValueStore()), drafts,
            ModelsApi(connection), MediaApi(client), SlashApi(connection), sessions, ProfilesApi(client),
            SettingsStore(InMemoryKeyValueStore(), scope), PetApi(connection), JourneyApi(client), AudioApi(client),
            NoRecorder, NoPlayer, scope, BotsApi(connection), { null },
            object : VoiceKeepAlive {
                override fun hold(onEnd: () -> Unit, onLost: () -> Unit) = false
                override fun release() = Unit
            },
            NoDeviceDictation,
            { _, _ -> null },
        )
        viewModels.put("chat", vm)
        return vm to chatHost
    }

    @Test
    fun widgetFilesStayWithTheirTranscriptWhileTheSelectedChatChanges() = runTest(dispatcher) {
        Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val reads = mutableListOf<Pair<String, String?>>()
        val (vm, _) = viewModel(fileReads = reads)
        val first = ChatTarget(gateway, "same-id", "First", profile = "first")
        vm.open(first)
        runCurrent()
        val oldTranscript = vm.transcript.value
        val oldFiles = assertNotNull(oldTranscript.widgetFiles)
        val file = dev.hermeskotlin.core.media.GatewayFile("/tmp/widget.html")

        listOf(first.copy(profile = "second"), first.copy(gateway = SavedGateway("https://other.example"))).forEach { next ->
            val previous = vm.transcript.value
            vm.open(next)
            // Session delivery is delayed: the old emission must still carry its old callbacks.
            assertSame(previous, vm.transcript.value)
            runCurrent()
            val newTranscript = vm.transcript.value
            assertEquals("same-id", newTranscript.state.storedSessionId)
            assertEquals(next.gateway.gatewayUrl to next.profile, assertNotNull(newTranscript.widgetFiles).identity)
            // The screen may still hold this prior emission while the selected route has changed.
            assertTrue(loadWidget(oldFiles, file) is FileLoad.Ready)
            assertTrue(downloadWidget(oldFiles, file) is FileDownload.Ready)
        }
        assertEquals<List<Pair<String, String?>>>(List(4) { "hermes.example.ts.net" to "first" }, reads)
        assertEquals(first.gateway.gatewayUrl to first.profile, oldFiles.identity)
        assertEquals("same-id", oldTranscript.state.storedSessionId)

        // A new title on the same chat reads the same files, so its widgets keep their key and don't reload.
        val current = vm.transcript.value.widgetFiles
        vm.open(first.copy(gateway = SavedGateway("https://other.example"), title = "Renamed"))
        runCurrent()
        assertEquals(assertNotNull(current).identity, assertNotNull(vm.transcript.value.widgetFiles).identity)
    }

    @Test
    fun theSameChatReopenedAfterTheHostClosedGetsAFreshSession() = runTest(dispatcher) {
        val (vm, host) = viewModel()
        val target = ChatTarget(gateway, "s1", "Chat")
        vm.open(target)
        val first = assertNotNull(host.session.value)

        // An expired session closes the host's chat; signing back in lands on the same chat.
        host.close()
        assertNull(host.session.value)
        vm.open(target)

        assertNotSame(first, assertNotNull(host.session.value))
    }

    @Test
    fun aBotsChatIsRememberedWhereTheChatsListWasTheLaunchProfileIncluded() {
        // Opened from the launch profile's Chats list: the next launch reads the launch profile's last chat.
        assertNull(ChatTarget(gateway, "b1", "Side", profile = "side", bot = BotIdentity("side", "Side")).rememberedIn)
        assertEquals("work", ChatTarget(gateway, "b1", "Side", profile = "side", bot = BotIdentity("side", "Side", chatsProfile = "work")).rememberedIn)
        assertEquals("work", ChatTarget(gateway, "s1", "Chat", profile = "work").rememberedIn)
    }

    private fun attachment(id: String) = OutgoingAttachment(id, "$id.txt", "text/plain", byteArrayOf(1))

    @Test
    fun aDraftFromOutsideFillsTheNewChatWithoutSending() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val draft = ComposeDraft(text = "look at this", attachments = listOf(attachment("a"), attachment("b")), notice = "Only some fit.")

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = draft))

        assertEquals("look at this", vm.composer.text.toString())
        assertEquals(listOf("a", "b"), vm.attachments.value.map { it.id })
        assertEquals("Only some fit.", vm.attachmentError.value)
        assertTrue(vm.state.value.messages.isEmpty())
    }

    @Test
    fun aSharedDraftWinsOverTheStoredNewChatDraft() = runTest(dispatcher) {
        val drafts = DraftStore(InMemoryKeyValueStore())
        drafts.set(gateway.gatewayUrl, null, "half-typed earlier")
        val (vm, _) = viewModel(drafts)

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(text = "shared")))

        assertEquals("shared", vm.composer.text.toString())
    }

    @Test
    fun aSharedPictureAloneDoesNotBringBackAnOldNewChatDraft() = runTest(dispatcher) {
        val drafts = DraftStore(InMemoryKeyValueStore())
        drafts.set(gateway.gatewayUrl, null, "text of an earlier share")
        val (vm, _) = viewModel(drafts)

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(attachments = listOf(attachment("p")))))

        assertEquals("", vm.composer.text.toString())
        assertEquals(listOf("p"), vm.attachments.value.map { it.id })
    }

    @Test
    fun aShareDoesNotPickUpFilesLeftInAnEarlierNewChat() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        vm.open(ChatTarget(gateway, null, null, nonce = 1))
        vm.addAttachments(listOf(attachment("old")))
        // Another chat takes the composer; the new chat's file waits in its tray.
        vm.open(ChatTarget(gateway, "s1", "Chat"))

        vm.open(ChatTarget(gateway, null, null, nonce = 2, draft = ComposeDraft(attachments = listOf(attachment("p")))))

        assertEquals(listOf("p"), vm.attachments.value.map { it.id })
    }

    private val scribe = BotIdentity("scribe", "Scribe")

    @Test
    fun aShareToABotGoesAfterWhatWasTypedInItsChat() = runTest(dispatcher) {
        val drafts = DraftStore(InMemoryKeyValueStore())
        drafts.set(gateway.gatewayUrl, "b1", "my own note", "scribe")
        val (vm, _) = viewModel(drafts)

        vm.open(ChatTarget(gateway, "b1", "Scribe", profile = "scribe", bot = scribe, draft = ComposeDraft(text = "shared link", attachments = listOf(attachment("p")))))

        assertEquals("my own note\nshared link", vm.composer.text.toString())
        assertEquals(listOf("p"), vm.attachments.value.map { it.id })
        assertTrue(vm.state.value.messages.none { it is ChatMessage.User })
    }

    @Test
    fun aShareIntoTheOpenBotChatKeepsItsTextAndFiles() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        vm.open(ChatTarget(gateway, "b1", "Scribe", profile = "scribe", bot = scribe))
        vm.composer.setTextAndPlaceCursorAtEnd("half typed")
        vm.addAttachments(listOf(attachment("old")))

        vm.open(ChatTarget(gateway, "b1", "Scribe", profile = "scribe", bot = scribe, draft = ComposeDraft(text = "shared", attachments = listOf(attachment("p")))))

        assertEquals("half typed\nshared", vm.composer.text.toString())
        assertEquals(listOf("old", "p"), vm.attachments.value.map { it.id })
    }

    @Test
    fun aSharedPictureAloneKeepsTheBotChatsText() = runTest(dispatcher) {
        val drafts = DraftStore(InMemoryKeyValueStore())
        drafts.set(gateway.gatewayUrl, "b1", "my own note", "scribe")
        val (vm, _) = viewModel(drafts)

        vm.open(ChatTarget(gateway, "b1", "Scribe", profile = "scribe", bot = scribe, draft = ComposeDraft(attachments = listOf(attachment("p")))))

        assertEquals("my own note", vm.composer.text.toString())
        assertEquals(listOf("p"), vm.attachments.value.map { it.id })
    }

    @Test
    fun aShareThatOverfillsTheBotChatsTraySaysSo() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        vm.open(ChatTarget(gateway, "b1", "Scribe", profile = "scribe", bot = scribe))
        vm.addAttachments((1..3).map { attachment("old$it") })
        val shared = (1..OutgoingAttachment.MAX_COUNT).map { attachment("f$it") }

        // The share sheet sent more files than fit, and the tray already holds some.
        vm.open(ChatTarget(gateway, "b1", "Scribe", profile = "scribe", bot = scribe, draft = ComposeDraft(attachments = shared, notice = "Only the first 10 files were attached.")))

        assertEquals(OutgoingAttachment.MAX_COUNT, vm.attachments.value.size)
        // Not "the first 10 were attached": only 7 of them were.
        assertEquals("Up to ${OutgoingAttachment.MAX_COUNT} attachments per message.", vm.attachmentError.value)
    }

    @Test
    fun sharedFilesStopAtTheTrayLimit() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val many = (1..OutgoingAttachment.MAX_COUNT + 2).map { attachment("f$it") }

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(attachments = many)))

        assertEquals(OutgoingAttachment.MAX_COUNT, vm.attachments.value.size)
    }

    @Test
    fun theVoiceShortcutAsksTheScreenToDictate() = runTest(dispatcher) {
        val (vm, _) = viewModel()

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(start = DraftStart.Dictation)))

        assertEquals(ChatRequest.StartDictation, vm.requests.first())
    }

    @Test
    fun theCallShortcutStartsVoiceOnceInItsFreshChat() = runTest(dispatcher) {
        val (vm, host) = viewModel()
        connection.start(gateway.gatewayUrl)
        vm.connectionState.first { it is ConnectionState.Connected }
        vm.open(ChatTarget(gateway, "s1", "Previous chat"))
        val previous = host.session.value
        val requests = mutableListOf<ChatRequest>()
        backgroundScope.launch(dispatcher) { vm.requests.collect { requests += it } }

        val call = ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(start = DraftStart.VoiceChat))
        vm.open(call)
        assertEquals(listOf<ChatRequest>(ChatRequest.StartVoice), requests)
        assertNotSame(previous, host.session.value)
        assertNull(vm.state.value.storedSessionId)

        // The same shortcut isn't replayed, and a plain new chat starts nothing.
        vm.open(call)
        vm.open(ChatTarget(gateway, null, null, nonce = 2))
        assertEquals(listOf<ChatRequest>(ChatRequest.StartVoice), requests)
    }

    @Test
    fun theCallShortcutWaitsForTheGateway() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val requests = mutableListOf<ChatRequest>()
        backgroundScope.launch(dispatcher) { vm.requests.collect { requests += it } }

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(start = DraftStart.VoiceChat)))
        assertEquals(emptyList(), requests)

        connection.start(gateway.gatewayUrl)
        vm.connectionState.first { it is ConnectionState.Connected }
        assertEquals(listOf<ChatRequest>(ChatRequest.StartVoice), requests)
    }

    @Test
    fun theCallShortcutGivesUpWithANoticeWhenTheGatewayNeverAnswers() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val requests = mutableListOf<ChatRequest>()
        backgroundScope.launch(dispatcher) { vm.requests.collect { requests += it } }

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(start = DraftStart.VoiceChat)))
        advanceTimeBy(30_001)

        assertEquals(emptyList(), requests)
        assertEquals("The call didn't start: Kyllene couldn't reach the gateway.", vm.attachmentError.value)
        // Connecting afterwards doesn't start a call the person has given up on.
        connection.start(gateway.gatewayUrl)
        vm.connectionState.first { it is ConnectionState.Connected }
        assertEquals(emptyList(), requests)
    }

    @Test
    fun theCallShortcutWaitsForThePersonToComeBackToTheApp() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val requests = mutableListOf<ChatRequest>()
        backgroundScope.launch(dispatcher) { vm.requests.collect { requests += it } }

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(start = DraftStart.VoiceChat)))
        vm.onScreen(false)
        connection.start(gateway.gatewayUrl)
        vm.connectionState.first { it is ConnectionState.Connected }
        assertEquals(emptyList(), requests)

        vm.onScreen(true)
        assertEquals(listOf<ChatRequest>(ChatRequest.StartVoice), requests)
    }

    @Test
    fun theCallShortcutGivesUpQuietlyWhenThePersonStaysAway() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val requests = mutableListOf<ChatRequest>()
        backgroundScope.launch(dispatcher) { vm.requests.collect { requests += it } }

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(start = DraftStart.VoiceChat)))
        vm.onScreen(false)
        connection.start(gateway.gatewayUrl)
        vm.connectionState.first { it is ConnectionState.Connected }
        advanceTimeBy(30_001)
        vm.onScreen(true)

        assertEquals(emptyList(), requests)
        // The gateway was there; the notice would blame it wrongly.
        assertNull(vm.attachmentError.value)
    }

    @Test
    fun theCallShortcutDoesNotFollowThePersonToAnotherChat() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        val requests = mutableListOf<ChatRequest>()
        backgroundScope.launch(dispatcher) { vm.requests.collect { requests += it } }

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(start = DraftStart.VoiceChat)))
        vm.open(ChatTarget(gateway, "s1", "Another chat"))
        connection.start(gateway.gatewayUrl)
        vm.connectionState.first { it is ConnectionState.Connected }

        assertEquals(emptyList(), requests)
    }

    @Test
    fun reopeningTheOpenChatKeepsItsSession() = runTest(dispatcher) {
        val (vm, host) = viewModel()
        val target = ChatTarget(gateway, "s1", "Chat")
        vm.open(target)
        val first = host.session.value

        vm.open(target)

        assertSame(first, host.session.value)
    }

    @Test
    fun theSameChatWithANewReadPositionKeepsItsSession() = runTest(dispatcher) {
        val (vm, host) = viewModel()
        val target = ChatTarget(gateway, "s1", "Chat")
        vm.open(target)
        val first = assertNotNull(host.session.value)

        // Reopened from a notification: only where the "New messages" line goes has changed.
        vm.open(target.copy(readUpTo = 1_300.0))

        assertSame(first, host.session.value)
    }

    @Test
    fun theSameChatFromANotificationAfterTheHostClosedGetsAFreshSession() = runTest(dispatcher) {
        val (vm, host) = viewModel()
        val target = ChatTarget(gateway, "s1", "Chat")
        vm.open(target)
        val first = assertNotNull(host.session.value)

        // The session expired while away; the reply's notification reopens the chat with a read position.
        host.close()
        vm.open(target.copy(readUpTo = 1_300.0))

        assertNotSame(first, assertNotNull(host.session.value))
    }
}
