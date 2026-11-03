package dev.hermeskotlin.designsystem.components

import android.content.ComponentName
import android.graphics.Canvas
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import android.view.View
import android.view.ViewGroup
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.HermesTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Rendered geometry of ordinary Markdown paragraphs, on the production [MarkdownText], hosted by Robolectric
 * with real Compose text measurement and real pixels.
 *
 * The defect under test: an ordinary (and highlighted, and linked) paragraph renders its text box at its
 * intrinsic width, laid out at the ambient start of a wider reply column. A short Hebrew paragraph is
 * therefore narrow and sits at the left while its glyphs read right-to-left. Geometry is asserted from the
 * real [TextLayoutResult] line edges plus the rendered node's root bounds — never from a direction enum.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownRtlLayoutTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    // The library ships no manifest declaring an activity, so register one before createComposeRule
    // launches its host activity through Robolectric's package manager.
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    private fun show(
        markdown: String,
        ambient: LayoutDirection = LayoutDirection.Ltr,
        highlights: List<TextHighlight> = emptyList(),
    ) {
        composeRule.setContent {
            HermesTheme(ColorScheme.Light) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides ambient,
                    LocalTextHighlights provides highlights,
                ) {
                    Box(Modifier.width(WIDTH).background(Color.White)) {
                        MarkdownText(markdown)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    // ---- controls: nothing below means anything unless the harness shows English at the left and known
    // full-width RTL text at the right.

    @Test
    fun englishParagraphStartsAtTheLeftContentEdge() {
        show("I saved the shopping list for home.")
        val block = blockFor("I saved the shopping list")
        record("english-paragraph", block.detail())
        assertTrue(
            block.bounds.left + block.layout.getLineLeft(0) <= tolPx(),
            "English paragraph line starts at ${block.bounds.left + block.layout.getLineLeft(0)}px, expected the " +
                "left content edge (${block.detail()})",
        )
    }

    @Test
    fun fullWidthRtlReferenceTextStartsAtTheRightContentEdge() {
        composeRule.setContent {
            HermesTheme(ColorScheme.Light) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.width(WIDTH).background(Color.White)) {
                        BasicText(
                            "אבגד",
                            modifier = Modifier.fillMaxWidth(),
                            style = TextStyle(textDirection = TextDirection.Rtl),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val node = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult))
            .fetchSemanticsNodes().single()
        val layout = layoutOf(node) ?: fail("no text layout result for the RTL control")
        record("control-full-width-rtl", "bounds=${node.boundsInRoot.short()} width=${layout.size.width} " +
            "lines=${linesOf(layout)}")
        assertTrue(
            layout.size.width >= contentWidthPx() - tolPx(),
            "RTL control box is ${layout.size.width}px wide, expected ${contentWidthPx()}px",
        )
        assertTrue(
            layout.getLineRight(0) >= layout.size.width - tolPx(),
            "RTL control line ends at ${layout.getLineRight(0)}px, expected its box edge ${layout.size.width}px",
        )
    }

    // ---- the reported case: ordinary, highlighted and linked paragraphs

    @Test
    fun shortHebrewParagraphStartsAtTheRightContentEdge() =
        assertHebrewRightAnchored("פתחתי ושמרתי **רשימת קניות לבית:**", "פתחתי ושמרתי", "plain")

    @Test
    fun secondHebrewParagraphStartsAtTheRightContentEdge() =
        assertHebrewRightAnchored("הוספתי מכסים לסירים לרשימה של מוטי.", "הוספתי מכסים", "plain-second")

    @Test
    fun highlightedHebrewParagraphStartsAtTheRightContentEdge() {
        val markdown = "פתחתי ושמרתי רשימת קניות לבית:"
        show(markdown, highlights = listOf(TextHighlight(block = markdown, start = 0, end = 8)))
        val block = blockFor("פתחתי ושמרתי")
        record("highlighted", block.detail())
        assertRightAnchored(block, "highlighted paragraph")
    }

    @Test
    fun linkedHebrewParagraphStartsAtTheRightContentEdge() {
        val markdown = "[רשימת קניות](https://example.com/list) לבית"
        show(markdown)
        val block = blockFor("רשימת קניות")
        record("linked", block.detail())
        // The link label must still be the rendered text: the fix is layout only, never text rewriting.
        assertEquals("רשימת קניות לבית", block.text, "the linked paragraph's text changed")
        assertRightAnchored(block, "linked paragraph")
    }

    @Test
    fun wrappedHebrewParagraphKeepsEveryLineAtTheRightEdge() {
        show("פתחתי ושמרתי רשימת קניות ארוכה מאד לבית ולהוספת מכסים לסירים ולכל מה שמוטי ביקש לקנות היום")
        val block = blockFor("פתחתי ושמרתי רשימת קניות")
        record("wrapped", block.detail())
        assertTrue(block.layout.lineCount >= 2, "fixture should wrap, got ${block.layout.lineCount} line(s)")
        assertRightAnchored(block, "wrapped paragraph")
        // A wrapped paragraph reaches the reply width even before the fix, so this is a stability check: no
        // line may start or end short of the same right edge.
        for (line in 0 until block.layout.lineCount) {
            val lineRight = block.bounds.left + block.layout.getLineRight(line)
            assertTrue(
                lineRight >= contentWidthPx() - tolPx(),
                "wrapped line $line ends at ${lineRight}px, expected the reply's right edge ${contentWidthPx()}px",
            )
        }
    }

    // ---- direction is per paragraph, never the whole reply

    @Test
    fun englishParagraphAfterHebrewStaysLeftInTheSameReply() {
        show("פסקה בעברית עם מספיק מילים כדי שתיקרא כשפה ימנית.\n\nI typed this paragraph in English.")
        val hebrew = blockFor("פסקה בעברית")
        val english = blockFor("I typed this paragraph")
        record("same-reply-hebrew", hebrew.detail())
        record("same-reply-english", english.detail())
        assertRightAnchored(hebrew, "Hebrew paragraph of a mixed reply")
        assertTrue(
            english.bounds.left + english.layout.getLineLeft(0) <= tolPx(),
            "the English paragraph of a Hebrew-heavy reply starts at " +
                "${english.bounds.left + english.layout.getLineLeft(0)}px, expected the left content edge",
        )
    }

    @Test
    fun englishParagraphStaysLeftInAnRtlAmbientContext() {
        show("I typed this paragraph in English.", ambient = LayoutDirection.Rtl)
        val english = blockFor("I typed this paragraph")
        record("english-in-rtl-ambient", english.detail())
        assertTrue(
            english.bounds.left + english.layout.getLineLeft(0) <= tolPx(),
            "English paragraph in an RTL ambient context starts at " +
                "${english.bounds.left + english.layout.getLineLeft(0)}px, expected the left content edge",
        )
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private class Block(
        val bounds: Rect,
        val ink: Rect?,
        val layout: TextLayoutResult,
        val text: String,
    ) {
        fun detail() = "bounds=${bounds.short()} ink=${ink?.short()} box=${layout.size.width.toInt()} " +
            "lines=${linesOf(layout)}"
    }

    private fun assertHebrewRightAnchored(markdown: String, fragment: String, label: String) {
        show(markdown)
        val block = blockFor(fragment)
        record(label, block.detail())
        assertRightAnchored(block, label)
    }

    private fun assertRightAnchored(block: Block, what: String) {
        // The rendered right edge in root space: the text box may be narrower than the reply, so neither the
        // box width nor the paragraph style alone proves where the line actually ends up.
        val right = block.bounds.left + block.layout.getLineRight(0)
        assertTrue(
            right >= contentWidthPx() - tolPx(),
            "$what ends at ${right}px, expected the reply's right edge ${contentWidthPx()}px (${block.detail()})",
        )
    }

    private fun blockFor(fragment: String): Block {
        val node = composeRule.onAllNodes(hasText(fragment, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .maxByOrNull { it.boundsInRoot.width }
            ?: fail("no rendered paragraph contains \"$fragment\"")
        val layout = layoutOf(node) ?: fail("no text layout result for \"$fragment\"")
        val ink = inkIn(node.boundsInRoot, fragment.take(12).replace(' ', '-'))
        val text = if (node.config.contains(SemanticsProperties.Text)) {
            node.config[SemanticsProperties.Text].joinToString("") { it.text }
        } else {
            ""
        }
        return Block(node.boundsInRoot, ink, layout, text)
    }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        if (!node.config.contains(SemanticsActions.GetTextLayoutResult)) return null
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.firstOrNull()
    }

    /**
     * The compose root drawn through a software canvas. captureToImage() copies the window, so it picks up the
     * activity's title bar and loses the root offset; drawing the compose view keeps root coordinates exact.
     */
    private fun renderRoot(): Bitmap? {
        val view = composeRule.activity.window.decorView.findComposeView() ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        composeRule.waitForIdle()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /** The darkest pixels drawn inside [bounds]: corroborating evidence next to the text-layout edges. */
    private fun inkIn(bounds: Rect, label: String): Rect? {
        val bitmap = renderRoot() ?: return null
        save(bitmap, label)
        val x0 = bounds.left.toInt().coerceIn(0, bitmap.width - 1)
        val x1 = bounds.right.toInt().coerceIn(0, bitmap.width - 1)
        val y0 = bounds.top.toInt().coerceIn(0, bitmap.height - 1)
        val y1 = bounds.bottom.toInt().coerceIn(0, bitmap.height - 1)
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in y0..y1) {
            for (x in x0..x1) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = android.graphics.Color.alpha(pixel)
                val luma = (0.299f * android.graphics.Color.red(pixel) +
                    0.587f * android.graphics.Color.green(pixel) +
                    0.114f * android.graphics.Color.blue(pixel)) / 255f
                if (alpha > 0x80 && luma < 0.55f) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < 0) return null
        return Rect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
    }

    // Geometry and pixels for debugging a failure, only when a run asks for them with -Drtl.geometry=<dir>.
    private val geometryDir = System.getProperty("rtl.geometry")?.let { File(it).apply { mkdirs() } }

    private fun save(bitmap: Bitmap, label: String) {
        val dir = geometryDir ?: return
        File(dir, "markdown-$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun record(label: String, value: String) {
        val dir = geometryDir ?: return
        File(dir, "markdown-$label.txt").writeText(value + "\n")
    }

    private fun contentWidthPx() = with(composeRule.density) { WIDTH.toPx() }

    private fun tolPx() = with(composeRule.density) { TOLERANCE.toPx() }

    private companion object {
        /** A reply column narrower than the phone, as in the screenshots, so a short block cannot fill it. */
        val WIDTH = 320.dp

        /** Line edges are exact; rasterised ink moves by a pixel between hosts. */
        val TOLERANCE = 4.dp
    }
}

/** A layout bound (or ink bound) as whole-pixel root coordinates, for the geometry record. */
private fun Rect.short() = "[${left.toInt()}..${right.toInt()}]x[${top.toInt()}..${bottom.toInt()}]"

/** Every line's left and right edge inside the measured text box, for the geometry record. */
private fun linesOf(layout: TextLayoutResult): String =
    (0 until layout.lineCount).joinToString("; ") { line ->
        "L$line[${layout.getLineLeft(line)}..${layout.getLineRight(line)}]"
    }

/** Registers the Compose host activity Robolectric would otherwise fail to resolve. */
private class RegisterHostActivity : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val app = RuntimeEnvironment.getApplication()
            Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                ComponentName(app.packageName, ComponentActivity::class.java.name),
            )
            base.evaluate()
        }
    }
}

/** The Compose root inside a view tree, without depending on Compose's internal class. */
private fun View.findComposeView(): View? {
    if (javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return this
    val group = this as? ViewGroup ?: return null
    for (index in 0 until group.childCount) {
        group.getChildAt(index).findComposeView()?.let { return it }
    }
    return null
}
