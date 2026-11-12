package dev.hermeskotlin.ui.bots

import kotlin.test.Test
import kotlin.test.assertEquals

/** The square a bot's picture is cut from a photo of any shape. */
class BotPhotoTest {

    @Test
    fun aWidePhotoKeepsItsMiddle() {
        assertEquals(Triple(100, 0, 300), centreSquare(500, 300))
    }

    @Test
    fun aTallPhotoKeepsItsMiddle() {
        assertEquals(Triple(0, 150, 400), centreSquare(400, 700))
    }

    @Test
    fun aSquarePhotoIsKeptWhole() {
        assertEquals(Triple(0, 0, 256), centreSquare(256, 256))
    }
}
