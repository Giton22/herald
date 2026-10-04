package dev.hermeskotlin.ui.assistant

import dev.hermeskotlin.core.chat.AttachmentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenContextTest {

    @Test
    fun screenshotAndTextGoOutAsAnImageAndAFile() {
        val attachments = ScreenContext("Settings", listOf("Apps"), byteArrayOf(1, 2)).toAttachments()
        assertEquals(listOf(AttachmentKind.Image, AttachmentKind.File), attachments.map { it.kind })
        assertEquals(listOf(ScreenContext.SCREENSHOT_NAME, ScreenContext.TEXT_NAME), attachments.map { it.name })
    }

    @Test
    fun textSaysWhatItIsAndWhetherAScreenshotCameWithIt() {
        val withShot = ScreenContext("Settings", listOf("Apps"), byteArrayOf(1)).screenText()!!
        assertTrue(withShot.startsWith("Text on the user's phone screen in Settings when they asked. A screenshot"))
        val textOnly = ScreenContext(null, listOf("Apps"), null).screenText()!!
        assertTrue(textOnly.startsWith("Text on the user's phone screen when they asked. There is no screenshot."))
    }

    @Test
    fun blankLinesAndImmediateRepeatsAreDropped() {
        val text = ScreenContext(null, listOf("  Apps ", "", "Apps", "Default\napps", "Apps"), null).screenText()!!
        assertEquals(listOf("Apps", "Default apps", "Apps"), text.substringAfter("\n\n").lines())
    }

    @Test
    fun noTextMeansNoFile() {
        val context = ScreenContext("Bank", listOf(" ", ""), byteArrayOf(1))
        assertNull(context.screenText())
        assertEquals(listOf(AttachmentKind.Image), context.toAttachments().map { it.kind })
    }

    @Test
    fun aLongScreenIsCutAndSaysHowMuch() {
        val line = "x".repeat(1_000)
        val text = ScreenContext(null, List(30) { "$it $line" }, null).screenText()!!
        assertTrue(text.length <= ScreenContext.MAX_TEXT_CHARS + 40)
        assertTrue(text.endsWith("more lines cut]"))
    }

    @Test
    fun aSecureWindowGivesNothing() {
        assertTrue(ScreenContext(null, emptyList(), null).isEmpty)
        assertTrue(ScreenContext(null, emptyList(), null).toAttachments().isEmpty())
    }
}
