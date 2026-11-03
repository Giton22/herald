package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.hermesTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The desktop's table scrollbar, rendered with the real [FittedTable] on the real desktop Compose target.
 *
 * The defect under test: the table's viewport is laid out in the table's own direction, but the scrollbar used
 * to sit outside that provider and take the screen's ambient direction. A Hebrew table in an English window (and
 * an English table in a Hebrew window) therefore had a viewport reading one way and a thumb starting at the
 * opposite physical end. A phone cannot show this — the Android scrollbar draws nothing — so this suite runs on
 * the desktop target and reads pixels: where the thumb's ink actually is along the bottom edge, never a
 * direction enum and never the layout direction the code asked for.
 */
@OptIn(ExperimentalTestApi::class)
class TableScrollbarDirectionTest {

    // ---- the mismatched cases: the table's direction differs from the window's

    @Test
    fun hebrewTableInAnLtrAmbientStartsItsScrollbarAtTheRight() = runComposeUiTest {
        val thumb = thumbInBand(HEBREW_TABLE, ambient = LayoutDirection.Ltr)
        assertTrue(
            thumb.fraction > 0.55f,
            "the Hebrew table's scrollbar ink sits at ${thumb.detail()}, expected the thumb at the right end " +
                "because the table reads right to left in an English window",
        )
    }

    @Test
    fun englishTableInAnRtlAmbientStartsItsScrollbarAtTheLeft() = runComposeUiTest {
        val thumb = thumbInBand(ENGLISH_TABLE, ambient = LayoutDirection.Rtl)
        assertTrue(
            thumb.fraction < 0.45f,
            "the English table's scrollbar ink sits at ${thumb.detail()}, expected the thumb at the left end " +
                "because the table reads left to right in a Hebrew window",
        )
    }

    // ---- control: when the directions agree, the same measurement says so. It has to pass before and after
    // the fix, or the two cases above prove nothing about the measurement.

    @Test
    fun hebrewTableInAnRtlAmbientStartsItsScrollbarAtTheRight() = runComposeUiTest {
        val thumb = thumbInBand(HEBREW_TABLE, ambient = LayoutDirection.Rtl)
        assertTrue(
            thumb.fraction > 0.55f,
            "the Hebrew table's scrollbar ink sits at ${thumb.detail()}, expected the thumb at the right end",
        )
    }

    private class Thumb(val fraction: Float, val ink: Double, val width: Int, val height: Int) {
        fun detail() = "${"%.3f".format(fraction)} of the table's width (ink=$ink, ${width}x$height px)"
    }

    /**
     * Renders [markdown] and returns where the scrollbar's ink is along the table's bottom edge, as a fraction
     * of the table's width: 0 is the physical left end, 1 the physical right end.
     */
    private fun ComposeUiTest.thumbInBand(markdown: String, ambient: LayoutDirection): Thumb {
        setContent {
            hermesTheme(AccentPalette.Blue)(ColorScheme.Light) {
                CompositionLocalProvider(LocalLayoutDirection provides ambient) {
                    Box(Modifier.width(COLUMN).background(Color.White).testTag(REPLY)) {
                        MarkdownText(markdown)
                    }
                }
            }
        }
        waitForIdle()
        val image = onNodeWithTag(REPLY).captureToImage().toPixelMap()
        val band = image.bottomBand(BAND_PX)
        // The table's own bottom edge and background fill the band; the thumb is darker than both. The band is
        // mostly background, so its median is the background the thumb is measured against.
        val background = band.lumas.sorted()[band.lumas.size / 2]
        var ink = 0.0
        var weightedX = 0.0
        for (y in band.top until image.height) {
            for (x in 0 until image.width) {
                val pixel = image[x, y]
                if (pixel.alpha < 0.5f) continue
                val weight = background - luma(pixel)
                if (weight <= INK_THRESHOLD) continue
                ink += weight
                weightedX += weight * x
            }
        }
        val width = image.width
        val fraction = if (ink > 0.0) (weightedX / ink / (width - 1)).toFloat() else 0.5f
        val thumb = Thumb(fraction, ink, width, image.height)
        println("[table-scrollbar] ambient=${ambient.name} ${thumb.detail()}")
        assertTrue(ink > 0.0, "no scrollbar ink at all: the table did not overflow within ${COLUMN.value}dp")
        return thumb
    }

    private class Band(val top: Int, val lumas: List<Float>)

    private fun PixelMap.bottomBand(height: Int): Band {
        val top = (this.height - height).coerceAtLeast(0)
        val lumas = ArrayList<Float>((this.height - top) * width)
        for (y in top until this.height) {
            for (x in 0 until width) lumas += luma(this[x, y])
        }
        return Band(top, lumas)
    }

    private fun luma(pixel: Color): Float = 0.299f * pixel.red + 0.587f * pixel.green + 0.114f * pixel.blue

    private companion object {
        /** Narrower than the tables below, so each one overflows and the bar has a thumb to draw. */
        val COLUMN = 320.dp
        const val REPLY = "table-reply"
        /** The bar is 6dp thick and drawn along the bottom edge; a little more than that is still all thumb. */
        const val BAND_PX = 8
        /** A luma step smaller than this is antialiasing between the table's own colours, not the thumb. */
        const val INK_THRESHOLD = 0.01f

        const val HEBREW_TABLE = "| מוצר | תיאור | כמות | מחיר | סטטוס | הערות |\n" +
            "| --- | --- | --- | --- | --- | --- |\n" +
            "| אביזרים | תיאורים | כמויות | מחירים | סטטוסים | הערותים |\n"

        const val ENGLISH_TABLE = "| configuration | description | quantity | amount | status | notes |\n" +
            "| --- | --- | --- | --- | --- | --- |\n" +
            "| implementations | descriptions | quantities | amounts | statuses | notifications |\n"
    }
}
