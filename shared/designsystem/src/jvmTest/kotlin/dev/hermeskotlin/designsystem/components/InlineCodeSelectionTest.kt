package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.PureBlack
import dev.hermeskotlin.designsystem.hermesTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A selection dragged across an inline code span, rendered with the real [MarkdownText] on the desktop target.
 *
 * The defect under test: the span's own background is opaque, and a text selection is drawn behind the text, so
 * the selected code read as if it were not selected at all. The test reads pixels: it renders the sentence
 * unselected, finds the pixels that are the span's plain background, selects across the line, and checks that
 * those same pixels now carry the selection color.
 */
@OptIn(ExperimentalTestApi::class)
class InlineCodeSelectionTest {

    @Test
    fun aSelectionShowsOverAnInlineCodeSpan() = runComposeUiTest {
        val (before, after) = renderUnselectedThenSelected("Some words before `inline code here` and some words after it.")
        val chip = chipBackgroundPixels(before)
        assertTrue(chip.size > 200, "found only ${chip.size} plain code-background pixels; the span was not drawn as expected")
        val tinted = chip.count { (x, y) -> isTintedTowardsSelection(after[x, y]) }
        val share = tinted.toFloat() / chip.size
        println("[inline-code-selection] ${chip.size} chip background pixels, $tinted tinted by the selection (${"%.2f".format(share)})")
        assertTrue(
            share > 0.9f,
            "only ${"%.0f".format(share * 100)}% of the code span's background shows the selection; the highlight is hidden behind it",
        )
    }

    /** On pure black, where the span's wash is at its faintest: it is still drawn, and a selection shows over it. */
    @Test
    fun aSelectionShowsOverAnInlineCodeSpanOnBlack() = runComposeUiTest {
        val (before, after) = renderUnselectedThenSelected(
            "Some words before `inline code here` and some words after it.", PureBlack, Color.Black,
        )
        val chip = chipBackgroundPixels(before, Color.Black)
        assertTrue(chip.size > 200, "found only ${chip.size} code-background pixels; the span doesn't stand out from a black page")
        // The light theme's wash is too faint here (under 0.05 of white); a dark ground takes the stronger one.
        val level = chip.first().let { (x, y) -> before[x, y].red }
        assertTrue(level > 0.06f, "the code span is only $level above black, fainter than it was before the wash")
        val tinted = chip.count { (x, y) -> isTintedTowardsSelection(after[x, y]) }
        assertTrue(tinted.toFloat() / chip.size > 0.9f, "only $tinted of ${chip.size} of the code span's background pixels show the selection")
    }

    /** The control: the plain text beside the span shows the selection, so the measurement means something. */
    @Test
    fun aSelectionShowsOverPlainText() = runComposeUiTest {
        val (before, after) = renderUnselectedThenSelected("Some words before `inline code here` and some words after it.")
        val plain = plainBackgroundPixels(before)
        assertTrue(plain.size > 200, "found only ${plain.size} plain background pixels")
        val tinted = plain.count { (x, y) -> isTintedTowardsSelection(after[x, y]) }
        println("[inline-code-selection] control: ${plain.size} plain background pixels, $tinted tinted")
        assertTrue(tinted.toFloat() / plain.size > 0.9f, "the selection did not draw over plain text, so the harness is not selecting")
    }

    private fun ComposeUiTest.renderUnselectedThenSelected(
        markdown: String,
        scheme: ColorScheme = ColorScheme.Light,
        page: Color = Color.White,
    ): Pair<PixelMap, PixelMap> {
        setContent {
            hermesTheme(AccentPalette.Blue)(scheme) {
                CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(Color.Red, SELECTION)) {
                    SelectionContainer {
                        Box(Modifier.width(COLUMN).background(page).testTag(REPLY)) {
                            MarkdownText(markdown)
                        }
                    }
                }
            }
        }
        waitForIdle()
        val before = onNodeWithTag(REPLY).captureToImage().toPixelMap()
        onNodeWithTag(REPLY).performMouseInput {
            moveTo(Offset(1f, LINE_Y))
            press()
            moveTo(Offset(width - 1f, LINE_Y))
            release()
        }
        waitForIdle()
        val after = onNodeWithTag(REPLY).captureToImage().toPixelMap()
        return before to after
    }

    /**
     * Pixels, on the first text line, that are the inline code span's background in the unselected image: the
     * most common color on the line that is neither the [page] nor text ink.
     */
    private fun chipBackgroundPixels(image: PixelMap, page: Color = Color.White): List<Pair<Int, Int>> {
        val band = (LINE_Y.toInt() - 4)..(LINE_Y.toInt() + 4)
        val counts = HashMap<Int, Int>()
        for (y in band) for (x in 0 until image.width) {
            val c = image[x, y]
            // Ink is far from the page in brightness; the span's background is near it.
            if (!c.isCloseTo(page) && kotlin.math.abs(c.luminance() - page.luminance()) < 0.15f) counts.merge(c.toArgb(), 1, Int::plus)
        }
        val chip = Color(counts.maxByOrNull { it.value }?.key ?: return emptyList())
        return pixelsMatching(image) { it.isCloseTo(chip) }
    }

    /** Pixels, on the first text line and left of its last glyph, that are the plain page background. */
    private fun plainBackgroundPixels(image: PixelMap): List<Pair<Int, Int>> {
        // A selection ends with the text; the empty space after the last word is never selected.
        val lastInk = (0 until image.width).lastOrNull { x -> (LINE_Y.toInt() - 4..LINE_Y.toInt() + 4).any { y -> image[x, y].luminance() < 0.5f } }
            ?: return emptyList()
        return pixelsMatching(image) { it.isCloseTo(Color.White) }.filter { (x, _) -> x < lastInk }
    }

    private fun pixelsMatching(image: PixelMap, match: (Color) -> Boolean): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (y in (LINE_Y.toInt() - 4)..(LINE_Y.toInt() + 4)) for (x in 0 until image.width) {
            if (match(image[x, y])) out += x to y
        }
        return out
    }

    private fun Color.isCloseTo(other: Color) =
        kotlin.math.abs(red - other.red) < 0.01f && kotlin.math.abs(green - other.green) < 0.01f && kotlin.math.abs(blue - other.blue) < 0.01f

    /** A background pixel the selection has drawn over loses green and blue against red: it was near-neutral before. */
    private fun isTintedTowardsSelection(pixel: Color) = pixel.red - pixel.green > 0.08f

    private companion object {
        val COLUMN = 480.dp
        const val REPLY = "reply"
        /** The middle of the first line of text. */
        const val LINE_Y = 10f
        val SELECTION = Color.Red.copy(alpha = 0.4f)
    }
}
