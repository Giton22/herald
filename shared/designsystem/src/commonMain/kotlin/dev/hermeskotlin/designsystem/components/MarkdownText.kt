package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.composables.icons.lucide.SquareTerminal
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.rememberColoredIndication
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.compose.elements.MarkdownListItems
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.textMuted
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownText as LibraryMarkdownText
import dev.hermeskotlin.designsystem.accent
import org.intellij.markdown.ast.ASTNode
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.heading
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.well
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.DefaultMarkdownInlineContent
import dev.hermeskotlin.designsystem.background
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.composables.icons.lucide.ImageOff
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer

/** Code blocks wrap long lines instead of scrolling sideways (the "Wrap code lines" setting). */
val LocalCodeWrap = compositionLocalOf { false }

/** The Markdown being drawn is still streaming in. */
private val LocalStreaming = compositionLocalOf { false }

/** [textStyle] laid over the reply's [body] style: what it leaves unset (color, line height, font) stays the body's. */
internal fun compactStyle(body: TextStyle, textStyle: TextStyle?): TextStyle? = textStyle?.let(body::merge)

/** A compact text style keeps every block small, while code keeps its own font and links their accent. */
internal fun DefaultMarkdownTypography.withTextStyle(style: TextStyle?): DefaultMarkdownTypography {
    if (style == null) return this
    val heading = style.copy(fontWeight = FontWeight.SemiBold)
    val codeStyle = code.copy(fontSize = style.fontSize, lineHeight = style.lineHeight, color = style.color)
    return copy(
        h1 = heading, h2 = heading, h3 = heading, h4 = heading, h5 = heading, h6 = heading,
        text = style, paragraph = style, ordered = style, bullet = style, list = style, table = style,
        // A quote keeps its own muted color so it still stands apart from the text around it.
        quote = style.copy(color = quote.color), code = codeStyle,
        inlineCode = inlineCode.copy(fontSize = style.fontSize, lineHeight = style.lineHeight, color = style.color),
        alertTitle = heading,
    )
}

/**
 * GitHub-flavoured Markdown in the app's type and colors. Fenced code gets a language label, syntax
 * colors, horizontal scrolling (or wrapping, per [LocalCodeWrap]) and a copy button. TeX math between
 * `$…$`, `\(…\)`, `$$…$$` or `\[…\]` is typeset (see [MathMarkup]). [streaming] parses off the main thread
 * and keeps the previous render while text grows; settled text parses synchronously so list items don't
 * change height after they appear. [textStyle] keeps blocks compact, for folded reasoning summaries;
 * headings use weight rather than size and code keeps its monospace font.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, streaming: Boolean = false, textStyle: TextStyle? = null) {
    val prepared = remember(text) { MathMarkup.prepare(text) }
    val replyStyle = Theme[typography][body].copy(color = Theme[colors][textColor])
    val compact = compactStyle(replyStyle, textStyle)
    val bodyStyle = compact ?: replyStyle
    val codeStyle = Theme[typography][code].copy(color = Theme[colors][textColor])
    val headingStyle = Theme[typography][heading].copy(color = Theme[colors][textColor])
    val linkColor = Theme[colors][accentText]
    val markdownColors = DefaultMarkdownColors(
        text = bodyStyle.color,
        codeBackground = Theme[colors][well],
        inlineCodeBackground = Theme[colors][surface2],
        dividerColor = Theme[colors][stroke],
        tableBackground = Theme[colors][surface],
    )
    val markdownTypography = DefaultMarkdownTypography(
        h1 = Theme[typography][title].copy(color = Theme[colors][textColor]),
        h2 = headingStyle.copy(fontSize = 19.sp, lineHeight = 26.sp),
        h3 = headingStyle,
        h4 = headingStyle,
        h5 = replyStyle.copy(fontWeight = FontWeight.SemiBold),
        h6 = replyStyle.copy(fontWeight = FontWeight.SemiBold),
        text = replyStyle,
        code = codeStyle,
        inlineCode = codeStyle,
        quote = replyStyle.copy(color = Theme[colors][textTertiary]),
        paragraph = replyStyle,
        ordered = replyStyle,
        bullet = replyStyle,
        list = replyStyle,
        textLink = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
        table = replyStyle,
    ).withTextStyle(compact)
    val components = remember {
        markdownComponents(
            codeFence = { MarkdownCodeFence(it.content, it.node, block = { code, language, style -> CodeBlock(code, language, style) }) },
            codeBlock = { MarkdownCodeBlock(it.content, it.node, block = { code, language, style -> CodeBlock(code, language, style) }) },
            paragraph = { HighlightedParagraph(it.content, it.node) },
            unorderedList = { DotList(it.content, it.node, it.listDepth) },
            table = { FittedTable(it.content, it.node, it.typography.table) },
        )
    }
    val density = LocalDensity.current
    val math = MathStyle(
        textSizePx = with(density) { bodyStyle.fontSize.toPx() },
        argb = bodyStyle.color.toArgb(),
        fallback = markdownTypography.code.toSpanStyle(),
    )
    val annotate: AnnotatedString.Builder.(String, ASTNode) -> Boolean = remember(math) { { content, child -> appendFormula(content, child, math) } }
    val annotator = markdownAnnotator(annotate = annotate)
    val inlineContent = remember(prepared, math, density) { DefaultMarkdownInlineContent(inlineFormulas(prepared, math, density)) }
    val parentUris = LocalUriHandler.current
    val fileLinks = LocalFileLinks.current
    val webOnly = remember(parentUris, fileLinks) { WebLinksOnly(parentUris, fileLinks) }
    CompositionLocalProvider(LocalUriHandler provides webOnly, LocalStreaming provides streaming, LocalMathStyle provides math) {
        Markdown(
            content = prepared,
            colors = markdownColors,
            typography = markdownTypography,
            modifier = modifier.fillMaxWidth(),
            padding = markdownPadding(block = 4.dp, list = 2.dp, listItemTop = 2.dp, listItemBottom = 2.dp),
            dimens = markdownDimens(codeBackgroundCornerSize = Theme[radii][radiusMedium], tableCornerSize = Theme[radii][radiusMedium]),
            annotator = annotator,
            inlineContent = inlineContent,
            imageTransformer = PictureTransformer,
            components = components,
            retainState = true,
            immediate = !streaming,
        )
    }
}

/** How formulas are typeset: the body text's size and color, and the code style for source shown instead. */
@Immutable
private data class MathStyle(val textSizePx: Float, val argb: Int, val fallback: SpanStyle) {
    fun sizeFor(display: Boolean) = if (display) textSizePx * 1.15f else textSizePx
}

