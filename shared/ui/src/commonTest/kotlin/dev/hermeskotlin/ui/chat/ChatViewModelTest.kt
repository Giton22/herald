package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.DraftStore
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
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private object NoRecorder : VoiceRecorder {
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording =
            error("not recording in tests")

        override fun finish() = Unit
    }

    private object NoPlayer : SpeechPlayer {
        override suspend fun play(audio: SpokenAudio) = Unit
    }

    private fun viewModel(): Pair<ChatViewModel, ChatHost> {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine { respond("{}", HttpStatusCode.OK, json) }, cookies)
        val scope = CoroutineScope(dispatcher)
        val connection = GatewayConnection(AuthApi(client, cookies), { _, _ -> error("not connecting in tests") }, scope)
        val sessions = SessionsApi(client)
        val chatHost = ChatHost(connection, sessions, scope)
        val vm = ChatViewModel(
            connection, chatHost, LastChatStore(InMemoryKeyValueStore()), DraftStore(InMemoryKeyValueStore()),
            ModelsApi(connection), MediaApi(client), SlashApi(connection), sessions, ProfilesApi(client),
            SettingsStore(InMemoryKeyValueStore(), scope), PetApi(connection), JourneyApi(client), AudioApi(client),
            NoRecorder, NoPlayer, scope,
        )
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
    fun reopeningTheOpenChatKeepsItsSession() = runTest(dispatcher) {
        val (vm, host) = viewModel()
        val target = ChatTarget(gateway, "s1", "Chat")
        vm.open(target)
        val first = host.session.value

        vm.open(target)

        assertSame(first, host.session.value)
    }
}
