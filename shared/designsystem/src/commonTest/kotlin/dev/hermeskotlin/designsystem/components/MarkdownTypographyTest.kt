package dev.hermeskotlin.designsystem.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MarkdownTypographyTest {
    private val normal = DefaultMarkdownTypography(
        h1 = TextStyle(fontSize = 22.sp),
        h2 = TextStyle(fontSize = 19.sp),
        h3 = TextStyle.Default, h4 = TextStyle.Default, h5 = TextStyle.Default, h6 = TextStyle.Default,
        text = TextStyle.Default,
        code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp),
        inlineCode = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp),
        quote = TextStyle(color = Color.DarkGray), paragraph = TextStyle.Default,
        ordered = TextStyle.Default, bullet = TextStyle.Default, list = TextStyle.Default,
        textLink = TextLinkStyles(SpanStyle(color = Color.Blue)),
        table = TextStyle.Default,
    )
    private val small = TextStyle(fontSize = 13.5.sp, lineHeight = 19.5.sp, color = Color.Gray)

    @Test
    fun compactTextAndHeadingsKeepTheReasoningSizeAndColor() {
        val compact = normal.withTextStyle(small)
        for (style in listOf(compact.text, compact.paragraph, compact.ordered, compact.bullet, compact.list, compact.table)) {
            assertEquals(small, style)
        }
        assertEquals(small.copy(color = Color.DarkGray), compact.quote)
        for (heading in listOf(compact.h1, compact.h2, compact.h3, compact.h4, compact.h5, compact.h6, compact.alertTitle)) {
            assertEquals(13.5.sp, heading.fontSize)
            assertEquals(19.5.sp, heading.lineHeight)
            assertEquals(Color.Gray, heading.color)
            assertEquals(FontWeight.SemiBold, heading.fontWeight)
        }
    }

    @Test
    fun compactCodeKeepsMonospaceAndLinksKeepTheirAccent() {
        val compact = normal.withTextStyle(small)
        for (code in listOf(compact.code, compact.inlineCode)) {
            assertEquals(FontFamily.Monospace, code.fontFamily)
            assertEquals(13.5.sp, code.fontSize)
            assertEquals(19.5.sp, code.lineHeight)
            assertEquals(Color.Gray, code.color)
        }
        assertEquals(normal.textLink, compact.textLink)
    }

    @Test
    fun aCompactStyleTakesWhatItLeavesUnsetFromTheBody() {
        val body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = Color.White)
        val compact = compactStyle(body, TextStyle(fontSize = 12.sp))!!
        assertEquals(12.sp, compact.fontSize)
        // The bullet dots are placed by the line height, and formulas are drawn in the text color.
        assertEquals(22.sp, compact.lineHeight)
        assertEquals(Color.White, compact.color)
        assertEquals(null, compactStyle(body, null))
    }

    @Test
    fun repliesWithoutAnOverrideKeepTheirTypography() {
        assertSame(normal, normal.withTextStyle(null))
    }
}
