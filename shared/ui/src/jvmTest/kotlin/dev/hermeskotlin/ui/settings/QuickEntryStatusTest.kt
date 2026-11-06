package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.ui.ProvideAppLanguage
import dev.hermeskotlin.ui.QuickEntryHotkey
import dev.hermeskotlin.ui.applyAppLanguage
import java.util.Locale
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class QuickEntryStatusTest {
    @Test fun portalStatesUpdateTheExistingRowWithCatalogueFallback() = runComposeUiTest {
        val previous = QuickEntryHotkey.status
        val locale = Locale.getDefault()
        try {
            QuickEntryHotkey.status = null
            setContent {
                ProvideAppLanguage("de") {
                    HermesTheme(ColorScheme.Light) {
                        CompositionLocalProvider(LocalDesktopLayout provides true) {
                            SettingsView(AppSettings(), GatewayInfo(), "https://fixture.example", {}, {}, {}, {})
                        }
                    }
                }
            }
            onNodeWithText("Schnelleingabe").performScrollTo().assertExists()
            // Each state follows what the feature does, in the selected language.
            val about = "Ein Tastenkürzel des Desktops öffnet aus jeder App ein kleines Feld, um Hermes zu fragen – in einem neuen Chat oder im offenen."
            for ((state, trigger, text) in listOf(
                Triple(QuickEntryHotkey.State.Binding, null, "Wähle im Berechtigungsdialog deines Desktops ein Tastenkürzel."),
                Triple(QuickEntryHotkey.State.Bound, "Ctrl+Alt+K", "Tastenkürzel des Desktops: Ctrl+Alt+K."),
                Triple(QuickEntryHotkey.State.Bound, null, "Tastenkürzel des Desktops: von deinem Desktop gewählt."),
                Triple(QuickEntryHotkey.State.Unassigned, null, "Dein Desktop hat ihm keine Taste gegeben. Wähle eine in seinen Kurzbefehl-Einstellungen."),
                Triple(QuickEntryHotkey.State.Declined, null, "Kein Tastenkürzel des Desktops erlaubt. Schalte die Schnelleingabe aus und wieder an, um es erneut zu versuchen."),
                Triple(QuickEntryHotkey.State.Unavailable, null, "Das Portal für globale Tastenkürzel ist auf diesem Desktop nicht verfügbar."),
                Triple(QuickEntryHotkey.State.Off, null, "Dein Desktop fragt nach dem Tastenkürzel, sobald das hier an ist."),
            )) {
                runOnIdle { QuickEntryHotkey.status = QuickEntryHotkey.Status(state, trigger) }
                onNodeWithText("$about $text").performScrollTo().assertExists()
            }
        } finally {
            QuickEntryHotkey.status = previous
            applyAppLanguage(null)
            Locale.setDefault(locale)
        }
    }
}
