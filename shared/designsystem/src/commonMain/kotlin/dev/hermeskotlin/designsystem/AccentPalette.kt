package dev.hermeskotlin.designsystem

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** The accent the app is drawn in, for colors outside the theme's tokens (the assistant's edge light). */
val LocalAccentPalette = staticCompositionLocalOf { AccentPalette.Blue }

/** The colors one accent gives a scheme: [accent], [onAccent] and [accentSoft], and the user's bubble tinted with it. */
class AccentColors(
    val accent: Color,
    val onAccent: Color,
    val soft: Color,
    val bubble: Color,
    val bubbleStroke: Color,
)

/**
 * An accent the user can pick in Settings. [Blue] is Desktop's Nous blue, exactly as it was before presets;
 * the others mix their tints from the accent the same way, so a preset only needs a light and a dark accent.
 * Light accents carry white text and dark accents near-black text, each at 4.5:1 or more (AccentPaletteTest).
 */
class AccentPalette private constructor(
    val name: String,
    val light: AccentColors,
    val dark: AccentColors,
    val black: AccentColors,
) {
    companion object {
        private val Canvas = Color.White
        private val Night = Color(0xFF0D1014)
        private val DarkText = Color(0xFF0D1117)

        val Blue = AccentPalette(
            "Blue",
            light = AccentColors(Color(0xFF0053FD), Color.White, Color(0xFFE3EDFF), Color(0xFFEDF3FF), Color(0xFFC9D8F5)),
            dark = AccentColors(Color(0xFF4A84FE), DarkText, Color(0xFF17243A), Color(0xFF0F1621), Color(0xFF1F2B41)),
            black = AccentColors(Color(0xFF4A84FE), DarkText, Color(0xFF17243A), Color(0xFF0B121C), Color(0xFF1F2B41)),
        )
        val Violet = mixed("Violet", light = Color(0xFF7C3AED), dark = Color(0xFFA78BFA))
        val Green = mixed("Green", light = Color(0xFF16803C), dark = Color(0xFF3FB950))
        val Orange = mixed("Orange", light = Color(0xFFBC4C00), dark = Color(0xFFF0883E))
        val Pink = mixed("Pink", light = Color(0xFFBF3989), dark = Color(0xFFF778BA))
        val Teal = mixed("Teal", light = Color(0xFF0E7490), dark = Color(0xFF22D3EE))

        val all: List<AccentPalette> = listOf(Blue, Violet, Green, Orange, Pink, Teal)

        /** The palette called [name], or [Blue] for a name this version doesn't know. */
        fun named(name: String?): AccentPalette = all.firstOrNull { it.name == name } ?: Blue

        // The fractions are the ones Blue's hand-picked tints sit at.
        private fun mixed(name: String, light: Color, dark: Color): AccentPalette {
            val darkColors = AccentColors(dark, DarkText, lerp(Night, dark, 0.16f), lerp(Night, dark, 0.05f), lerp(Night, dark, 0.22f))
            return AccentPalette(
                name,
                light = AccentColors(light, Color.White, lerp(Canvas, light, 0.11f), lerp(Canvas, light, 0.07f), lerp(Canvas, light, 0.22f)),
                dark = darkColors,
                black = AccentColors(dark, DarkText, darkColors.soft, lerp(Color.Black, dark, 0.1f), darkColors.bubbleStroke),
            )
        }
    }
}
