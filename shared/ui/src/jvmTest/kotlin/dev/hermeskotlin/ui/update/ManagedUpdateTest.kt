package dev.hermeskotlin.ui.update

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.update.AppUpdate
import dev.hermeskotlin.core.update.UpdateChecker
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.ProvideAppLanguage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

@OptIn(ExperimentalTestApi::class)
class ManagedUpdateTest {
    @Test
    fun managedOffersBypassReleaseCheckingIncludingFailureStates() = runComposeUiTest {
        val requests = AtomicInteger()
        val client = HttpClient(MockEngine {
            requests.incrementAndGet()
            respond("""{"tag_name":"v2.0.0","html_url":"https://fixture.invalid/release","assets":[]}""")
        })
        startKoin { modules(module { single { UpdateChecker(client, InMemoryKeyValueStore()) } }) }
        try {
            var state by mutableStateOf(ManagedUpdateState(ManagedUpdatePhase.Available))
            lateinit var offered: UpdateOffer
            lateinit var managed: ManagedUpdateOffer
            setContent {
                managed = ManagedUpdateOffer(state)
                CompositionLocalProvider(LocalManagedUpdateOffer provides managed) {
                    offered = rememberUpdateOffer("fixture/repository", "1.0.0", true)
                }
            }
            for (failure in listOf(null, ManagedUpdateFailure.Denied, ManagedUpdateFailure.Uncertain,
                ManagedUpdateFailure.SystemUpdateRequired, ManagedUpdateFailure.Unavailable)) {
                runOnIdle { state = ManagedUpdateState(if (failure == null) ManagedUpdatePhase.Available else ManagedUpdatePhase.Failed, failure = failure) }
                waitForIdle()
                runOnIdle { assertSame(managed, offered.managed); assertEquals(0, requests.get()) }
            }
        } finally { stopKoin(); client.close() }
    }

    @Test
    fun ordinaryOfferRetainsInstallerThenBrowserFallback() = runComposeUiTest {
        val locale = Locale.getDefault()
        val uris = mutableListOf<String>()
        var installs = 0
        try {
            setContent {
                ProvideAppLanguage("en") { HermesTheme(ColorScheme.Light) {
                    CompositionLocalProvider(
                        LocalUpdateOffer provides UpdateOffer(AppUpdate("2.0", "https://fixture.invalid/release", "https://fixture.invalid/app.deb")) {},
                        LocalUpdateInstaller provides UpdateInstaller { installs++; false },
                        LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { uris += uri } },
                    ) { UpdateBanner() }
                } }
            }
            onNodeWithText("Install").performClick()
            waitUntil { uris.isNotEmpty() }
            assertEquals(1, installs)
            assertEquals(listOf("https://fixture.invalid/app.deb"), uris)
        } finally { Locale.setDefault(locale) }
    }

    @Test
    fun managedActionProgressAndDenialNeverReachNativeInstallerOrBrowser() = runComposeUiTest {
        val locale = Locale.getDefault()
        var state by mutableStateOf(ManagedUpdateState(ManagedUpdatePhase.Available))
        var updates = 0
        try {
            setContent {
                ProvideAppLanguage("en") { HermesTheme(ColorScheme.Dark) {
                    val managed = ManagedUpdateOffer(state, onUpdate = {
                        updates++; state = ManagedUpdateState(ManagedUpdatePhase.Updating, progress = 100)
                    })
                    CompositionLocalProvider(
                        LocalUpdateOffer provides UpdateOffer(managed),
                        LocalUpdateInstaller provides UpdateInstaller { error("managed native installer fallback") },
                        LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { error("managed browser fallback") } },
                    ) { UpdateBanner() }
                } }
            }
            onNodeWithText("Update").performClick()
            waitForIdle()
            onNodeWithText("Updating… 100%").assertExists()
            onNodeWithText("Restart").assertDoesNotExist()
            runOnIdle { state = ManagedUpdateState(ManagedUpdatePhase.Failed, failure = ManagedUpdateFailure.Denied) }
            waitForIdle()
            assertEquals(1, updates)
            assertEquals(ManagedUpdateFailure.Denied, state.failure)
        } finally { Locale.setDefault(locale) }
    }

    @Test
    fun sidebarActionsStayReadableAndRestartAllowsDeferral() = runComposeUiTest {
        val locale = Locale.getDefault()
        var restarts = 0
        try {
            setContent {
                ProvideAppLanguage("es") { HermesTheme(ColorScheme.Dark) {
                    CompositionLocalProvider(LocalUpdateOffer provides UpdateOffer(ManagedUpdateOffer(
                        ManagedUpdateState(ManagedUpdatePhase.Available, canRestart = true), onRestart = { restarts++ },
                    ))) { Box(Modifier.width(240.dp).padding(horizontal = 12.dp)) { UpdateBanner() } }
                } }
            }
            val layout = mutableListOf<TextLayoutResult>()
            onNodeWithText("Ahora no", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
            assertTrue(layout.isNotEmpty() && layout.all { result ->
                result.size.width > 0 && result.lineCount > 0 && (0 until result.lineCount).none { result.isLineEllipsized(it) }
            }, "Sidebar actions must remain fully readable")
            onNodeWithText("Reiniciar").performClick()
            assertEquals(0, restarts)
            onNodeWithText("¿Reiniciar Kyllene?").assertExists()
            onNodeWithText("Cancelar").performClick()
            assertEquals(0, restarts)
            onNodeWithText("Reiniciar").performClick()
            onNodeWithText("Reiniciar ahora").performClick()
            assertEquals(1, restarts)
        } finally { Locale.setDefault(locale) }
    }
}
