package dev.hermeskotlin.designsystem.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import java.awt.Color
import java.awt.image.BufferedImage

/** JLaTeXMath itself, which the Android port comes from, drawing into an AWT image. */
internal actual fun typesetMath(latex: String, textSizePx: Float, argb: Int, display: Boolean): ImageBitmap? = runCatching {
    val style = if (display) TeXConstants.STYLE_DISPLAY else TeXConstants.STYLE_TEXT
    val icon = TeXFormula(latex).createTeXIcon(style, textSizePx)
    icon.setForeground(Color(argb, true))
    val width = icon.iconWidth
    val height = icon.iconHeight
    // A runaway formula shouldn't allocate a huge bitmap.
    if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return null
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    icon.paintIcon(null, graphics, 0, 0)
    graphics.dispose()
    image.toComposeImageBitmap()
}.getOrNull()

private const val MAX_PIXELS = 4096L * 2048L
