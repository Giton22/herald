package dev.hermeskotlin.designsystem.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import ru.noties.jlatexmath.JLatexMathDrawable

/** JLaTeXMath, a Java port of TeX's math layout with its own fonts; it initialises itself at app start. */
internal actual fun typesetMath(latex: String, textSizePx: Float, argb: Int, display: Boolean): ImageBitmap? {
    val style = if (display) "\\displaystyle " else "\\textstyle "
    val drawable = JLatexMathDrawable.builder(style + latex)
        .textSize(textSizePx)
        .color(argb)
        .align(JLatexMathDrawable.ALIGN_LEFT)
        .build()
    val width = drawable.intrinsicWidth
    val height = drawable.intrinsicHeight
    // A runaway formula shouldn't allocate a huge bitmap.
    if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return null
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, width, height)
    drawable.draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}

private const val MAX_PIXELS = 4096L * 2048L
