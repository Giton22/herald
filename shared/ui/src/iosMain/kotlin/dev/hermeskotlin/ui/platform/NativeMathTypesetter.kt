package dev.hermeskotlin.ui.platform

import dev.hermeskotlin.designsystem.components.IosMath
import platform.Foundation.NSData

/**
 * Typesets TeX math for the chat, which the Swift host implements with SwiftMath. Called on the main thread,
 * once per formula, size and colour (the shared code keeps the pictures).
 */
interface NativeMathTypesetter {
    /**
     * [latex] (without its delimiters) as a PNG at the screen's scale, [textSizePx] pixels per em in [argb], as
     * display math or inline; null when it doesn't parse, and the chat shows the source.
     */
    fun typeset(latex: String, textSizePx: Float, argb: Int, display: Boolean): NSData?
}

/** Called by the Swift host at launch. Without it, formulas show as their source. */
fun setMathTypesetter(typesetter: NativeMathTypesetter) {
    IosMath.typeset = typesetter::typeset
}
