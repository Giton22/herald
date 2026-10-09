package dev.hermeskotlin.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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

    private fun schemes(palette: AccentPalette) = listOf(
        "light" to lightColors(palette.light),
        "dark" to darkColors(palette.dark),
        "black" to pureBlackColors(palette.black),
    )

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
    fun accentTextReadsOnEveryGroundItSitsOn() {
        for (palette in AccentPalette.all) {
            for ((scheme, colors) in schemes(palette)) {
                val text = colors.getValue(accentText)
                val grounds = listOf(background, surface, surface2, surface3, surfaceElevated).map { colors.getValue(it) }
                // Selected chips, rows and secondary buttons: the soft tint over a card.
                val tinted = colors.getValue(accentSoft).compositeOver(colors.getValue(surface))
                for (ground in grounds + tinted) {
                    val ratio = contrast(text, ground)
                    assertTrue(ratio >= 4.5f, "${palette.name} $scheme: accent text on $ground is $ratio:1")
                }
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
