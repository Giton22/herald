package dev.hermeskotlin.designsystem.components

import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodeViewTest {

    private fun lines(text: String) = splitLines(text).map { text.substring(it.first, it.last + 1) }

    @Test
    fun linesWithoutTheirBreaks() {
        assertEquals(listOf("a", "", "b"), lines("a\n\nb"))
        assertEquals(listOf("a", "b"), lines("a\r\nb\r\n"))
    }

    @Test
    fun aTrailingBreakMakesNoEmptyLastLine() {
        assertEquals(listOf("a", "b"), lines("a\nb\n"))
        assertEquals(listOf(""), lines(""))
        assertEquals(listOf("", ""), lines("\n\n"))
    }

    @Test
    fun aLongSingleLineIsLaidOutAtABoundedWidth() {
        assertEquals(80, unwrappedColumns(80))
        assertEquals(MAX_LINE_COLUMNS, unwrappedColumns(200_000))
        assertEquals(0, unwrappedColumns(0))
    }

    @Test
    fun aColoringOfOtherTextIsntUsed() {
        val raw = """{"a":1}"""
        val colored = raw to AnnotatedString(raw)
        assertEquals(colored.second, coloringOf(colored, raw))
        // Switched to the indented text, the one-line coloring would be cut out of range.
        assertNull(coloringOf(colored, "{\n  \"a\": 1\n}"))
        assertNull(coloringOf(null, raw))
    }
}