private val LocalMathStyle = compositionLocalOf<MathStyle?> { null }

/** A formula link (see [MathMarkup]) goes into the text as its picture, or as its source when it won't typeset. */
private fun AnnotatedString.Builder.appendFormula(content: String, child: ASTNode, math: MathStyle): Boolean {
    if (child.type != MarkdownElementTypes.IMAGE) return false
    val link = child.linkDestination(content) ?: return false
    val formula = MathMarkup.formula(link) ?: return false
    if (MathPictures.get(formula.latex, math.sizeFor(formula.display), math.argb, formula.display) != null) {
        appendInlineContent(link, formula.latex)
    } else {
        withStyle(math.fallback) { append(formula.source()) }
    }
    return true
}

private fun MathFormula.source() = if (display) "$$$latex$$" else "$$latex$"

/** The pictures of the formulas [prepared] puts in its text, each sized to sit in the line. */
private fun inlineFormulas(prepared: String, math: MathStyle, density: Density): Map<String, InlineTextContent> {
    if ("herald-math:" !in prepared) return emptyMap()
    return FORMULA_LINK.findAll(prepared).mapNotNull { match ->
        val link = match.groupValues[1]
        val formula = MathMarkup.formula(link) ?: return@mapNotNull null
        val picture = MathPictures.get(formula.latex, math.sizeFor(formula.display), math.argb, formula.display) ?: return@mapNotNull null
        val placeholder = with(density) {
            Placeholder(picture.width.toSp(), picture.height.toSp(), PlaceholderVerticalAlign.TextCenter)
        }
        link to InlineTextContent(placeholder) {
            Image(picture, contentDescription = formula.latex, modifier = Modifier.fillMaxSize())
        }
    }.toMap()
}

private val FORMULA_LINK = Regex("""!\[math]\((herald-math:[di]:[A-Za-z0-9_-]*)\)""")

private fun ASTNode.linkDestination(content: String): String? {
    if (type == MarkdownElementTypes.LINK_DESTINATION) return getTextInNode(content).toString()
    for (child in children) child.linkDestination(content)?.let { return it }
    return null
}

/** A paragraph that is a display formula and nothing else, as written on its own lines. */
private fun ASTNode.soleDisplayFormula(content: String): MathFormula? {
    val parts = children.filterNot { it.type == MarkdownTokenTypes.WHITE_SPACE || it.type == MarkdownTokenTypes.EOL }
    val image = parts.singleOrNull()?.takeIf { it.type == MarkdownElementTypes.IMAGE } ?: return null
    return image.linkDestination(content)?.let(MathMarkup::formula)?.takeIf { it.display }
}

