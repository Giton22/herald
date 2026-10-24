package dev.hermeskotlin.ui.rooms

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
        assertEquals("Copy Main's message", copyLabel("Main"))
        assertEquals("Copy your message", copyLabel(null))
    }
}
