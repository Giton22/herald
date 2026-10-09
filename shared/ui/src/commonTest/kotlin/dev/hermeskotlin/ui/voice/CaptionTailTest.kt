package dev.hermeskotlin.ui.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptionTailTest {

    @Test
    fun shortWordsShowWhole() {
        assertEquals("Run it now", tail("  Run it now ", 40))
    }

    @Test
    fun aLongLineKeepsItsEndFromAWord() {
        val said = "Yes, run the backup now and then mail me the report when it's done please"
        val shown = tail(said, 30)

        assertTrue(shown.startsWith("…"))
        assertTrue(said.endsWith(shown.removePrefix("…")))
        // It starts on a word, not partway through one.
        assertTrue(said.contains(" " + shown.removePrefix("…")))
    }

    @Test
    fun oneLongWordIsCutRatherThanLost() {
        assertEquals("…" + "x".repeat(10), tail("x".repeat(50), 10))
    }
}
