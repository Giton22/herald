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
            for ((state, trigger, text) in listOf(
                Triple(QuickEntryHotkey.State.Binding, null, "Choose a shortcut in your desktop’s permission dialog."),
                Triple(QuickEntryHotkey.State.Bound, "Ctrl+Alt+K", "Desktop shortcut: Ctrl+Alt+K."),
                Triple(QuickEntryHotkey.State.Bound, null, "Desktop shortcut: chosen by your desktop."),
                Triple(QuickEntryHotkey.State.Declined, null, "No desktop shortcut was granted. Turn Quick entry off and on to try again."),
                Triple(QuickEntryHotkey.State.Unavailable, null, "This desktop’s global shortcut portal is unavailable."),
                Triple(QuickEntryHotkey.State.Off, null, "Your desktop asks which shortcut to use when this is on."),
            )) {
                runOnIdle { QuickEntryHotkey.status = QuickEntryHotkey.Status(state, trigger) }
                onNodeWithText(text).performScrollTo().assertExists()
            }
        } finally {
            QuickEntryHotkey.status = previous
            applyAppLanguage(null)
            Locale.setDefault(locale)
        }
    }
}
