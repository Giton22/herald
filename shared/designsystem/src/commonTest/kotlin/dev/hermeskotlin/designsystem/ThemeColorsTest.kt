package dev.hermeskotlin.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeColorsTest {

    // Every color token in Tokens.kt. A lookup of a token a scheme lacks crashes at runtime, so all must be in each one.
    private val allTokens = setOf(
        background, surface, surface2, surface3, well, thumb, surfaceElevated, input, sidebar,
        userBubble, userBubbleStroke, onUserBubble,
        text, textSecondary, textTertiary, textMuted,
        accent, onAccent, accentText, accentSoft, inverse, onInverse,
        stroke, strokeStrong,
        danger, success, warning, dangerSoft, successSoft, warningSoft,
    )

    @Test
    fun everySchemeDefinesEveryColorToken() {
        for (palette in AccentPalette.all) {
            assertEquals(allTokens, lightColors(palette.light).keys, "${palette.name} light")
            assertEquals(allTokens, darkColors(palette.dark).keys, "${palette.name} dark")
            assertEquals(allTokens, pureBlackColors(palette.black).keys, "${palette.name} pure black")
        }
    }

    @Test
    fun theUsersBubbleCarriesItsOwnTextColor() {
        for (palette in AccentPalette.all) {
            for (scheme in listOf(lightColors(palette.light), darkColors(palette.dark), pureBlackColors(palette.black))) {
                assertEquals(scheme.getValue(accent), scheme.getValue(userBubble), palette.name)
                assertEquals(scheme.getValue(onAccent), scheme.getValue(onUserBubble), palette.name)
            }
        }
    }
}
