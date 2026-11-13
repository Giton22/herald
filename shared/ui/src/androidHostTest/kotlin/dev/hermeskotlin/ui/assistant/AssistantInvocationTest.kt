package dev.hermeskotlin.ui.assistant

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.AssistantTarget
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.*
import dev.hermeskotlin.designsystem.HermesTheme
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs the real panel effect, asynchronous target binding and VoiceController, without a device microphone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantInvocationTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    @Test fun fixedTargetListensAfterValidationAndRecognizerWindDown() = invocation(fixed = true)
    @Test fun newChatListensAfterPermissionReturnAndRecognizerWindDown() = invocation(fixed = false)

    private fun invocation(fixed: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val disk = InMemoryKeyValueStore()
        val gateway = SavedGateway("https://example.com")
        val gateways = GatewayRepository(disk)
        val cookies = PersistentCookiesStorage(disk)
        runBlocking {
            gateways.save(gateway)
            cookies.addCookie(Url(gateway.url), Cookie("hermes_session_at", "sample", path = "/"))
        }
        val settings = SettingsStore(disk, scope)
        settings.update { it.copy(assistantTargets = if (fixed) mapOf(gateway.url to mapOf("" to AssistantTarget("user", "picked"))) else emptyMap()) }
        var validation = CompletableDeferred<Unit>()
        var reads = 0
        var missing = false
        val client = createHttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = Dispatchers.Unconfined
            addHandler { request ->
                val body = when {
                    request.url.encodedPath.endsWith("ws-ticket") -> awaitCancellation()
                    request.url.encodedPath.endsWith("/auth/me") -> """{"user_id":"user"}"""
                    request.url.encodedPath.endsWith("/picked") -> {
                        reads++
                        validation.await()
                        if (missing) return@addHandler respond("{}", HttpStatusCode.NotFound)
                        "{}"
                    }
                    else -> """{"session_id":"picked","messages":[{"role":"user","content":"Scoped synthetic message"}]}"""
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }), cookies)
        val auth = AuthApi(client, cookies)
        val connection = GatewayConnection(auth, { _, _ -> error("no socket") }, scope)
        val recognizer = WaitingRecognizer()
        val silent = object : VoiceRecorder, SpeechPlayer {
            override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording = error("use device recognizer")
            override fun finish() = Unit
            override suspend fun play(audio: SpokenAudio) = Unit
        }
        val model = AssistantPanelModel(gateways, auth, ProfileStore(disk), connection, SessionsApi(client), AudioApi(client), silent, silent, scope,
            settings = settings, deviceDictation = recognizer)
        val allowed = mutableStateOf(false)
        try {
            compose.setContent {
                HermesTheme(ColorScheme.Light) {
                    AssistantPanelCard(model, allowed.value, {}, {}, {}, {})
                }
            }
            compose.runOnIdle { model.begin(false, false) }
            if (fixed) {
                compose.waitUntil(10_000) { reads > 0 }
                compose.runOnIdle { allowed.value = true }
                compose.waitForIdle()
                assertEquals(0, recognizer.starts, "No listening in an unvalidated context")
                compose.runOnIdle { model.hide(); validation.complete(Unit) }
                compose.waitUntil(10_000) { model.phase.value == AssistantPhase.Ready }
                compose.waitForIdle()
                assertEquals(0, recognizer.starts, "Dismissed while validation was pending")
                compose.runOnIdle { model.begin(false, false) }
            } else {
                compose.waitUntil(10_000) { model.phase.value == AssistantPhase.Ready }
                assertEquals(0, recognizer.starts, "No microphone permission")
                compose.runOnIdle { model.resume(); allowed.value = true }
            }
            compose.waitUntil(10_000) { recognizer.starts == 1 }
            val firstSession = model.session.value
            assertEquals(if (fixed) "picked" else null, firstSession?.state?.value?.storedSessionId)
            assertTrue(model.voice.dictation.value.recording)

            // A real recognizer can need a main-thread hop to release its old microphone. Let target
            // validation finish before that release: the invocation must wait, then start without a tap.
            recognizer.release = CompletableDeferred()
            validation = CompletableDeferred()
            compose.runOnIdle { model.begin(false, false) }
            if (fixed) compose.runOnIdle { validation.complete(Unit) }
            compose.waitUntil(10_000) { model.phase.value == AssistantPhase.Ready }
            compose.waitForIdle()
            assertEquals(1, recognizer.starts)
            compose.runOnIdle { recognizer.release.complete(Unit) }
            compose.waitUntil(10_000) { recognizer.starts == 2 }
            assertEquals(1, recognizer.maximumOwners)
            assertEquals(firstSession, model.session.value, "An invocation does not reset the selected or unused chat")

            // An explicit stop finishes this invocation; state updates must not start it again.
            compose.runOnIdle { recognizer.finish() }
            compose.waitUntil(10_000) { compose.waitForIdle(); !model.voice.dictation.value.active }
            compose.waitForIdle()
            assertEquals(2, recognizer.starts)
            // Session.onHide cancels dictation, but doesn't stop work or start a hidden microphone.
            compose.runOnIdle { model.hide() }
            compose.waitForIdle()
            assertFalse(model.voice.dictation.value.active)
            assertEquals(2, recognizer.starts)
            if (fixed) {
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Scoped synthetic message").fetchSemanticsNodes().isNotEmpty() }
                compose.runOnIdle { missing = true; model.begin(false, false) }
                compose.waitUntil(10_000) { compose.waitForIdle(); model.targetUnavailable.value }
                compose.onNodeWithText("Scoped synthetic message").assertDoesNotExist()
                assertEquals(null, model.session.value)
                assertEquals(2, recognizer.starts, "No recording for an unavailable target")
            }
        } finally {
            validation.complete(Unit)
            recognizer.release.complete(Unit)
            model.close()
            connection.stop()
            scope.cancel()
            client.close()
        }
    }

    private class WaitingRecognizer : DeviceDictation {
        var starts = 0
        var owners = 0
        var maximumOwners = 0
        var release = CompletableDeferred<Unit>().apply { complete(Unit) }
        private var finish = CompletableDeferred<String>()
        override fun available() = true
        override suspend fun listen(onPartial: (String) -> Unit, onLevel: (Float) -> Unit): String {
            starts++
            owners++
            maximumOwners = maxOf(maximumOwners, owners)
            finish = CompletableDeferred()
            try { return finish.await() }
            finally { withContext(NonCancellable) { release.await() }; owners-- }
        }
        override fun finish() { finish.complete("") }
    }

    private class RegisterHostActivity : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
                base.evaluate()
            }
        }
    }
}
