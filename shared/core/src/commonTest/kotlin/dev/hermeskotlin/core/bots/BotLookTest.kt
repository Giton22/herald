package dev.hermeskotlin.core.bots

import dev.hermeskotlin.core.network.HermesJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotLookTest {

    private fun bot(json: String) = HermesJson.decodeFromString(Bot.serializer(), json)

    @Test
    fun theDefaultBotWearsVioletUntilChanged() {
        assertEquals(BotLook(BotShape.Squircle, 0x8B5CF6), botLook(bot("""{"name":"default"}""")))
        val changed = bot("""{"name":"default","ui_meta":{"hermes-bots":{"custom":true,"shape":"cloud","color":"#ff0000"}}}""")
        assertEquals(BotLook(BotShape.Cloud, 0xFF0000), botLook(changed))
    }

    @Test
    fun stockLooksMatchDesktop() {
        // Desktop drew test1 as a yellow triangle (its face copy on the test gateway).
        val look = botLook(bot("""{"name":"test1"}"""))
        assertEquals(BotShape.Triangle, look.shape)
        assertEquals(defaultShapeFor("test1"), look.shape)
        val red = look.color shr 16 and 0xFF
        val green = look.color shr 8 and 0xFF
        val blue = look.color and 0xFF
        assertTrue(red > blue && green > blue, "yellowish, was ${look.color.toString(16)}")
    }

    @Test
    fun pickedLooksWinAndUnknownShapesFallBack() {
        val picked = bot("""{"name":"test2","ui_meta":{"hermes-bots":{"shape":"squircle","color":"hsl(210 68% 58%)"}}}""")
        assertEquals(BotLook(BotShape.Squircle, 0x4B94DD), botLook(picked))
        val blob = bot("""{"name":"test2","ui_meta":{"hermes-bots":{"shape":"blobatar:abc:round"}}}""")
        assertEquals(defaultShapeFor("test2"), botLook(blob).shape)
    }

    @Test
    fun cssColors() {
        assertEquals(0xAABBCC, parseCssColor("#abc"))
        assertEquals(0x12AB34, parseCssColor(" #12AB34 "))
        assertEquals(0xFF0000, parseCssColor("hsl(0, 100%, 50%)"))
        assertEquals(0x00FF00, parseCssColor("hsl(120deg 100% 50%)"))
        assertNull(parseCssColor("rebeccapurple"))
        assertNull(parseCssColor("#12345"))
    }

    @Test
    fun faceCopiesArePassedOverOnlyWhenTheMetaSaysHowToDraw() {
        val faceCopy = png(160, 160)
        val upload = png(256, 256)
        val drawn = bot("""{"name":"test2","ui_meta":{"hermes-bots":{"shape":"squircle","imageKind":"shape"}}}""")
        val photo = bot("""{"name":"test2","ui_meta":{"hermes-bots":{"shape":"squircle","imageKind":"photo"}}}""")
        val unknown = bot("""{"name":"test1"}""")
        assertFalse(showsPicture(drawn, faceCopy))
        assertTrue(showsPicture(drawn, upload))
        assertTrue(showsPicture(photo, faceCopy))
        // Desktop kept this bot's look to itself; its face copy is all there is.
        assertTrue(showsPicture(unknown, faceCopy))
        assertFalse(isFaceCopy(byteArrayOf(1, 2, 3)))
    }

    private fun png(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(33)
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10).copyInto(bytes)
        fun put(at: Int, value: Int) = (0 until 4).forEach { bytes[at + it] = (value shr (24 - 8 * it)).toByte() }
        put(16, width)
        put(20, height)
        return bytes
    }
}
