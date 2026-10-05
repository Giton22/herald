package dev.hermeskotlin.ui.assistant

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

class AssistantPanelModelTest {

    private val home = SavedGateway("http://100.64.0.1:9119")
    private val work = SavedGateway("https://hermes.work.example")

    private val store = InMemoryKeyValueStore()
    private val cookies = PersistentCookiesStorage(store)
    private val gateways = GatewayRepository(store)

    // The connection waits on its ticket for good (a retry loop would keep the test clock busy, so a missed
    // chat would hang instead of time out); every other call fails.
    private val client = createHttpClient(
        MockEngine { request ->
            if (request.url.encodedPath.endsWith("ws-ticket")) awaitCancellation()
            respond("", HttpStatusCode.ServiceUnavailable)
        },
        cookies,
    )
    private val auth = AuthApi(client, cookies)

    private object Silent : VoiceRecorder, SpeechPlayer {
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit) =
            Recording(byteArrayOf(), "audio/wav", false)

        override fun finish() = Unit

        override suspend fun play(audio: SpokenAudio) = Unit
    }

    private fun TestScope.model(): AssistantPanelModel {
        val connection = GatewayConnection(auth, openSocket = { _, _ -> error("no socket in tests") }, scope = backgroundScope)
        return AssistantPanelModel(
            gateways, auth, ProfileStore(store), connection, SessionsApi(client), AudioApi(client), Silent, Silent, backgroundScope,
        )
    }

    private suspend fun signedIn(gateway: SavedGateway) =
        cookies.addCookie(Url(gateway.url + "/"), Cookie("hermes_session_at", "token", path = "/"))

    @Test
    fun aCallUpAfterHeraldSwitchedGatewaysStartsTheChatOnTheNewOne() = runTest {
        gateways.save(home)
        signedIn(home)
        gateways.save(work)
        signedIn(work)
        gateways.select(home.url)
        val model = model()
        val first = model.session.first { it != null }
        model.phase.first { it == AssistantPhase.Ready }

        // Herald switches to the other gateway while the panel is hidden, then the user calls it up again.
        gateways.select(work.url)
        model.begin(expectText = false, expectScreenshot = false)

        val next = model.session.first { it != null && it !== first }
        assertNotNull(next)
        assertNotSame(first, next)
        model.close()
    }

    @Test
    fun aCallUpOnTheSameGatewayKeepsAnUnusedChat() = runTest {
        gateways.save(home)
        signedIn(home)
        val model = model()
        val first = model.session.first { it != null }
        model.phase.first { it == AssistantPhase.Ready }

        model.begin(expectText = false, expectScreenshot = false)

        assertEquals(first, model.session.value)
        model.close()
    }
}