/** A display formula centered on its own, scrolling sideways when it's wider than the reply. */
@Composable
private fun DisplayFormula(formula: MathFormula, math: MathStyle) {
    val picture = MathPictures.get(formula.latex, math.sizeFor(true), math.argb, display = true)
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            if (picture != null) {
                Image(picture, contentDescription = formula.latex)
            } else {
                BasicText(formula.source(), style = LocalMarkdownTypography.current.code, softWrap = false)
            }
        }
    }
}

/**
 * Where a tapped link that isn't a web or mail link goes, a file path for instance; the handler ignores
 * what it can't open. Null (the default) leaves such links inert.
 */
val LocalFileLinks = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** A picture a Markdown image links to, as the screen's loader has it. */
sealed interface MarkdownPicture {
    data object Loading : MarkdownPicture

    /** [onOpen] shows it full screen. */
    class Ready(val bitmap: ImageBitmap, val onOpen: (() -> Unit)? = null) : MarkdownPicture

    data object Failed : MarkdownPicture
}

/**
 * Loads the picture a Markdown image (`![chart](chart.png)`) links to, or returns null to leave that image
 * out. Null (the default) shows no linked pictures: a reply doesn't fetch what its text points at.
 */
val LocalMarkdownPictures = staticCompositionLocalOf<(@Composable (String) -> MarkdownPicture?)?> { null }

/** Draws images through [LocalMarkdownPictures]: nothing while one loads, a broken-picture icon when it fails. */
private object PictureTransformer : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? {
        val load = LocalMarkdownPictures.current ?: return null
        return when (val picture = load(link)) {
            null, MarkdownPicture.Loading -> null
            is MarkdownPicture.Ready -> ImageData(
                BitmapPainter(picture.bitmap),
                modifier = picture.onOpen?.let { Modifier.clickable(onClickLabel = "Open the picture", onClick = it) } ?: Modifier,
                contentDescription = null,
            )
            MarkdownPicture.Failed -> ImageData(
                rememberVectorPainter(Lucide.ImageOff),
                contentDescription = "Picture couldn't load",
                colorFilter = ColorFilter.tint(Theme[colors][textTertiary]),
            )
        }
    }
}

/**
 * Opens only web and mail links. The text may come from a page the agent read, so a link to an
 * `intent:`, `file:` or app scheme must not reach another app on a tap; [files] may still show it in
 * the app (a path on the gateway, opened in a preview).
 */
private class WebLinksOnly(private val parent: UriHandler, private val files: ((String) -> Unit)?) : UriHandler {
    override fun openUri(uri: String) {
        val scheme = uri.substringBefore(':', "").lowercase()
        if (scheme in setOf("http", "https", "mailto")) runCatching { parent.openUri(uri) } else files?.invoke(uri)
    }
}

/** The library's paragraph, with the [LocalTextHighlights] that fall in it marked; a lone display formula is centered. */
@Composable
private fun HighlightedParagraph(content: String, node: ASTNode) {
    val math = LocalMathStyle.current
    val display = remember(content, node) { node.soleDisplayFormula(content) }
    if (math != null && display != null) {
        DisplayFormula(display, math)
        return
    }
    val highlights = LocalTextHighlights.current
    if (highlights.isEmpty()) {
        MarkdownParagraph(content, node)
        return
    }
    val style = LocalMarkdownTypography.current.paragraph
    val settings = annotatorSettings()
    val color = highlightColor()
    val text = remember(content, node, style, settings, highlights, color) {
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            buildMarkdownAnnotatedString(content, node, settings)
            pop()
        }.withHighlights(highlights, color)
    }
    LibraryMarkdownText(text, node, style = style)
}

/** A bullet list whose markers are small accent dots, centered on the first line; nested lists get muted ones. */
@Composable
private fun DotList(content: String, node: ASTNode, depth: Int) {
    val dot = Theme[colors][if (depth == 0) accentText else textMuted]
    // Centers the dot on the first line of the item's text. Only the dot is pushed down: a task item's
    // checkbox takes the same marker slot and sits on the line as it is.
    // Only an sp line height converts to dp; any other falls back to the body's.
    val lineHeight = LocalMarkdownTypography.current.list.lineHeight.takeIf { it.isSp } ?: Theme[typography][body].lineHeight
    val top = with(LocalDensity.current) { ((lineHeight.toDp() - BULLET_DOT) / 2) }
    MarkdownListItems(content, node, depth, markerModifier = { Modifier.padding(start = 2.dp, end = 10.dp) }) { _, _, _ ->
        Box(Modifier.padding(top = top).size(BULLET_DOT).background(dot, CircleShape))
    }
}

private val BULLET_DOT = 5.dp

/** The highlighter under text a comment is about: the accent, a little stronger than its soft tint so it stands out. */
@Composable
fun highlightColor(): Color = Theme[colors][accent].copy(alpha = 0.24f)

