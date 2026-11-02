package dev.hermeskotlin.desktop

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.awtClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.hermeskotlin.designsystem.components.plainTextClipEntry
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.awt.datatransfer.Clipboard as AwtClipboard

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class PatientClipboardTest {

    /** A clipboard another app holds open for its first [busy] tries, as Windows' throws then. */
    private class HeldClipboard(var busy: Int) : Clipboard {
        var tries = 0
        var entry: ClipEntry? = null

        private fun open() {
            tries++
            if (busy-- > 0) throw IllegalStateException("cannot open system clipboard")
        }

        override suspend fun getClipEntry(): ClipEntry? = entry.also { open() }

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            open()
            entry = clipEntry
        }
    }

    @Test
    fun aCopyWaitsForTheClipboardToBeLetGo() = runBlocking {
        val system = HeldClipboard(busy = 2)
        val copied = plainTextClipEntry("text")
        PatientClipboard(system).setClipEntry(copied)
        assertSame(copied, system.entry)
        assertEquals(3, system.tries)
    }

    @Test
    fun aClipboardHeldTooLongDropsTheCopyWithoutAnError() = runBlocking {
        val system = HeldClipboard(busy = Int.MAX_VALUE)
        PatientClipboard(system).setClipEntry(plainTextClipEntry("text"))
        assertNull(system.entry)
        assertEquals(5, system.tries)
    }

    @Test
    fun aCancelledCopyIsNotTriedAgain() = runBlocking {
        var tries = 0
        val cancelled = object : Clipboard {
            override suspend fun getClipEntry(): ClipEntry? = null

            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                tries++
                throw CancellationException("left")
            }
        }
        assertFailsWith<CancellationException> { PatientClipboard(cancelled).setClipEntry(plainTextClipEntry("text")) }
        assertEquals(1, tries)
    }

    @Test
    fun aPasteFromAHeldClipboardFindsNothing() = runBlocking {
        val system = HeldClipboard(busy = Int.MAX_VALUE).apply { entry = plainTextClipEntry("text") }
        assertNull(PatientClipboard(system).getClipEntry())
    }

    @Test
    fun rightClickingATextFieldOpensItsMenu() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalClipboard provides rememberPatientClipboard()) {
                BasicTextField(rememberTextFieldState("a draft"), Modifier.size(240.dp, 40.dp).testTag("field"))
            }
        }
        onNodeWithTag("field").performMouseInput { rightClick(center) }
        waitForIdle()
        onNodeWithText("Select all").assertExists()
    }

    @Test
    fun theSystemsTextIsSeenThroughIt() {
        val awt = AwtClipboard("test").apply { setContents(StringSelection("text"), null) }
        val clipboard = PatientClipboard(OverAwt(awt)).awtClipboard
        assertTrue(clipboard!!.isDataFlavorAvailable(DataFlavor.stringFlavor))
        assertEquals("text", clipboard.getData(DataFlavor.stringFlavor))
    }

    /** A clipboard over [awt], as the window's own one is over the system's. */
    private class OverAwt(private val awt: AwtClipboard) : Clipboard {
        override suspend fun getClipEntry(): ClipEntry? = null

        override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit

        @Suppress("OVERRIDE_DEPRECATION")
        override val nativeClipboard: Any get() = awt
    }
}
