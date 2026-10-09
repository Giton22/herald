package dev.hermeskotlin.designsystem

import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composeunstyled.theme.ColorScheme
import com.composeunstyled.theme.buildThemeV2
import com.composeunstyled.theme.rememberColoredIndication

/**
 * The Herald "modern" palette: graphite surfaces in dark, cool off-white in light. Surfaces step up in
 * lightness (surface → surface 2 → surface 3) instead of casting shadows, and hairlines are translucent.
 */
private object Palette {
    val Bg = Color(0xFF0A0A0C)
    val Surface = Color(0xFF16161A)
    val Surface2 = Color(0xFF1C1C22)
    val Surface3 = Color(0xFF232329)
    val Well = Color(0xFF121215)
    val Sheet = Color(0xFF18181D)
    val Line = Color.White.copy(alpha = 0.07f)
    val LineStrong = Color.White.copy(alpha = 0.12f)
    val Text = Color(0xFFF4F4F6)
    val Text2 = Color(0xFFD4D4D8)
    val Text4 = Color(0xFF8A8A94)
    val Text5 = Color(0xFF6B6B75)

    val LightBg = Color(0xFFF7F7F9)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurface2 = Color(0xFFF1F1F5)
    val LightSurface3 = Color(0xFFE9E9EF)
    val LightWell = Color(0xFFF3F3F7)
    val LightLine = Color(0xFF0A0A1E).copy(alpha = 0.08f)
    val LightLineStrong = Color(0xFF0A0A1E).copy(alpha = 0.14f)
    val LightText = Color(0xFF0A0A0C)
    val LightText2 = Color(0xFF27272A)
    val LightText4 = Color(0xFF6B6B75)
    val LightText5 = Color(0xFF8E8E98)

    // Pure black keeps the graphite steps, each pulled a little toward black.
    val BlackSurface = Color(0xFF111114)
    val BlackSurface2 = Color(0xFF17171C)
    val BlackSurface3 = Color(0xFF1E1E24)
    val BlackWell = Color(0xFF0C0C0E)
    val BlackSheet = Color(0xFF141418)

    val Danger = Color(0xFFF85149)
    val Success = Color(0xFF3FB950)
    val Warning = Color(0xFFF5A524)
    val LightDanger = Color(0xFFCF222E)
    val LightSuccess = Color(0xFF1A7F37)
    val LightWarning = Color(0xFFB76E00)
}

// Body text sits a hair tighter than the default, as the design's -0.005em does.
private val baseText = TextStyle(fontFamily = FontFamily.Default, letterSpacing = (-0.005).em)

/** Dark with true black backgrounds, for OLED screens. */
val PureBlack = ColorScheme("pure_black")

/**
 * The app theme in Herald blue. Light/dark follow the system unless a [ColorScheme] is passed:
 * `HermesTheme(ColorScheme.Dark) { ... }`, or [PureBlack]. [hermesTheme] gives it in another accent.
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
        code to TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 19.5.sp),
        eyebrow to baseText.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em),
        wordmark to baseText.copy(fontSize = 56.sp, lineHeight = 52.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em),
    )

    colorScheme(ColorScheme.Light) {
        properties[colors] = mapOf(
            background to Palette.LightBg,
            surface to Palette.LightSurface,
            surface2 to Palette.LightSurface2,
            surface3 to Palette.LightSurface3,
            well to Palette.LightWell,
            surfaceElevated to Palette.LightSurface,
            input to Palette.LightSurface,
            sidebar to Palette.LightBg,
            userBubble to palette.light.bubble,
            userBubbleStroke to palette.light.bubbleStroke,
            onUserBubble to palette.light.onAccent,
            text to Palette.LightText,
            textSecondary to Palette.LightText2,
            textTertiary to Palette.LightText4,
            textMuted to Palette.LightText5,
            accent to palette.light.accent,
            onAccent to palette.light.onAccent,
            accentText to palette.light.text,
            accentSoft to palette.light.soft,
            inverse to Palette.LightText,
            onInverse to Color.White,
            stroke to Palette.LightLine,
            strokeStrong to Palette.LightLineStrong,
            danger to Palette.LightDanger,
            success to Palette.LightSuccess,
            warning to Palette.LightWarning,
            dangerSoft to Palette.LightDanger.copy(alpha = 0.09f),
            successSoft to Palette.LightSuccess.copy(alpha = 0.10f),
            warningSoft to Palette.LightWarning.copy(alpha = 0.10f),
        )
        defaultContentColor = Palette.LightText
        defaultIndication = rememberColoredIndication(Palette.LightText)
        defaultTextSelectionColors = TextSelectionColors(palette.light.accent, palette.light.soft)
    }

    colorScheme(ColorScheme.Dark) {
        properties[colors] = darkColors(palette.dark, Palette.Bg, Palette.Surface, Palette.Surface2, Palette.Surface3, Palette.Well, Palette.Sheet)
        defaultContentColor = Palette.Text
        defaultIndication = rememberColoredIndication(Palette.Text)
        defaultTextSelectionColors = TextSelectionColors(palette.dark.text, palette.dark.soft)
    }

    colorScheme(PureBlack) {
        properties[colors] = darkColors(
            palette.black, Color.Black, Palette.BlackSurface, Palette.BlackSurface2, Palette.BlackSurface3, Palette.BlackWell, Palette.BlackSheet,
        )
        defaultContentColor = Palette.Text
        defaultIndication = rememberColoredIndication(Palette.Text)
        defaultTextSelectionColors = TextSelectionColors(palette.black.text, palette.black.soft)
    }

    defaultTextStyle = baseText.copy(fontSize = 15.sp, lineHeight = 22.5.sp)
}

/** Dark and pure black share every role but the grounds they sit on. */
private fun darkColors(
    accentColors: AccentColors,
    bg: Color,
    surfaceColor: Color,
    surface2Color: Color,
    surface3Color: Color,
    wellColor: Color,
    sheet: Color,
) = mapOf(
    background to bg,
    surface to surfaceColor,
    surface2 to surface2Color,
    surface3 to surface3Color,
    well to wellColor,
    surfaceElevated to sheet,
    input to surfaceColor,
    sidebar to bg,
    userBubble to accentColors.bubble,
    userBubbleStroke to accentColors.bubbleStroke,
    onUserBubble to accentColors.onAccent,
    text to Palette.Text,
    textSecondary to Palette.Text2,
    textTertiary to Palette.Text4,
    textMuted to Palette.Text5,
    accent to accentColors.accent,
    onAccent to accentColors.onAccent,
    accentText to accentColors.text,
    accentSoft to accentColors.soft,
    inverse to Palette.Text,
    onInverse to Palette.Bg,
    stroke to Palette.Line,
    strokeStrong to Palette.LineStrong,
    danger to Palette.Danger,
    success to Palette.Success,
    warning to Palette.Warning,
    dangerSoft to Palette.Danger.copy(alpha = 0.14f),
    successSoft to Palette.Success.copy(alpha = 0.13f),
    warningSoft to Palette.Warning.copy(alpha = 0.13f),
)
