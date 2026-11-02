package dev.hermeskotlin.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpanPainter
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.SpanDrawInstructions

/** A link in a reply, and what opening and copying it do there. */
class PointedLink(val url: String, val open: () -> Unit, val copy: () -> Unit)

/**
 * The link the pointer is on in Markdown text, on a desktop, for a right-click menu to offer Open link and Copy
 * link; null when it's on none. A phone never hovers, so it stays null there.
 */
object LinkUnderPointer {
    var link: PointedLink? = null
        internal set
}

/**
 * Keeps [LinkUnderPointer] up to date for one block of Markdown text. A hovered link is laid out again with its
 * hovered style, which carries [mark] as its background: the block's layout then says which link it is.
 */
@Composable
internal fun rememberLinkHoverSpans(mark: Color, open: (String) -> Unit, copy: (String) -> Unit): ExtendedSpans {
    val painter = remember(mark) { LinkHoverPainter(mark) }
    painter.open = open
    painter.copy = copy
    // A block that goes while the pointer is on its link doesn't leave the link behind.
    DisposableEffect(painter) { onDispose { painter.release() } }
    return remember(painter) { ExtendedSpans(painter) }
}

private class LinkHoverPainter(private val mark: Color) : ExtendedSpanPainter() {
    var open: (String) -> Unit = {}
    var copy: (String) -> Unit = {}
    private var mine: PointedLink? = null

    override fun decorate(span: SpanStyle, start: Int, end: Int, text: AnnotatedString, builder: AnnotatedString.Builder) = span

    override fun decorate(linkAnnotation: LinkAnnotation, start: Int, end: Int, text: AnnotatedString, builder: AnnotatedString.Builder) =
        linkAnnotation

    override fun drawInstructionsFor(layoutResult: TextLayoutResult, color: Color?): SpanDrawInstructions {
        val text = layoutResult.layoutInput.text
        val hovered = text.spanStyles.firstOrNull { it.item.background == mark }
        val url = hovered?.let { h -> text.getLinkAnnotations(h.start, h.end).firstNotNullOfOrNull { (it.item as? LinkAnnotation.Url)?.url } }
        if (url != null) {
            if (mine?.url != url) mine = PointedLink(url, open = { open(url) }, copy = { copy(url) })
            LinkUnderPointer.link = mine
        } else {
            release()
        }
        return NOTHING
    }

    fun release() {
        if (mine != null && LinkUnderPointer.link === mine) LinkUnderPointer.link = null
        mine = null
    }
}

private val NOTHING = SpanDrawInstructions {}
