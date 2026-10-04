package dev.hermeskotlin.ui.assistant

import kotlin.math.max
import kotlin.math.min

/** A rectangle on the phone's display, in its pixels: where a piece of text sits, or what the user circled. */
data class ScreenRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2

    operator fun contains(point: Pair<Float, Float>): Boolean =
        point.first in left..right && point.second in top..bottom

    /** Grown by [by] on every side and kept within [width] × [height]. */
    fun padded(by: Float, width: Int, height: Int) = ScreenRegion(
        max(0f, left - by),
        max(0f, top - by),
        min(width.toFloat(), right + by),
        min(height.toFloat(), bottom + by),
    )

    /** The same rectangle measured in other pixels: [x] and [y] are how many of those make one of these. */
    fun scaled(x: Float, y: Float) = ScreenRegion(left * x, top * y, right * x, bottom * y)

    companion object {
        /** The box around a stroke, or null for no points. */
        fun around(points: List<Pair<Float, Float>>): ScreenRegion? {
            if (points.isEmpty()) return null
            return ScreenRegion(
                points.minOf { it.first },
                points.minOf { it.second },
                points.maxOf { it.first },
                points.maxOf { it.second },
            )
        }
    }
}

/** A piece of text on the screen and where it sits. [bounds] is null when the platform didn't say. */
data class ScreenItem(val text: String, val bounds: ScreenRegion? = null)

/** The text the user circled: each piece whose middle falls inside [region], in reading order. */
fun List<ScreenItem>.inside(region: ScreenRegion): List<ScreenItem> = filter { item ->
    val box = item.bounds ?: return@filter false
    (box.centerX to box.centerY) in region
}

/**
 * What a tap at [point] picks: the smallest piece of text under the finger, so tapping a word in a
 * paragraph gets that line rather than the whole page. Null when there's no text there.
 */
fun List<ScreenItem>.tapped(point: Pair<Float, Float>): ScreenItem? =
    filter { it.bounds?.let { box -> point in box } == true && it.text.isNotBlank() }
        .minByOrNull { it.bounds!!.width * it.bounds.height }
