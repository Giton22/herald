package dev.hermeskotlin.ui.voice

import androidx.compose.ui.graphics.toArgb
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.wearlink.WatchAccent

/**
 * The accent preset called [name] as the watch draws it: its dark colors, since the watch is always dark. The
 * soft tint goes opaque as the watch's dim accent.
 */
fun watchAccent(name: String?): WatchAccent {
    val colors = AccentPalette.named(name).dark
    return WatchAccent.of(colors.accent.toArgb(), colors.text.toArgb(), colors.onAccent.toArgb(), colors.soft.copy(alpha = 1f).toArgb())
}
