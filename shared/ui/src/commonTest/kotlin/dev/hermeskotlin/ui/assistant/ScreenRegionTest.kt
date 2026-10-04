package dev.hermeskotlin.ui.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenRegionTest {

    private val title = ScreenItem("Apps", ScreenRegion(100f, 800f, 400f, 860f))
    private val subtitle = ScreenItem("Assistant, recent apps, default apps", ScreenRegion(100f, 860f, 900f, 920f))
    private val row = ScreenItem("Apps row", ScreenRegion(0f, 780f, 1344f, 940f))
    private val footer = ScreenItem("Storage", ScreenRegion(100f, 2400f, 400f, 2460f))
    private val unplaced = ScreenItem("Somewhere")
    private val items = listOf(row, title, subtitle, footer, unplaced)

    @Test
    fun aCircleTakesTheTextWhoseMiddleIsInside() {
        val circle = ScreenRegion(50f, 760f, 950f, 960f)
        assertEquals(listOf("Apps", "Assistant, recent apps, default apps"), items.inside(circle).map { it.text }.filter { it != "Apps row" })
        assertTrue(footer !in items.inside(circle))
        assertTrue(unplaced !in items.inside(circle))
    }

    @Test
    fun aTapPicksTheSmallestTextUnderTheFinger() {
        assertEquals("Apps", items.tapped(200f to 830f)?.text)
        assertEquals("Apps row", items.tapped(1200f to 830f)?.text)
        assertNull(items.tapped(700f to 1500f))
    }

    @Test
    fun theBoxAroundAStrokeIsPaddedButStaysOnScreen() {
        val box = ScreenRegion.around(listOf(10f to 20f, 300f to 5f, 120f to 400f))!!
        assertEquals(ScreenRegion(10f, 5f, 300f, 400f), box)
        assertEquals(ScreenRegion(0f, 0f, 320f, 420f), box.padded(20f, width = 1344, height = 2992))
        assertNull(ScreenRegion.around(emptyList()))
    }

    @Test
    fun aCircledScreenSaysSo() {
        val context = ScreenContext("Settings", listOf("Apps"), byteArrayOf(1), circled = true)
        assertTrue(context.screenText()!!.startsWith("Text inside the part of the user's phone screen they circled in Settings"))
        assertEquals(ScreenContext.CIRCLED_NAME, context.toAttachments().first().name)
    }
}
