package dev.hermeskotlin.ui.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WatchAccentsTest {

    @Test
    fun blueGoesToTheWatchAsItsOwnTheme() {
        val blue = watchAccent("Blue")
        assertEquals(0xFF2F6BF5.toInt(), blue.fill)
        assertEquals(0xFF7AA5FF.toInt(), blue.text)
        assertEquals(0xFFFFFFFF.toInt(), blue.onFill)
        // The soft tint, opaque: a see-through dim would wash out on the watch.
        assertEquals(0xFF4A84FE.toInt(), blue.dim)
    }

    @Test
    fun aPresetSendsItsDarkColors() {
        val violet = watchAccent("Violet")
        assertEquals(0xFFA78BFA.toInt(), violet.fill)
        assertEquals(0xFFA78BFA.toInt(), violet.text)
        assertEquals(0xFF0A0A0C.toInt(), violet.onFill)
        assertEquals(0xFFA78BFA.toInt(), violet.dim)
    }

    @Test
    fun anUnknownPresetIsBlueAndEachPresetHasItsOwnRevision() {
        assertEquals(watchAccent("Blue"), watchAccent("Mauve"))
        val revisions = listOf("Blue", "Violet", "Green", "Orange", "Pink", "Teal").map { watchAccent(it).revision }
        assertEquals(revisions.size, revisions.toSet().size)
        assertNotEquals(watchAccent("Blue").revision, watchAccent("Teal").revision)
    }
}
