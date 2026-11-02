package dev.hermeskotlin.designsystem.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
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

    @Test
    fun eachLineKeepsOnlyItsOwnSpansWhereTheyFallInIt() {
        val red = SpanStyle(color = Color.Red)
        val blue = SpanStyle(color = Color.Blue)
        val text = "val a\n\nfun b"
        // One span runs across the empty line, from "a" to "fun".
        val colored = AnnotatedString(text, listOf(AnnotatedString.Range(red, 0, 3), AnnotatedString.Range(blue, 4, 10)))
        val lines = coloredLines(colored, splitLines(text))
        assertEquals(listOf("val a", "", "fun b"), lines.map { it.text })
        assertEquals(listOf(AnnotatedString.Range(red, 0, 3), AnnotatedString.Range(blue, 4, 5)), lines[0].spanStyles)
        assertEquals(emptyList(), lines[1].spanStyles)
        assertEquals(listOf(AnnotatedString.Range(blue, 0, 3)), lines[2].spanStyles)
    }
}
