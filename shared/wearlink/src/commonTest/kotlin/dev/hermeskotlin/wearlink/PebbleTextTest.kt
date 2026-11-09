package dev.hermeskotlin.wearlink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PebbleTextTest {

    @Test
    fun sizesAreCountedInUtf8Bytes() {
        assertEquals(5, utf8Size("hello"))
        assertEquals(2, utf8Size("é"))
        assertEquals(3, utf8Size("…"))
        assertEquals(4, utf8Size("🙂"))
        assertEquals(4 + 1 + 2, utf8Size("🙂aé"))
    }

    @Test
    fun aCutNeverSplitsACharacter() {
        assertEquals("ab", takeUtf8("ab🙂", 5))
        assertEquals("ab🙂", takeUtf8("ab🙂", 6))
        assertEquals("🙂", takeUtf8("ab🙂c", 4, from = 2))
        assertEquals("", takeUtf8("é", 1))
    }

    @Test
    fun aClippedTextEndsWithDotsWithinItsBudget() {
        val clipped = clipUtf8("one two three four five six seven eight", 20)
        assertTrue(utf8Size(clipped) <= 20, clipped)
        assertTrue(clipped.endsWith("..."), clipped)
        assertEquals("short", clipUtf8("short", 20))
        val emoji = clipUtf8("🙂".repeat(10), 13)
        assertEquals("🙂🙂...", emoji)
    }

    @Test
    fun theCharIndexOfAByteCountFollowsTheText() {
        // a, b, then the emoji's two chars.
        assertEquals(4, charIndexOfUtf8("ab🙂c", 6))
        assertEquals(0, charIndexOfUtf8("🙂", 0))
    }

    @Test
    fun markdownIsShownAsPlainText() {
        val markdown = """
            # Plan
            Here is **bold**, *soft*, `code` and a [link](https://example.com).

            ```kotlin
            val x = 1
            ```
            * one
            + two
            > quoted
            ---
            ![a picture](https://example.com/p.png)
        """.trimIndent()
        val expected = """
            Plan
            Here is bold, soft, code and a link.

            val x = 1
            - one
            - two
            quoted

            a picture
        """.trimIndent()
        assertEquals(expected, plainText(markdown))
    }

    @Test
    fun codeKeepsItsMarks() {
        val markdown = """
            Call `__init__` with **care**, as [`setup.py`](https://example.com/setup.py) does:

            ```python
            # set up
            def f(*args, **kwargs):
                return [x for x in args]
            ```
        """.trimIndent()
        val expected = """
            Call __init__ with care, as setup.py does:

            # set up
            def f(*args, **kwargs):
                return [x for x in args]
        """.trimIndent()
        assertEquals(expected, plainText(markdown))
    }

    @Test
    fun plainWordsWithStarsAndUnderscoresKeepThem() {
        assertEquals("2 * 3 * 4 = 24", plainText("2 * 3 * 4 = 24"))
        assertEquals("snake_case_name", plainText("snake_case_name"))
    }
}
