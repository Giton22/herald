package dev.hermeskotlin.designsystem

import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composeunstyled.theme.ColorScheme
import com.composeunstyled.theme.buildThemeV2
import com.composeunstyled.theme.rememberColoredIndication

// Body text sits a hair tighter than the default, as the design's -0.005em does.
private val baseText = TextStyle(fontFamily = Geist, letterSpacing = (-0.005).em)

/** Dark with true black backgrounds, for OLED screens. */
val PureBlack = ColorScheme("pure_black")

/**
 * The app theme in Herald blue. Light/dark follow the system unless a [ColorScheme] is passed:
 * `HermesTheme(ColorScheme.Dark) { ... }`, or [PureBlack]. [hermesTheme] gives it in another accent.
 * The colors are in ThemeColors.kt.
 */
val HermesTheme = buildHermesTheme(AccentPalette.Blue)

private val accentThemes = AccentPalette.all.associateWith { if (it == AccentPalette.Blue) HermesTheme else buildHermesTheme(it) }

/** The app theme with the [accent] picked in Settings. */
fun hermesTheme(accent: AccentPalette) = accentThemes.getValue(accent)

private fun buildHermesTheme(palette: AccentPalette) = buildThemeV2 {
    name = "HermesTheme" + palette.name.takeIf { palette != AccentPalette.Blue }.orEmpty()
    colorSchemeTransitionSpec = tween(180)

    properties[radii] = mapOf(
        radiusXSmall to 6.dp,
        radiusSmall to 10.dp,
        radiusMedium to 14.dp,
        radiusLarge to 20.dp,
        radiusXLarge to 26.dp,
        radiusFull to 999.dp,
    )

    // Hierarchy comes from size and color steps, so only 400, 500 and 600 are used.
    properties[typography] = mapOf(
        display to baseText.copy(fontSize = 32.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em),
        title to baseText.copy(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
        heading to baseText.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
        body to baseText.copy(fontSize = 15.sp, lineHeight = 22.5.sp),
        bodySmall to baseText.copy(fontSize = 13.5.sp, lineHeight = 19.5.sp),
        label to baseText.copy(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
        caption to baseText.copy(fontSize = 12.sp, lineHeight = 16.sp),
        code to TextStyle(fontFamily = GeistMono, fontSize = 12.5.sp, lineHeight = 19.5.sp),
        eyebrow to baseText.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em),
        wordmark to baseText.copy(fontSize = 56.sp, lineHeight = 52.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em),
    )

    // Selection keeps a stronger tint than accent soft, which is too faint behind selected text.
    colorScheme(ColorScheme.Light) {
        properties[colors] = lightColors(palette.light)
        defaultContentColor = Palette.LightText
        defaultIndication = rememberColoredIndication(Palette.LightText)
        defaultTextSelectionColors = TextSelectionColors(palette.light.accent, palette.light.accent.copy(alpha = 0.25f))
    }

    colorScheme(ColorScheme.Dark) {
        properties[colors] = darkColors(palette.dark)
        defaultContentColor = Palette.Text
        defaultIndication = rememberColoredIndication(Palette.Text)
        defaultTextSelectionColors = TextSelectionColors(palette.dark.text, palette.dark.text.copy(alpha = 0.35f))
    }

    colorScheme(PureBlack) {
        properties[colors] = pureBlackColors(palette.black)
        defaultContentColor = Palette.Text
        defaultIndication = rememberColoredIndication(Palette.Text)
        defaultTextSelectionColors = TextSelectionColors(palette.black.text, palette.black.text.copy(alpha = 0.35f))
    }

    defaultTextStyle = baseText.copy(fontSize = 15.sp, lineHeight = 22.5.sp)
}
