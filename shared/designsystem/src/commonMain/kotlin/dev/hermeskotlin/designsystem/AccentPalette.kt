package dev.hermeskotlin.designsystem

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The accent the app is drawn in, for colors outside the theme's tokens (the assistant's edge light). */
val LocalAccentPalette = staticCompositionLocalOf { AccentPalette.Blue }

/**
 * The colors one accent gives a scheme: [accent] as a fill with [onAccent] on it, [text] for the accent as
 * text or an icon, the [soft] tint, and the user's bubble, which is filled with the accent.
 */
class AccentColors(
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val soft: Color,
    val bubble: Color = accent,
    val bubbleStroke: Color = accent,
)

/**
 * An accent the user can pick in Settings. [Blue] is Herald blue #2F6BF5: one fill with white on it in both
 * schemes, and a lighter blue for text on dark. The others use their accent as fill and text alike, so a preset
 * only needs a light and a dark accent. Light accents carry white text and dark accents near-black text, each
 * at 4.5:1 or more, and every accent's text reads at 4.5:1 on its background (AccentPaletteTest).
 */
class AccentPalette private constructor(
    val name: String,
    val light: AccentColors,
    val dark: AccentColors,
    val black: AccentColors,
) {
    companion object {
        private val DarkText = Color(0xFF0A0A0C)

        private val HeraldBlue = Color(0xFF2F6BF5)

        val Blue = AccentPalette(
            "Blue",
            light = AccentColors(HeraldBlue, Color.White, text = Color(0xFF1F56D6), soft = HeraldBlue.copy(alpha = 0.10f)),
            dark = AccentColors(HeraldBlue, Color.White, text = Color(0xFF7AA5FF), soft = Color(0xFF4A84FE).copy(alpha = 0.14f)),
            black = AccentColors(HeraldBlue, Color.White, text = Color(0xFF7AA5FF), soft = Color(0xFF4A84FE).copy(alpha = 0.14f)),
        )
        val Violet = mixed("Violet", light = Color(0xFF7C3AED), dark = Color(0xFFA78BFA))
        val Green = mixed("Green", light = Color(0xFF16803C), dark = Color(0xFF3FB950))
        val Orange = mixed("Orange", light = Color(0xFFBC4C00), dark = Color(0xFFF0883E))
        val Pink = mixed("Pink", light = Color(0xFFBF3989), dark = Color(0xFFF778BA))
        val Teal = mixed("Teal", light = Color(0xFF0E7490), dark = Color(0xFF22D3EE))

        val all: List<AccentPalette> = listOf(Blue, Violet, Green, Orange, Pink, Teal)

        /** The palette called [name], or [Blue] for a name this version doesn't know. */
        fun named(name: String?): AccentPalette = all.firstOrNull { it.name == name } ?: Blue

        // The tints sit at Blue's alphas.
        private fun mixed(name: String, light: Color, dark: Color): AccentPalette {
            val darkColors = AccentColors(dark, DarkText, text = dark, soft = dark.copy(alpha = 0.14f))
            return AccentPalette(
                name,
                light = AccentColors(light, Color.White, text = light, soft = light.copy(alpha = 0.10f)),
                dark = darkColors,
                black = darkColors,
            )
        }
    }
}
