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
 * Hermes Desktop's default "Nous" skin: GitHub's Light/Dark Default neutrals carrying Nous blue,
 * which dark mode lifts to #4A84FE to stay legible on near-black. Dark values are as Desktop renders them.
 */
private object Palette {
    val Blue = Color(0xFF0053FD)
    val BlueDark = Color(0xFF4A84FE)

    val Ink = Color(0xFF1F2328)
    val InkMuted = Color(0xFF656D76)
    val Canvas = Color(0xFFFFFFFF)
    val CanvasSubtle = Color(0xFFF6F8FA)
    val Border = Color(0xFFD0D7DE)
    val BorderMuted = Color(0xFFD8DEE4)

    val Night = Color(0xFF0D1014)
    val NightSidebar = Color(0xFF090B0F)
    val NightCard = Color(0xFF151B23)
    val NightElevated = Color(0xFF1A2029)
    val Snow = Color(0xFFE6EDF3)
    val SnowMuted = Color(0xFF7D8590)
    val NightBorder = Color(0xFF30363D)
    val NightBorderMuted = Color(0xFF21262D)
}

private val baseText = TextStyle(fontFamily = FontFamily.Default)

/** Dark with true black backgrounds, for OLED screens. */
val PureBlack = ColorScheme("pure_black")

/**
 * The app theme. Light/dark follow the system unless a [ColorScheme] is passed:
 * `HermesTheme(ColorScheme.Dark) { ... }`, or [PureBlack].
 */
val HermesTheme = buildThemeV2 {
    name = "HermesTheme"
    colorSchemeTransitionSpec = tween(200)

    // Desktop scales every radius by 0.2: corners are barely there.
    properties[radii] = mapOf(
        radiusSmall to 2.dp,
        radiusMedium to 4.dp,
        radiusLarge to 6.dp,
        radiusFull to 999.dp,
    )

    properties[typography] = mapOf(
        display to baseText.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        title to baseText.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        heading to baseText.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        body to baseText.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodySmall to baseText.copy(fontSize = 14.sp, lineHeight = 20.sp),
        label to baseText.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        caption to baseText.copy(fontSize = 12.sp, lineHeight = 16.sp),
        code to TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp),
        eyebrow to baseText.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.14.em),
        wordmark to baseText.copy(fontSize = 56.sp, lineHeight = 52.sp, fontWeight = FontWeight.Black, letterSpacing = 0.08.em),
    )

    colorScheme(ColorScheme.Light) {
        properties[colors] = mapOf(
            background to Palette.Canvas,
            surface to Palette.CanvasSubtle,
            surfaceElevated to Palette.Canvas,
            input to Palette.Canvas,
            sidebar to Palette.CanvasSubtle,
            userBubble to Color(0xFFEDF3FF),
            userBubbleStroke to Color(0xFFC9D8F5),
            text to Palette.Ink,
            textSecondary to Palette.Ink.copy(alpha = 0.78f),
            textTertiary to Palette.InkMuted,
            accent to Palette.Blue,
            onAccent to Color.White,
            accentSoft to Color(0xFFE3EDFF),
            stroke to Palette.BorderMuted,
            strokeStrong to Palette.Border,
            danger to Color(0xFFCF222E),
            success to Color(0xFF1A7F37),
            warning to Color(0xFF9A6700),
        )
        defaultContentColor = Palette.Ink
        defaultIndication = rememberColoredIndication(Palette.Ink)
        defaultTextSelectionColors = TextSelectionColors(Palette.Blue, Palette.Blue.copy(alpha = 0.25f))
    }

    colorScheme(ColorScheme.Dark) {
        properties[colors] = mapOf(
            background to Palette.Night,
            surface to Palette.NightCard,
            surfaceElevated to Palette.NightElevated,
            input to Palette.Night,
            sidebar to Palette.NightSidebar,
            userBubble to Color(0xFF0F1621),
            userBubbleStroke to Color(0xFF1F2B41),
            text to Palette.Snow,
            textSecondary to Palette.Snow.copy(alpha = 0.78f),
            textTertiary to Palette.SnowMuted,
            accent to Palette.BlueDark,
            onAccent to Color(0xFF0D1117),
            accentSoft to Color(0xFF17243A),
            stroke to Palette.NightBorderMuted,
            strokeStrong to Palette.NightBorder,
            danger to Color(0xFFF85149),
            success to Color(0xFF3FB950),
            warning to Color(0xFFD29922),
        )
        defaultContentColor = Palette.Snow
        defaultIndication = rememberColoredIndication(Palette.Snow)
        defaultTextSelectionColors = TextSelectionColors(Palette.BlueDark, Palette.BlueDark.copy(alpha = 0.35f))
    }

    colorScheme(PureBlack) {
        properties[colors] = mapOf(
            background to Color.Black,
            surface to Color(0xFF0D1014),
            surfaceElevated to Palette.NightCard,
            input to Color.Black,
            sidebar to Color.Black,
            userBubble to Color(0xFF0B121C),
            userBubbleStroke to Color(0xFF1F2B41),
            text to Palette.Snow,
            textSecondary to Palette.Snow.copy(alpha = 0.78f),
            textTertiary to Palette.SnowMuted,
            accent to Palette.BlueDark,
            onAccent to Color(0xFF0D1117),
            accentSoft to Color(0xFF17243A),
            stroke to Palette.NightBorderMuted,
            strokeStrong to Palette.NightBorder,
            danger to Color(0xFFF85149),
            success to Color(0xFF3FB950),
            warning to Color(0xFFD29922),
        )
        defaultContentColor = Palette.Snow
        defaultIndication = rememberColoredIndication(Palette.Snow)
        defaultTextSelectionColors = TextSelectionColors(Palette.BlueDark, Palette.BlueDark.copy(alpha = 0.35f))
    }

    defaultTextStyle = baseText.copy(fontSize = 16.sp, lineHeight = 24.sp)
}
