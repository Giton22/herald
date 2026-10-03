package dev.hermeskotlin.designsystem

import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.theme.ColorScheme
import com.composeunstyled.theme.buildThemeV2
import com.composeunstyled.theme.rememberColoredIndication

private object Palette {
    val Blue = Color(0xFF0053FD)
    val BlueDark = Color(0xFF4D86FF)

    val Ink = Color(0xFF17171A)
    val Paper = Color(0xFFF8FAFF)

    val Night = Color(0xFF0A0A0B)
    val NightCard = Color(0xFF161618)
    val NightElevated = Color(0xFF1C1C1F)
    val Snow = Color(0xFFEDEDF0)
}

private val baseText = TextStyle(fontFamily = FontFamily.Default)

/**
 * The app theme. Light/dark follow the system unless a [ColorScheme] is passed:
 * `HermesTheme(ColorScheme.Dark) { ... }`.
 */
val HermesTheme = buildThemeV2 {
    name = "HermesTheme"
    colorSchemeTransitionSpec = tween(200)

    properties[radii] = mapOf(
        radiusSmall to 6.dp,
        radiusMedium to 10.dp,
        radiusLarge to 16.dp,
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
    )

    colorScheme(ColorScheme.Light) {
        properties[colors] = mapOf(
            background to Palette.Paper,
            surface to Color.White,
            surfaceElevated to Color.White,
            input to Color(0xFFFCFCFC),
            text to Palette.Ink,
            textSecondary to Palette.Ink.copy(alpha = 0.74f),
            textTertiary to Palette.Ink.copy(alpha = 0.54f),
            accent to Palette.Blue,
            onAccent to Color.White,
            accentSoft to Color(0xFFE6EEFF),
            stroke to Palette.Ink.copy(alpha = 0.08f),
            strokeStrong to Palette.Ink.copy(alpha = 0.16f),
            danger to Color(0xFFCF2D56),
            success to Color(0xFF1F8A65),
            warning to Color(0xFFC08532),
        )
        defaultContentColor = Palette.Ink
        defaultIndication = rememberColoredIndication(Palette.Ink)
        defaultTextSelectionColors = TextSelectionColors(Palette.Blue, Palette.Blue.copy(alpha = 0.3f))
    }

    colorScheme(ColorScheme.Dark) {
        properties[colors] = mapOf(
            background to Palette.Night,
            surface to Palette.NightCard,
            surfaceElevated to Palette.NightElevated,
            input to Color(0xFF111113),
            text to Palette.Snow,
            textSecondary to Palette.Snow.copy(alpha = 0.72f),
            textTertiary to Palette.Snow.copy(alpha = 0.5f),
            accent to Palette.BlueDark,
            onAccent to Color.White,
            accentSoft to Color(0xFF15213A),
            stroke to Palette.Snow.copy(alpha = 0.08f),
            strokeStrong to Palette.Snow.copy(alpha = 0.18f),
            danger to Color(0xFFE75E78),
            success to Color(0xFF55A583),
            warning to Color(0xFFD9A15A),
        )
        defaultContentColor = Palette.Snow
        defaultIndication = rememberColoredIndication(Palette.Snow)
        defaultTextSelectionColors = TextSelectionColors(Palette.BlueDark, Palette.BlueDark.copy(alpha = 0.35f))
    }

    defaultTextStyle = baseText.copy(fontSize = 16.sp, lineHeight = 24.sp)
}
