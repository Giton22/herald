package dev.hermeskotlin.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.designsystem.hermesTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ClientCertificateImportTest {
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
