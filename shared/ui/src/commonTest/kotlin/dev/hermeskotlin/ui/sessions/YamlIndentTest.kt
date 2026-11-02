package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class YamlIndentTest {

    private fun after(text: String, selection: TextRange, outdent: Boolean): Triple<String, TextRange, Boolean> {
        val state = TextFieldState(text, selection)
        var changed = false
        state.edit { changed = yamlIndent(this, outdent) }
        return Triple(state.text.toString(), state.selection, changed)
    }

    @Test
    fun tabIndentsWithTwoSpacesNeverATab() {
        assertEquals(Triple("model:\n  ", TextRange(9), true), after("model:\n", TextRange(7), outdent = false))
    }

    @Test
    fun shiftTabTakesUpToTwoSpacesOffTheCaretsLine() {
        assertEquals(Triple("a:\n  b: 1", TextRange(5), true), after("a:\n    b: 1", TextRange(7), outdent = true))
        assertEquals(Triple("a:\nb: 1", TextRange(3), true), after("a:\n b: 1", TextRange(4), outdent = true))
    }

    @Test
    fun shiftTabOnAnUnindentedLineChangesNothingAndLetsTheFocusMove() {
        val (text, selection, changed) = after("a: 1", TextRange(2), outdent = true)
        assertEquals("a: 1" to TextRange(2), text to selection)
        assertFalse(changed)
    }

    @Test
    fun tabOverSelectedLinesIndentsEachOneAndKeepsTheirText() {
        // "b: 1" and "c: 2" selected from b's start to c's end.
        assertEquals(
            Triple("a:\n  b: 1\n  c: 2\nd: 3", TextRange(3, 16), true),
            after("a:\nb: 1\nc: 2\nd: 3", TextRange(3, 12), outdent = false),
        )
    }

    @Test
    fun aSelectionEndingAtALineStartLeavesThatLineAlone() {
        assertEquals(
            Triple("  a\nb", TextRange(0, 4), true),
            after("a\nb", TextRange(0, 2), outdent = false),
        )
    }

    @Test
    fun shiftTabOverSelectedLinesOutdentsEachOne() {
        assertEquals(
            Triple("a:\nb: 1\n c: 2", TextRange(3, 13), true),
            after("a:\n  b: 1\n   c: 2", TextRange(3, 17), outdent = true),
        )
    }
}
