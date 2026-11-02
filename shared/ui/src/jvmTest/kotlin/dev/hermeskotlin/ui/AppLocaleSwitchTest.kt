package dev.hermeskotlin.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.Text
import dev.hermeskotlin.ui.resources.Res
import dev.hermeskotlin.ui.resources.common_settings
import java.util.Locale
import dev.hermeskotlin.ui.settings.systemAppLanguage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.compose.resources.stringResource

/** Settings › Language on the desktop: the provided language switches the text on screen, and back. */
@OptIn(ExperimentalTestApi::class)
class AppLocaleSwitchTest {
    private val before = Locale.getDefault()

    @AfterTest
    fun restoreLocale() {
        applyAppLanguage(null)
        Locale.setDefault(before)
    }

    @Test
    fun systemDefaultStaysTheSystemsLanguageOnceOneIsPicked() {
        val system = systemAppLanguage(before.language, before.country)
        // As the desktop starts with a language stored: the first call replaces the default.
        applyAppLanguage(if (system.tag == "de") "fr" else "de")
        assertEquals(system, systemLanguage())
        applyAppLanguage(null)
        assertEquals(before, Locale.getDefault())
    }

    @Test
    fun switchingTheLanguageRedrawsTheText() = runComposeUiTest {
        var language by mutableStateOf<String?>("en")
        setContent {
            ProvideAppLanguage(language) { Text(stringResource(Res.string.common_settings)) }
        }
        waitUntil { onAllNodesWithTextExists("Settings") }

        language = "de"
        waitUntil { onAllNodesWithTextExists("Einstellungen") }
        onNodeWithText("Einstellungen").assertExists()

        language = "en"
        waitUntil { onAllNodesWithTextExists("Settings") }
        onNodeWithText("Settings").assertExists()
    }

    private fun ComposeUiTest.onAllNodesWithTextExists(text: String): Boolean =
        onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
}
