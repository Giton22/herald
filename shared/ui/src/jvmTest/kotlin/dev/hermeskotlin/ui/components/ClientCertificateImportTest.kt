package dev.hermeskotlin.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.core.gateway.ClientCertificateException
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.designsystem.hermesTheme
import dev.hermeskotlin.ui.ProvideAppLanguage
import dev.hermeskotlin.ui.applyAppLanguage
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ClientCertificateImportTest {
    @Test fun untranslatedCertificateCopyFallsBackWhileSharedButtonsStayLocalized() = runComposeUiTest {
        val locale = Locale.getDefault()
        try {
            setContent {
                ProvideAppLanguage("fr") {
                    hermesTheme(AccentPalette.Blue)(ColorScheme.Light) {
                        ClientCertificateImportDialog(TextFieldState(), false, null, {}, {})
                    }
                }
            }
            onNodeWithText("Import client certificate").assertExists()
            onNodeWithText("PKCS#12 password").assertExists()
            onNodeWithText("Annuler").assertExists()
        } finally {
            applyAppLanguage(null)
            Locale.setDefault(locale)
        }
    }

    @Test fun importErrorsKeepFullActionButtonsInNarrowAndShortWindows() {
        for (size in listOf(Size(640f, 360f), Size(360f, 640f))) {
            for (scheme in listOf(ColorScheme.Light, ColorScheme.Dark)) runSkikoComposeUiTest(size = size) {
                setContent {
                    hermesTheme(AccentPalette.Blue)(scheme) {
                        CompositionLocalProvider(LocalDesktopLayout provides true) {
                            ClientCertificateImportDialog(TextFieldState(), false, ClientCertificateException().message, {}, {})
                        }
                    }
                }
                onNodeWithText("Cancel").assertHeightIsAtLeast(ButtonSize.Medium.desktopHeight)
                onNodeWithText("Import").assertHeightIsAtLeast(ButtonSize.Medium.desktopHeight)
                if (size.width > size.height) {
                    onNode(hasSetTextAction()).performScrollTo().assertIsDisplayed()
                    onNodeWithText(ClientCertificateException().message!!).performScrollTo().assertIsDisplayed()
                    onNodeWithText("Import").assertHeightIsAtLeast(ButtonSize.Medium.desktopHeight)
                }
            }
        }
    }

    @Test fun thePasswordIsMaskedAndRepeatedSubmissionIsDisabledWhileImporting() = runComposeUiTest {
        val password = TextFieldState()
        var busy by mutableStateOf(false)
        var submitted = 0
        var cancelled = 0
        setContent {
            hermesTheme(AccentPalette.Blue)(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides true) {
                    ClientCertificateImportDialog(password, busy, "Synthetic import error", {
                        submitted++
                        busy = true
                    }, { cancelled++ })
                }
            }
        }
        onNodeWithText("Import client certificate").assertExists()
        onNode(hasSetTextAction()).performTextInput("secret")
        onNode(hasSetTextAction()).assertTextContains("••••••")
        assertEquals("secret", password.text.toString())
        onNodeWithText("Synthetic import error").assertExists()
        onNodeWithText("Import").performClick()
        waitForIdle()
        assertEquals(1, submitted)
        onNodeWithText("Importing…").assertIsNotEnabled()
        onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
    }
}
