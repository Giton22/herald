package dev.hermeskotlin.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccentPaletteTest {

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    @Test
    fun textOnEveryAccentIsReadable() {
        for (palette in AccentPalette.all) {
            for ((scheme, colors) in listOf("light" to palette.light, "dark" to palette.dark, "black" to palette.black)) {
                val ratio = contrast(colors.onAccent, colors.accent)
                assertTrue(ratio >= 4.5f, "${palette.name} $scheme: on-accent text is $ratio:1")
            }
        }
    }

    @Test
    fun everyAccentReadsAsLinkTextOnItsBackground() {
        for (palette in AccentPalette.all) {
            assertTrue(contrast(palette.light.text, Color(0xFFF7F7F9)) >= 4.5f, "${palette.name} on the light background")
            assertTrue(contrast(palette.light.text, Color.White) >= 4.5f, "${palette.name} on a light card")
            assertTrue(contrast(palette.dark.text, Color(0xFF0A0A0C)) >= 4.5f, "${palette.name} on the dark background")
            assertTrue(contrast(palette.dark.text, Color(0xFF16161A)) >= 4.5f, "${palette.name} on a dark card")
            assertTrue(contrast(palette.black.text, Color.Black) >= 4.5f, "${palette.name} on black")
        }
    }

    @Test
    fun textOnTheUsersBubbleIsReadable() {
        for (palette in AccentPalette.all) {
            for ((scheme, colors) in listOf("light" to palette.light, "dark" to palette.dark, "black" to palette.black)) {
                val ratio = contrast(colors.onAccent, colors.bubble)
                assertTrue(ratio >= 4.5f, "${palette.name} $scheme: bubble text is $ratio:1")
            }
        }
    }

    @Test
    fun anUnknownOrMissingNameIsBlue() {
        assertEquals(AccentPalette.Blue, AccentPalette.named("Neon"))
        assertEquals(AccentPalette.Blue, AccentPalette.named(null))
        assertEquals(AccentPalette.Teal, AccentPalette.named("Teal"))
    }

    @Test
    fun namesAreUnique() {
        assertEquals(AccentPalette.all.size, AccentPalette.all.map { it.name }.toSet().size)
    }
}
