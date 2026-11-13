package dev.hermeskotlin.wear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WatchHistoryTextTest {
    @Test
    fun independentlyRenderedPartsKeepEmojiCharactersTogether() {
        val text = "word".repeat(149) + "abc😀\r\n" + "| tall | table |\r\n".repeat(80)
        val parts = historyTextParts(text)
        assertEquals(text, parts.joinToString(""))
        assertFalse(parts.any { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
    }
}