@Composable
private fun CodeBlock(code: String, language: String?, style: TextStyle) {
    val highlights = LocalTextHighlights.current
    val color = highlightColor()
    val wrap = LocalCodeWrap.current
    val shown = code.trimEnd('\n')
    val syntax = rememberSyntaxSpans(shown, language)
    val palette = if (Theme[colors][background].luminance() < 0.5f) SyntaxColors.Dark else SyntaxColors.Light
    val text = remember(shown, syntax, palette, highlights, color) {
        highlightedCode(shown, syntax, palette).withHighlights(highlights, color)
    }
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(Theme[colors][well])
            .border(1.dp, Theme[colors][stroke], shape),
    ) {
        // A header strip: what the code is, and a Copy pill.
        Row(
            Modifier.fillMaxWidth().background(Theme[colors][surface2]).padding(start = 12.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(Lucide.SquareTerminal, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(13.dp))
            Text(
                language?.takeIf { it.isNotBlank() } ?: "code",
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
                modifier = Modifier.weight(1f),
            )
            CopyPill(code)
        }
        val scroll = if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())
        Box(Modifier.fillMaxWidth().then(scroll).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
            BasicText(text, style = style, softWrap = wrap)
        }
    }
}

/** Highlighted code, shared by every block on screen and kept as long as it's among the recent ones. */
private val highlightCache = HighlightCache()

/** Settled code up to this long is colored at once; longer code, or code still streaming, off the main thread. */
private const val SYNC_HIGHLIGHT_LIMIT = 4_000

/** How long a growing block must stop changing before it's colored again. */
private const val STREAMING_PAUSE_MS = 120L

private class LastSpans(var spans: List<CodeSpan> = emptyList())

/**
 * The syntax spans of [code], from the cache when it has them. A block still streaming keeps the spans of
 * its previous text (they still fit the part already there) until the new text is worked out.
 */
@Composable
private fun rememberSyntaxSpans(code: String, languageName: String?): List<CodeSpan> {
    val language = remember(languageName) { CodeHighlighter.language(languageName) } ?: return emptyList()
    val streaming = LocalStreaming.current
    val last = remember(language) { LastSpans() }
    var computed by remember(language) { mutableStateOf<Pair<String, List<CodeSpan>>?>(null) }
    val ready = highlightCache.cached(code, language)
        ?: computed?.takeIf { it.first == code }?.second
        ?: if (!streaming && code.length <= SYNC_HIGHLIGHT_LIMIT) runCatching { highlightCache.spans(code, language) }.getOrNull() else null
    if (ready != null) {
        last.spans = ready
        return ready
    }
    LaunchedEffect(code, language) {
        if (streaming) delay(STREAMING_PAUSE_MS)
        val spans = withContext(Dispatchers.Default) { runCatching { CodeHighlighter.spans(code, language) }.getOrDefault(emptyList()) }
        highlightCache.put(code, language, spans)
        computed = code to spans
    }
    return last.spans
}

/** Whether [text] was just copied, and the copy; the flag clears itself after a moment. */
@Composable
private fun rememberCopy(text: String): Pair<Boolean, () -> Unit> {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    return copied to {
        scope.launch {
            clipboard.setClipEntry(plainTextClipEntry(text))
            copied = true
        }
    }
}

/** A code block's "Copy" pill; it says "Copied" for a moment. The whole header height takes the tap. */
@Composable
private fun CopyPill(text: String) {
    val (copied, copy) = rememberCopy(text)
    val tint = Theme[colors][textTertiary]
    UnstyledButton(
        onClick = copy,
        modifier = Modifier.height(40.dp).clip(RoundedCornerShape(Theme[radii][radiusSmall])),
        indication = rememberColoredIndication(tint),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Row(
            Modifier.background(Theme[colors][surface3], CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnstyledIcon(if (copied) Lucide.Check else Lucide.Copy, contentDescription = null, tint = tint, modifier = Modifier.size(11.dp))
            Text(if (copied) "Copied" else "Copy", style = Theme[typography][caption], color = tint)
        }
    }
}

/**
 * Copies [text]; the icon turns into a check for a moment. [label] is what a screen reader hears, so a list of
 * them can say whose text each one copies.
 */
@Composable
fun CopyButton(text: String, modifier: Modifier = Modifier, label: String = "Copy") {
    val (copied, copy) = rememberCopy(text)
    IconButton(
        icon = if (copied) Lucide.Check else Lucide.Copy,
        contentDescription = if (copied) "Copied" else label,
        onClick = copy,
        // Smaller than MinTouchTarget so it sits close to the text; it stands alone, so Compose still stretches its taps to 48dp.
        modifier = modifier.size(32.dp),
        tint = Theme[colors][textTertiary],
        iconSize = 16.dp,
    )
}
