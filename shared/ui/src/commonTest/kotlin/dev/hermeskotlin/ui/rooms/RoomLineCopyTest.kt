package dev.hermeskotlin.ui.rooms

import dev.hermeskotlin.ui.inbox.words
import dev.hermeskotlin.ui.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Copy button beside a room line: when it shows, and what a screen reader hears for it. */
class RoomLineCopyTest {

    @Test
    fun aLineWithTextCanBeCopied() {
        assertTrue(copyable("ok"))
        assertTrue(copyable("**bold**\n\n- a list"))
    }

    @Test
    fun aBlankLineHasNoCopy() {
        assertFalse(copyable(""))
        assertFalse(copyable("  \n\t "))
    }

    @Test
    fun theLabelSaysWhoseMessageItCopies() {
        assertEquals(words(Res.string.rooms_copy_their_message, "Main"), copyLabel("Main"))
        assertEquals(words(Res.string.rooms_copy_your_message), copyLabel(null))
    }
}
