package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import dev.hermeskotlin.designsystem.warning
import org.intellij.markdown.ast.ASTNode
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.heading
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * GitHub-flavoured Markdown in the app's type and colors. Fenced code gets a language label, horizontal
 * scrolling and a copy button. [streaming] parses off the main thread and keeps the previous render while
 * text grows; settled text parses synchronously so list items don't change height after they appear.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, streaming: Boolean = false) {
    val bodyStyle = Theme[typography][body].copy(color = Theme[colors][textColor])
    val codeStyle = Theme[typography][code].copy(color = Theme[colors][textColor])
    val headingStyle = Theme[typography][heading].copy(color = Theme[colors][textColor])
    val linkColor = Theme[colors][accent]
    val markdownColors = DefaultMarkdownColors(
        text = Theme[colors][textColor],
        codeBackground = Theme[colors][surface],
        inlineCodeBackground = Theme[colors][surface],
        dividerColor = Theme[colors][stroke],
        tableBackground = Theme[colors][surface],
    )
    val markdownTypography = DefaultMarkdownTypography(
        h1 = Theme[typography][title].copy(color = Theme[colors][textColor]),
        h2 = headingStyle.copy(fontSize = 19.sp, lineHeight = 26.sp),
        h3 = headingStyle,
        h4 = headingStyle,
        h5 = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
        h6 = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
        text = bodyStyle,
        code = codeStyle,
        inlineCode = codeStyle,
        quote = bodyStyle.copy(color = Theme[colors][textTertiary]),
        paragraph = bodyStyle,
        ordered = bodyStyle,
        bullet = bodyStyle,
        list = bodyStyle,
        textLink = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
        table = bodyStyle,
    )
    val components = remember {
        markdownComponents(
            codeFence = { MarkdownCodeFence(it.content, it.node, block = { code, language, style -> CodeBlock(code, language, style) }) },
            codeBlock = { MarkdownCodeBlock(it.content, it.node, block = { code, language, style -> CodeBlock(code, language, style) }) },
            paragraph = { HighlightedParagraph(it.content, it.node) },
        )
    }
    val parentUris = LocalUriHandler.current
    val webOnly = remember(parentUris) { WebLinksOnly(parentUris) }
    CompositionLocalProvider(LocalUriHandler provides webOnly) {
        Markdown(
            content = text,
            colors = markdownColors,
            typography = markdownTypography,
            modifier = modifier.fillMaxWidth(),
            padding = markdownPadding(block = 4.dp, list = 2.dp, listItemTop = 2.dp, listItemBottom = 2.dp),
            dimens = markdownDimens(codeBackgroundCornerSize = Theme[radii][radiusMedium], tableCornerSize = Theme[radii][radiusMedium]),
            components = components,
            retainState = true,
            immediate = !streaming,
        )
    }
}

/**
 * Opens only web and mail links. The text may come from a page the agent read, so a link to an
 * `intent:`, `file:` or app scheme must not reach another app on a tap.
 */
private class WebLinksOnly(private val parent: UriHandler) : UriHandler {
    override fun openUri(uri: String) {
        val scheme = uri.substringBefore(':', "").lowercase()
        if (scheme in setOf("http", "https", "mailto")) runCatching { parent.openUri(uri) }
    }
}

/** The library's paragraph, with the [LocalTextHighlights] that fall in it marked. */
@Composable
private fun HighlightedParagraph(content: String, node: ASTNode) {
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

/** The highlighter under text a comment is about. */
@Composable
fun highlightColor(): Color = Theme[colors][warning].copy(alpha = 0.3f)

@Composable
private fun CodeBlock(code: String, language: String?, style: TextStyle) {
    val highlights = LocalTextHighlights.current
    val color = highlightColor()
    val shown = code.trimEnd('\n')
    val text = remember(shown, highlights, color) { AnnotatedString(shown).withHighlights(highlights, color) }
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, Theme[colors][stroke], shape)
            .background(Theme[colors][surface], shape),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                language?.takeIf { it.isNotBlank() } ?: "code",
                style = Theme[typography][caption],
                color = Theme[colors][textTertiary],
                modifier = Modifier.weight(1f),
            )
            CopyButton(code)
        }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            BasicText(text, style = style, softWrap = false)
        }
    }
}

/** Copies [text]; the icon turns into a check for a moment. */
@Composable
fun CopyButton(text: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    IconButton(
        icon = if (copied) Lucide.Check else Lucide.Copy,
        contentDescription = if (copied) "Copied" else "Copy",
        onClick = {
            scope.launch {
                clipboard.setClipEntry(plainTextClipEntry(text))
                copied = true
            }
        },
        // Smaller than MinTouchTarget so it sits under the text; it stands alone, so Compose still stretches its taps to 48dp.
        modifier = modifier.size(32.dp),
        tint = Theme[colors][textTertiary],
        iconSize = 16.dp,
    )
}
