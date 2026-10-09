package dev.hermeskotlin.designsystem

import androidx.compose.ui.graphics.Color

/**
 * The Herald "modern" palette: graphite surfaces in dark, cool off-white in light. Surfaces step up in
 * lightness (surface → surface 2 → surface 3) instead of casting shadows, and hairlines are translucent.
 * Kept apart from HermesTheme.kt, which loads the fonts, so tests can read the colors without Android resources.
 */
internal object Palette {
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

internal fun lightColors(accentColors: AccentColors) = mapOf(
    background to Palette.LightBg,
    surface to Palette.LightSurface,
    surface2 to Palette.LightSurface2,
    surface3 to Palette.LightSurface3,
    well to Palette.LightWell,
    surfaceElevated to Palette.LightSurface,
    input to Palette.LightSurface,
    sidebar to Palette.LightSurface,
    userBubble to accentColors.bubble,
    userBubbleStroke to accentColors.bubbleStroke,
    onUserBubble to accentColors.onAccent,
    text to Palette.LightText,
    textSecondary to Palette.LightText2,
    textTertiary to Palette.LightText4,
    textMuted to Palette.LightText5,
    accent to accentColors.accent,
    onAccent to accentColors.onAccent,
    accentText to accentColors.text,
    accentSoft to accentColors.soft,
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

/** Pure black: the dark roles on a true black ground, with each graphite step pulled toward black. */
internal fun pureBlackColors(accentColors: AccentColors) = darkColors(
    accentColors, Color.Black, Palette.BlackSurface, Palette.BlackSurface2, Palette.BlackSurface3, Palette.BlackWell, Palette.BlackSheet,
)

/** Dark and pure black share every role but the grounds they sit on. */
internal fun darkColors(
    accentColors: AccentColors,
    bg: Color = Palette.Bg,
    surfaceColor: Color = Palette.Surface,
    surface2Color: Color = Palette.Surface2,
    surface3Color: Color = Palette.Surface3,
    wellColor: Color = Palette.Well,
    sheet: Color = Palette.Sheet,
) = mapOf(
    background to bg,
    surface to surfaceColor,
    surface2 to surface2Color,
    surface3 to surface3Color,
    well to wellColor,
    surfaceElevated to sheet,
    input to surfaceColor,
    sidebar to surfaceColor,
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
