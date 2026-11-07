package dev.hermeskotlin.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.gateway.ClientCertificateException
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.designsystem.hermesTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import dev.hermeskotlin.ui.ProvideAppLanguage
import dev.hermeskotlin.ui.applyAppLanguage
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ClientCertPickerTest {
    @Test fun certificateImportAndItsFailureUseTheChosenLanguage() = runComposeUiTest {
        val locale = Locale.getDefault()
        val file = kotlin.io.path.createTempFile("synthetic-cert", ".p12").toFile().apply { writeBytes(byteArrayOf(1)) }
        var picker: ClientCertPicker? = null
        val selected = mutableListOf<String?>()
        try {
            setContent {
                ProvideAppLanguage("es") {
                    hermesTheme(AccentPalette.Blue)(ColorScheme.Light) {
                        picker = rememberDesktopClientCertPicker("localhost", 9443,
                            { _, _, _, _ -> throw ClientCertificateException() }, { file }, { selected.add(it) })
                    }
                }
            }
            runOnIdle { picker!!.pick() }
            waitUntil { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Importar certificado de cliente").assertExists()
            onNodeWithText("Contraseña PKCS#12").assertExists()
            onNodeWithText("Cancelar").assertExists()
            onNode(hasSetTextAction()).performTextInput("wrong")
            onNodeWithText("Importar").performClick()
            waitUntil { onAllNodes(androidx.compose.ui.test.hasText("Kyllene no pudo abrir este certificado de cliente.", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasSetTextAction()).assertTextEquals("")
            assertTrue(selected.isEmpty())
        } finally {
            file.delete()
            applyAppLanguage(null)
            Locale.setDefault(locale)
        }
    }

    @Test fun aFailedImportCanRetryWithoutSelectingOrRetainingItsPasswordAndBytes() = runComposeUiTest {
        val file = kotlin.io.path.createTempFile("synthetic-cert", ".p12").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var picker: ClientCertPicker? = null
        val selected = mutableListOf<String?>()
        var attempts = 0
        var consumed: ByteArray? = null
        var secret: CharArray? = null
        try {
            setContent {
                hermesTheme(AccentPalette.Blue)(ColorScheme.Dark) {
                    CompositionLocalProvider(LocalDesktopLayout provides true) {
                        picker = rememberDesktopClientCertPicker("localhost", 9443, { _, _, bytes, password ->
                            consumed = bytes
                            secret = password
                            attempts++
                            if (attempts == 1) throw ClientCertificateException()
                            "synthetic-alias"
                        }, { file }, { selected.add(it) })
                    }
                }
            }
            runOnIdle { picker!!.pick(); picker!!.pick() }
            waitUntil { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasSetTextAction()).performTextInput("wrong")
            onNodeWithText("Import").performClick()
            waitUntil { attempts == 1 && secret!!.all { it == '\u0000' } }
            onNodeWithText("Kyllene couldn't open this client certificate.", substring = true).assertExists()
            assertTrue(selected.isEmpty())
            assertTrue(consumed!!.all { it == 0.toByte() })
            onNode(hasSetTextAction()).assertTextEquals("")
            onNode(hasSetTextAction()).performTextInput("right")
            onNodeWithText("Import").performClick()
            waitUntil { selected.size == 1 }
            assertEquals(listOf<String?>("synthetic-alias"), selected)
            assertTrue(secret!!.all { it == '\u0000' })
            assertTrue(consumed!!.all { it == 0.toByte() })
            onNodeWithText("Import client certificate").assertDoesNotExist()
        } finally { file.delete() }
    }

    @Test fun cancellationDropsANonCooperativeLateImport() = lateImport(cancel = true)

    @Test fun anOwnerChangeDropsANonCooperativeLateImport() = lateImport(cancel = false)

    private fun lateImport(cancel: Boolean) = runComposeUiTest {
        val file = kotlin.io.path.createTempFile("synthetic-cert", ".p12").toFile().apply { writeBytes(byteArrayOf(4)) }
        var picker: ClientCertPicker? = null
        var owner by mutableStateOf("first")
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val selected = mutableListOf<String?>()
        var consumed: ByteArray? = null
        try {
            setContent {
                hermesTheme(AccentPalette.Blue)(ColorScheme.Light) {
                    CompositionLocalProvider(LocalDesktopLayout provides true) {
                        key(owner) {
                            picker = rememberDesktopClientCertPicker("localhost", 9443, { _, _, bytes, _ ->
                                consumed = bytes
                                entered.complete(Unit)
                                withContext(NonCancellable) { finish.await() }
                                "late-alias"
                            }, { file }, { selected.add(it) })
                        }
                    }
                }
            }
            runOnIdle { picker!!.pick() }
            waitUntil { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasSetTextAction()).performTextInput("secret")
            onNodeWithText("Import").performClick()
            waitUntil { entered.isCompleted }
            if (cancel) onNodeWithText("Cancel").performClick()
            else runOnIdle { owner = "second" }
            waitForIdle()
            runOnIdle { finish.complete(Unit) }
            waitUntil { consumed!!.all { it == 0.toByte() } }
            assertTrue(selected.isEmpty())
            onNodeWithText("Import client certificate").assertDoesNotExist()
        } finally { finish.complete(Unit); file.delete() }
    }
}
