package dev.hermeskotlin.ui.sessions

import com.composables.icons.lucide.Archive
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin
import kotlin.test.Test
import kotlin.test.assertEquals

class SwipeRowActionsTest {

    @Test
    fun oneActionOnBothSidesIsOfferedToScreenReadersOnce() {
        val read = SwipeAction("Mark read", Lucide.Check, SwipeKind.Pin) {}
        assertEquals(listOf("Mark read"), readerActions(read, read).map { it.label })
    }

    @Test
    fun aSessionRowsTwoActionsAreBothOffered() {
        val pin = SwipeAction("Pin", Lucide.Pin, SwipeKind.Pin) {}
        val archive = SwipeAction("Archive", Lucide.Archive, SwipeKind.Archive) {}
        assertEquals(listOf("Pin", "Archive"), readerActions(pin, archive).map { it.label })
        assertEquals(listOf("Archive"), readerActions(null, archive).map { it.label })
        assertEquals(emptyList(), readerActions(null, null))
    }
}
