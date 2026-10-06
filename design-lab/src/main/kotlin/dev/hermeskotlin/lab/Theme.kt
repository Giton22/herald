package dev.hermeskotlin.lab

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/*
 * "Signal": a from-scratch look for Herald.
 * Soft, rounded surfaces in near-black, one blue signal color, amber for "needs you", and a pixel layer on top:
 * a pixel face for labels, dithered glows, pixel sprites for agents, staircase hazard stripes.
 */

private fun geist(weight: Int) = Font("fonts/Geist.ttf", FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
private fun geistMono(weight: Int) = Font("fonts/GeistMono.ttf", FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Geist = FontFamily(geist(400), geist(500), geist(600), geist(700))
val GeistMono = FontFamily(geistMono(400), geistMono(500), geistMono(600))
val Pixel = FontFamily(Font("fonts/Silkscreen.ttf", FontWeight.Normal))

class SignalColors(
    val void: Color,
    val bg: Color,
    val s1: Color,
    val s2: Color,
    val s3: Color,
    val line: Color,
    val line2: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val text4: Color,
    val signal: Color,
    val signalHi: Color,
    val signalSoft: Color,
    val onSignal: Color,
    val ember: Color,
    val emberSoft: Color,
    val ok: Color,
    val bad: Color,
    val dark: Boolean,
)

/** Herald's current dark "Nous" skin, as HermesTheme.kt defines it. */
val Dark = SignalColors(
    void = Color(0xFF000000),
    bg = Color(0xFF0D1014),
    s1 = Color(0xFF151B23),
    s2 = Color(0xFF1A2029),
    s3 = Color(0xFF222935),
    line = Color(0xFF21262D),
    line2 = Color(0xFF30363D),
    text = Color(0xFFE6EDF3),
    text2 = Color(0xC7E6EDF3),
    text3 = Color(0xFF7D8590),
    text4 = Color(0xFF484F58),
    signal = Color(0xFF4A84FE),
    signalHi = Color(0xFF8AB0FF),
    signalSoft = Color(0xFF17243A),
    onSignal = Color(0xFF0D1117),
    ember = Color(0xFFD29922),
    emberSoft = Color(0xFF2A2112),
    ok = Color(0xFF3FB950),
    bad = Color(0xFFF85149),
    dark = true,
)

val Sidebar = Color(0xFF090B0F)
val Bubble = Color(0xFF0F1621)
val BubbleStroke = Color(0xFF1F2B41)

private fun roboto(weight: Int) = Font("fonts/Roboto.ttf", FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
private fun robotoMono(weight: Int) = Font("fonts/RobotoMono.ttf", FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
val Roboto = FontFamily(roboto(400), roboto(500), roboto(600), roboto(700), roboto(900))
val RobotoMono = FontFamily(robotoMono(400), robotoMono(500))

object Type {
    val display = TextStyle(fontFamily = Geist, fontWeight = FontWeight(600), fontSize = 36.sp, lineHeight = 38.sp, letterSpacing = (-1.2).sp)
    val title = TextStyle(fontFamily = Geist, fontWeight = FontWeight(600), fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.6).sp)
    val head = TextStyle(fontFamily = Geist, fontWeight = FontWeight(600), fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.2).sp)
    val body = TextStyle(fontFamily = Geist, fontWeight = FontWeight(400), fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = (-0.1).sp)
    val small = TextStyle(fontFamily = Geist, fontWeight = FontWeight(400), fontSize = 13.sp, lineHeight = 18.sp)
    val label = TextStyle(fontFamily = Geist, fontWeight = FontWeight(500), fontSize = 13.sp, lineHeight = 18.sp)
    val pixel = TextStyle(fontFamily = Pixel, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.9.sp)
    val mono = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight(400), fontSize = 12.sp, lineHeight = 18.sp)
    val monoSmall = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight(400), fontSize = 11.sp, lineHeight = 15.sp)
}

val LocalSignal = staticCompositionLocalOf { Dark }

val S: SignalColors @Composable get() = LocalSignal.current

@Composable
fun SignalTheme(colors: SignalColors = Dark, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSignal provides colors, content = content)
}
