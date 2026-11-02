package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.selection.DisableSelection
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.typography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A file's text, line by line with numbers in a gutter: colored as [language] when the highlighter knows
 * it, scrolled both ways (or [wrap]ped to the width), and opened at [focusLine] with that line marked.
 * Lines are laid out lazily, so the gateway's whole 512 KB preview scrolls smoothly.
 */
@Composable
fun CodeView(
    text: String,
    language: String?,
    modifier: Modifier = Modifier,
    focusLine: Int? = null,
    wrap: Boolean = false,
) {
    val lines = remember(text) { splitLines(text) }
    val palette = if (Theme[colors][background].luminance() < 0.5f) SyntaxColors.Dark else SyntaxColors.Light
    // Colored off the main thread; until then (or for a file too big to color) the lines show plain.
    // The state outlives a change of [text]: each coloring keeps the text it was made from, and one made from
    // other text is never cut into this text's lines (a Source / Formatted switch would cut out of range).
    // Cut into lines there too, each line keeping only its own spans: a line scrolled in then costs nothing, where
    // cutting it out of the whole file's coloring would walk every span of the file each time.
    val highlight by produceState<Pair<String, List<AnnotatedString>>?>(null, text, language, palette) {
        val syntax = CodeHighlighter.language(language)
        value = if (syntax == null || text.length > MAX_HIGHLIGHT_CHARS) {
            null
        } else {
            withContext(Dispatchers.Default) {
                runCatching { text to coloredLines(highlightedCode(text, CodeHighlighter.spans(text, syntax), palette), splitLines(text)) }.getOrNull()
            }
        }
    }
    val colored = highlight?.takeIf { it.first == text }?.second
    val style = Theme[typography][code].copy(color = Theme[colors][textColor])
    val gutterStyle = style.copy(color = Theme[colors][textMuted], textAlign = TextAlign.End)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charWidth = remember(style, density) { with(density) { measurer.measure("0000000000", style).size.width.toDp() / 10 } }
    // The digits plus room on either side: a number never runs into the frame's edge or the code.
    val gutterWidth = charWidth * lines.size.toString().length.coerceAtLeast(2) * 1.15f + 22.dp
    val longest = remember(lines) { unwrappedColumns(lines.maxOfOrNull { it.last - it.first + 1 } ?: 0) }
    val state = rememberLazyListState()
    LaunchedEffect(focusLine, lines.size) {
        val target = focusLine?.minus(1)?.takeIf { it in lines.indices } ?: return@LaunchedEffect
        state.scrollToItem((target - FOCUS_CONTEXT).coerceAtLeast(0))
    }
    val focusColor = Theme[colors][accentSoft]
    val sideways = rememberScrollState()
    val horizontal = if (wrap) Modifier else Modifier.horizontalScroll(sideways)
    SelectionContainer(modifier) {
      Box {
        Box(horizontal) {
            // Scrolled sideways the list needs a fixed width: the longest line's, so rows don't jump as they come in.
            val width = if (wrap) Modifier.fillMaxWidth() else Modifier.width(gutterWidth + charWidth * (longest + 2) + 28.dp)
            // Room under the last line for the sideways bar, so it doesn't sit over it.
            val underBar = if (!wrap && LocalDesktopLayout.current) 12.dp else 0.dp
            LazyColumn(width, state = state, contentPadding = PaddingValues(bottom = underBar)) {
                itemsIndexed(lines, key = { index, _ -> index }) { index, range ->
                    val number = index + 1
                    // A line past the width the list can take (minified code, a log line) wraps within it.
                    val overlong = range.last - range.first + 1 > MAX_LINE_COLUMNS
                    val lineText = colored?.getOrNull(index) ?: AnnotatedString(text.substring(range.first, range.last + 1))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (number == focusLine) Modifier.background(focusColor) else Modifier)
                            .padding(end = 12.dp),
                    ) {
                        // Left out of a selection, so copied lines come without their numbers.
                        DisableSelection {
                            BasicText(number.toString(), style = gutterStyle, softWrap = false, modifier = Modifier.width(gutterWidth).padding(start = 8.dp, end = 4.dp))
                        }
                        BasicText(lineText, style = style, softWrap = wrap || overlong, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
        // The desktop's bars along the file's right and bottom edges, to drag it by; as big as the view, not more.
        Box(Modifier.matchParentSize()) {
            VerticalScrollbar(state, Modifier.align(Alignment.TopEnd).fillMaxHeight())
            if (!wrap) HorizontalScrollbar(sideways, Modifier.align(Alignment.BottomStart).fillMaxWidth())
        }
      }
    }
}

/** [highlight]'s coloring when it was made from [text] itself, else null (the lines show plain until it's redone). */
internal fun coloringOf(highlight: Pair<String, AnnotatedString>?, text: String): AnnotatedString? =
    highlight?.takeIf { it.first == text }?.second

/**
 * [colored] cut at [lines], each line with the spans that fall in it: one pass over the spans, each going only to
 * the lines it covers (found by a binary search on their starts).
 */
internal fun coloredLines(colored: AnnotatedString, lines: List<IntRange>): List<AnnotatedString> {
    val starts = IntArray(lines.size) { lines[it].first }
    val perLine = arrayOfNulls<MutableList<AnnotatedString.Range<SpanStyle>>>(lines.size)
    for (span in colored.spanStyles) {
        var at = starts.binarySearch(span.start).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
        while (at < lines.size && lines[at].first < span.end) {
            val line = lines[at]
            val start = maxOf(span.start, line.first)
            val end = minOf(span.end, line.last + 1)
            if (start < end) {
                (perLine[at] ?: mutableListOf<AnnotatedString.Range<SpanStyle>>().also { perLine[at] = it }) +=
                    AnnotatedString.Range(span.item, start - line.first, end - line.first)
            }
            at++
        }
    }
    return lines.mapIndexed { i, range -> AnnotatedString(colored.text.substring(range.first, range.last + 1), perLine[i].orEmpty()) }
}

/** Each line's range in [text], its break left out; a trailing break makes no empty last line. */
internal fun splitLines(text: String): List<IntRange> {
    val ranges = ArrayList<IntRange>()
    var start = 0
    while (start <= text.length) {
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        if (end == text.length && start == text.length && ranges.isNotEmpty()) break
        val visibleEnd = if (end > start && text[end - 1] == '\r') end - 1 else end
        ranges += start until visibleEnd
        start = end + 1
    }
    return ranges
}

/**
 * How many characters wide the unwrapped list is laid out: the longest line's, up to [MAX_LINE_COLUMNS].
 * Unbounded, one long line (minified JSON) would ask for a width Compose's constraints can't hold.
 */
internal fun unwrappedColumns(longest: Int): Int = longest.coerceIn(0, MAX_LINE_COLUMNS)

/** About 15,000 dp of monospace: far past any screen, well inside what a layout can measure. */
internal const val MAX_LINE_COLUMNS = 2_000

/** Lines shown above the one a link points at, so it doesn't sit at the very top. */
private const val FOCUS_CONTEXT = 4

/** The highlighter is a regex pass; past this a file shows plain rather than keep the phone busy. */
private const val MAX_HIGHLIGHT_CHARS = 200_000
