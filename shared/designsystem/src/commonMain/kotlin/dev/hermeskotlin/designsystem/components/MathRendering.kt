package dev.hermeskotlin.designsystem.components

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Typesets [latex] (TeX math, without its delimiters) into a picture [textSizePx] tall per em in [argb],
 * as display math or inline. Null when the platform has no typesetter or the formula doesn't parse;
 * the caller shows the source instead.
 */
internal expect fun typesetMath(latex: String, textSizePx: Float, argb: Int, display: Boolean): ImageBitmap?

/**
 * Typeset formulas, so a reply re-rendering on every streamed delta doesn't typeset the same formula
 * again. Composition only (the main thread), so it needs no lock.
 */
internal object MathPictures {
    private data class Key(val latex: String, val textSizePx: Float, val argb: Int, val display: Boolean)

    private const val MAX_ENTRIES = 96
    private val entries = LinkedHashMap<Key, Result<ImageBitmap?>>()

    fun get(latex: String, textSizePx: Float, argb: Int, display: Boolean): ImageBitmap? {
        val key = Key(latex, textSizePx, argb, display)
        val hit = entries.remove(key)
        val result = hit ?: runCatching { typesetMath(latex, textSizePx, argb, display) }
        entries[key] = result
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        return result.getOrNull()
    }
}
