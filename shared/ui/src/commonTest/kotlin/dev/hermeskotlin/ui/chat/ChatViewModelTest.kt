package dev.hermeskotlin.ui.chat

import androidx.lifecycle.ViewModelStore
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ComposeDraft
import dev.hermeskotlin.core.chat.DraftStore
import dev.hermeskotlin.core.chat.OutgoingAttachment
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.connection.GatewayConnection
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
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    private fun viewModel(drafts: DraftStore = DraftStore(InMemoryKeyValueStore())): Pair<ChatViewModel, ChatHost> {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        // On the test dispatcher, so no request is still finishing on another thread when a test ends.
        val engine = MockEngineConfig()
        engine.dispatcher = dispatcher
        engine.addHandler { respond("{}", HttpStatusCode.OK, json) }
        val client = createHttpClient(MockEngine(engine), cookies)
        val connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> error("not connecting in tests") }, scope)
        val sessions = SessionsApi(client)
        val chatHost = ChatHost(connection, sessions, scope)
        val vm = ChatViewModel(
            connection, chatHost, LastChatStore(InMemoryKeyValueStore()), drafts,
            ModelsApi(connection), MediaApi(client), SlashApi(connection), sessions, ProfilesApi(client),
            SettingsStore(InMemoryKeyValueStore(), scope), PetApi(connection), JourneyApi(client), AudioApi(client),
            NoRecorder, NoPlayer, scope, BotsApi(connection),
        )
        viewModels.put("chat", vm)
        return vm to chatHost
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

        vm.open(ChatTarget(gateway, null, null, nonce = 1, draft = ComposeDraft(dictate = true)))

        assertEquals(ChatRequest.StartDictation, vm.requests.first())
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
}
