package dev.hermeskotlin.designsystem.components

import androidx.compose.ui.graphics.ImageBitmap

// No TeX typesetter on iOS yet: formulas show as their source.
internal actual fun typesetMath(latex: String, textSizePx: Float, argb: Int, display: Boolean): ImageBitmap? = null
