package dev.hermeskotlin.designsystem.components

import androidx.compose.ui.graphics.Color
import dev.snipme.highlights.model.SyntaxLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeHighlightingTest {

    @Test
    fun fenceNamesMapToLanguages() {
        assertEquals(SyntaxLanguage.KOTLIN, CodeHighlighter.language("kotlin"))
        assertEquals(SyntaxLanguage.KOTLIN, CodeHighlighter.language("kt"))
        assertEquals(SyntaxLanguage.PYTHON, CodeHighlighter.language("Python"))
        assertEquals(SyntaxLanguage.SHELL, CodeHighlighter.language("bash"))
        assertEquals(SyntaxLanguage.TYPESCRIPT, CodeHighlighter.language("tsx"))
        assertEquals(SyntaxLanguage.DEFAULT, CodeHighlighter.language("json"))
        // Plain text and unnamed blocks aren't colored: prose would light up as keywords.
        assertNull(CodeHighlighter.language(null))
        assertNull(CodeHighlighter.language("text"))
        assertNull(CodeHighlighter.language("brainfork"))
    }

    @Test
    fun kotlinGetsKeywordsStringsAndComments() {
        val code = "// greet\nval name = \"Ada\"\nfun main() = println(42)"
        val spans = CodeHighlighter.spans(code, SyntaxLanguage.KOTLIN)
        fun tokenOf(word: String) = spans.firstOrNull { code.substring(it.start, it.end).contains(word) }?.token
        assertEquals(CodeToken.Comment, tokenOf("greet"))
        assertEquals(CodeToken.Keyword, tokenOf("val"))
        assertEquals(CodeToken.Text, tokenOf("Ada"))
        assertEquals(CodeToken.Literal, tokenOf("42"))
        assertTrue(spans.all { it.start in 0 until it.end && it.end <= code.length })
    }

    @Test
    fun theCacheWorksCodeOutOnce() {
        var runs = 0
        val cache = HighlightCache(maxEntries = 2) { code, _ -> runs++; listOf(CodeSpan(0, code.length, CodeToken.Keyword)) }
        cache.spans("val a", SyntaxLanguage.KOTLIN)
        cache.spans("val a", SyntaxLanguage.KOTLIN)
        assertEquals(1, runs)
        // The same text in another language is another entry.
        cache.spans("val a", SyntaxLanguage.SWIFT)
        assertEquals(2, runs)
    }

    @Test
    fun theCacheDropsTheLeastRecentlyUsed() {
        val cache = HighlightCache(maxEntries = 2) { _, _ -> emptyList() }
        cache.spans("a", SyntaxLanguage.KOTLIN)
        cache.spans("b", SyntaxLanguage.KOTLIN)
        cache.cached("a", SyntaxLanguage.KOTLIN) // a is now the most recent
        cache.spans("c", SyntaxLanguage.KOTLIN)
        assertEquals(2, cache.size)
        assertNull(cache.cached("b", SyntaxLanguage.KOTLIN))
        assertTrue(cache.cached("a", SyntaxLanguage.KOTLIN) != null)
    }

    @Test
    fun spansFromALongerTextAreDroppedAndPunctuationKeepsTheTextColor() {
        val colors = SyntaxColors.Light
        val text = highlightedCode(
            "val x",
            listOf(CodeSpan(0, 3, CodeToken.Keyword), CodeSpan(3, 4, CodeToken.Punctuation), CodeSpan(4, 9, CodeToken.Text)),
            colors,
        )
        assertEquals(listOf(Color(0xFFCF222E)), text.spanStyles.map { it.item.color })
        assertEquals(0 to 3, text.spanStyles.single().let { it.start to it.end })
    }
}
