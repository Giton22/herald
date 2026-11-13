package dev.hermeskotlin.ui.settings

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.*
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.plugins.NextPromptApi
import dev.hermeskotlin.core.plugins.PluginsApi
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.push.*
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.*
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.bots.BotFaces
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import org.junit.After
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CallTargetSearchTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(compose)
    private val models = androidx.lifecycle.ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gateway = mutableStateOf(SavedGateway("https://example.com"))
    private val profile = mutableStateOf<String?>("work")
    private val requests = CopyOnWriteArrayList<Url>()
    private var gate: CompletableDeferred<Unit>? = null
    private var user = "sample-user"
    private var failSearch = false
    private var selected: Pair<CallTarget, String?>? = null

    @After fun close() { gate?.complete(Unit); models.clear(); scope.cancel() }

    private fun show(assistant: Boolean = true) {
        val disk = InMemoryKeyValueStore()
        val cookies = PersistentCookiesStorage(disk)
        val client = createHttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = Dispatchers.Unconfined
            addHandler { request ->
                val url = request.url
                requests += url
                val body = when {
                    url.encodedPath.endsWith("/auth/me") -> """{"user_id":"$user"}"""
                    url.encodedPath.endsWith("/search") -> {
                        // Deliberately finish even after cancellation: the picker must reject this response.
                        val pending = gate
                        if (url.parameters["q"] == "slow") withContext(NonCancellable) { pending?.await() }
                        if (failSearch) return@addHandler respond("{}", HttpStatusCode.ServiceUnavailable)
                        if (url.parameters["q"] == "empty") """{"results":[]}"""
                        else """{"results":[{"id":"older-900","title":"Found ${url.parameters["q"]}","snippet":"message match"}]}"""
                    }
                    else -> """{"sessions":[{"id":"recent-${url.parameters["offset"]}","title":"Recent ${url.parameters["offset"]}"}],"total":1000}"""
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }), cookies)
        val auth = AuthApi(client, cookies)
        val settings = SettingsStore(disk, scope)
        val probe = GatewayProbe(client)
        val connection = GatewayConnection(auth, { _, _ -> error("no socket") }, scope)
        val vm = SettingsViewModel(settings, auth, probe, ConnectionCheck(probe, auth, { _, _ -> error("no socket") }),
            PushSetup(PushApi(connection), NoPushKeys, connection, GatewayRepository(disk), settings, scope) { "Test" },
            WallpaperStore(InMemoryBlobFile(), scope), ProfilesApi(client), SessionsApi(client), NextPromptApi(client, PluginsApi(client)))
        models.put("settings", vm)
        compose.setContent {
            HermesTheme(ColorScheme.Light) {
                CallTargetPicker(gateway.value, vm, BotFaces(), {}, assistant, profile.value) { target, owner -> selected = target to owner }
            }
        }
        compose.onNodeWithText("Existing session").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Recent 0").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun search(text: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }
    private fun awaitHit(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun searchIsScopedBoundedAndSelectingAHitKeepsItsId() {
        show()
        search("  needle  ")
        awaitHit("Found needle")
        val request = requests.last { it.encodedPath.endsWith("/search") }
        assertEquals("example.com", request.host)
        assertEquals("work", request.parameters["profile"])
        assertEquals("needle", request.parameters["q"])
        assertEquals("30", request.parameters["limit"])
        compose.onNodeWithText("Found needle").performClick()
        assertEquals(CallTarget(sessionId = "older-900", label = "Found needle") to "sample-user", selected)
    }

    @Test fun clearingSearchRestoresTheFirstRecentPageAndPagination() {
        show(assistant = false)
        compose.onNodeWithText("Next").performClick()
        awaitHit("Recent 50")
        search("needle")
        awaitHit("Found needle")
        assertEquals(null, requests.last { it.encodedPath.endsWith("/search") }.parameters["profile"])
        compose.onNodeWithText("Next").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear").performClick()
        awaitHit("Recent 0")
        compose.onNodeWithText("Next").performClick()
        awaitHit("Recent 50")
        val lists = requests.filter { it.encodedPath == "/api/sessions" }
        assertTrue(lists.all { it.parameters["limit"] == "50" && it.parameters["profile"] == null })
        assertEquals(listOf("0", "50", "0", "50"), lists.map { it.parameters["offset"] })
    }

    @Test fun aCancelledSearchCannotReplaceANewerQueryOrAClearedList() {
        show()
        gate = CompletableDeferred()
        search("slow")
        compose.waitUntil(10_000) { compose.mainClock.advanceTimeBy(50); requests.any { it.parameters["q"] == "slow" } }
        compose.onNodeWithText("Recent 0").assertDoesNotExist()
        search("newer")
        awaitHit("Found newer")
        compose.onNodeWithContentDescription("Clear").performClick()
        awaitHit("Recent 0")
        gate!!.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText("Found slow").assertDoesNotExist()
        compose.onNodeWithText("Found newer").assertDoesNotExist()
        compose.onNodeWithText("Recent 0").assertExists()
    }

    @Test fun aContextChangeClearsTheQueryAndDiscardsTheOldResponse() {
        show()
        gate = CompletableDeferred()
        search("slow")
        compose.waitUntil(10_000) { compose.mainClock.advanceTimeBy(50); requests.any { it.parameters["q"] == "slow" } }
        compose.runOnIdle { gateway.value = SavedGateway("https://other.example"); profile.value = "personal" }
        compose.onNodeWithText("Existing session").performClick()
        awaitHit("Recent 0")
        gate!!.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText("Found slow").assertDoesNotExist()
        assertFalse(compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.isNotEmpty())
        search("next")
        awaitHit("Found next")
        val request = requests.last { it.encodedPath.endsWith("/search") }
        assertEquals("other.example", request.host)
        assertEquals("personal", request.parameters["profile"])
    }

    @Test fun emptyAndFailedSearchesCanClearOrRetry() {
        show()
        search("empty")
        awaitHit("No matching sessions.")
        failSearch = true
        search("needle")
        awaitHit("Try again")
        compose.onNodeWithText("Found needle").assertDoesNotExist()
        failSearch = false
        compose.onNodeWithText("Try again").performClick()
        awaitHit("Found needle")
    }

    @Test fun anAccountChangeDuringSearchDoesNotExposeTheOldAccountsHit() {
        show()
        gate = CompletableDeferred()
        search("slow")
        compose.waitUntil(10_000) { compose.mainClock.advanceTimeBy(50); requests.any { it.parameters["q"] == "slow" } }
        user = "another-user"
        gate!!.complete(Unit)
        awaitHit("Couldn’t verify the signed-in account. Try again.")
        compose.onNodeWithText("Found slow").assertDoesNotExist()
        assertEquals(null, selected)
    }

    private object NoPushKeys : PushKeys {
        override val deviceId = "test"
        override fun registration(name: String) = error("no registration")
        override fun pinned(gatewayUrl: String): PushGateway? = null
        override fun accepts(keys: PushGateway) = false
        override fun pin(gatewayUrl: String, keys: PushGateway) = Unit
        override fun retire(gatewayUrl: String) = Unit
        override fun retired(gatewayUrl: String) = emptySet<String>()
        override fun forgetRetired(gatewayUrl: String, deviceId: String) = Unit
        override fun pinnedGatewayUrl(): String? = null
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
