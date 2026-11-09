package dev.hermeskotlin.designsystem.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

class BidiTest {

    private val lri = Bidi.LRI
    private val pdi = Bidi.PDI

    @Test
    fun inlineCodeIsIsolatedInsideArabicText() {
        val input = "شغّل `hermes update` بعد النسخ الاحتياطي."
        val out = Bidi.isolateInlineCode(input)
        assertEquals("شغّل `${lri}hermes update$pdi` بعد النسخ الاحتياطي.", out)
    }

    @Test
    fun theArabicAroundAnIsolatedSpanIsUntouched() {
        val input = "المسار هو `~/config.yaml` وهو صحيح."
        val out = Bidi.isolateInlineCode(input)
        assertTrue(out.startsWith("المسار هو `"))
        assertTrue(out.endsWith("` وهو صحيح."))
    }

    @Test
    fun everySpanOnALineIsIsolated() {
        val input = "`one` و `two` و `three`"
        val out = Bidi.isolateInlineCode(input)
        assertEquals("`${lri}one$pdi` و `${lri}two$pdi` و `${lri}three$pdi`", out)
    }

    @Test
    fun fencedCodeBlocksAreLeftAlone() {
        val input = "قبل:\n```bash\nhermes sessions prune\n```\nوبعد `x`"
        val out = Bidi.isolateInlineCode(input)
        assertTrue(out.contains("```bash\nhermes sessions prune\n```"))
        assertTrue(out.endsWith("وبعد `${lri}x$pdi`"))
    }

    @Test
    fun indentedCodeLinesAreLeftAlone() {
        val input = "    hermes sessions prune\nسطر `y`"
        val out = Bidi.isolateInlineCode(input)
        assertTrue(out.startsWith("    hermes sessions prune\n"))
        assertTrue(out.endsWith("`${lri}y$pdi`"))
    }

    @Test
    fun arabicInsideBackticksIsNotForcedToTheLeft() {
        val input = "الامر `حذف` هنا"
        assertEquals(input, Bidi.isolateInlineCode(input))
    }

    @Test
    fun textWithoutBackticksIsReturnedIdentical() {
        val input = "لا يوجد كود هنا: 409 MB و v0.22.5."
        assertEquals(input, Bidi.isolateInlineCode(input))
    }

    @Test
    fun isolatingTwiceChangesNothing() {
        val once = Bidi.isolateInlineCode("قبل `git status` بعد")
        assertEquals(once, Bidi.isolateInlineCode(once))
    }

    @Test
    fun anUnclosedBacktickIsLeftAsWritten() {
        val input = "قبل `hermes وبعد"
        assertEquals(input, Bidi.isolateInlineCode(input))
    }

    @Test
    fun aDoubleBacktickSpanIsIsolatedAsOneRun() {
        val input = "هنا ``a ` b`` نهاية"
        assertTrue(Bidi.isolateInlineCode(input).contains("``${lri}a ` b$pdi``"))
    }

    @Test
    fun aPathAndAVersionNumberAreIsolated() {
        val out = Bidi.isolateInlineCode("الملف `/root/.hermes/config.yaml` والنسخة `v0.22.5`.")
        assertTrue(out.contains("`${lri}/root/.hermes/config.yaml$pdi`"))
        assertTrue(out.contains("`${lri}v0.22.5$pdi`"))
    }

    @Test
    fun arabicTextPrefersRightToLeft() {
        assertTrue(Bidi.prefersRightToLeft("شكراً على الشرح"))
        assertTrue(Bidi.prefersRightToLeft("409 MB مكتوبة صراحةً، فالتحديث لن يغيّرها"))
    }

    @Test
    fun latinTextDoesNot() {
        assertFalse(Bidi.prefersRightToLeft("Thanks for the explanation"))
        assertFalse(Bidi.prefersRightToLeft("auto_prune is false in both paths, and v0.22.5 is optional"))
        assertFalse(Bidi.prefersRightToLeft("12345 — "))
        assertFalse(Bidi.prefersRightToLeft(""))
    }

    @Test
    fun aMostlyArabicLineStaysRightToLeftAroundCodeAndLatin() {
        assertTrue(Bidi.prefersRightToLeft("`hermes update` بعد النسخ الاحتياطي وقبل أي تعديل آخر"))
    }

    @Test
    fun lineBreaksAndSpacingSurvive() {
        val input = "سطر اول `a`\n\nسطر ثاني `b`\n"
        val out = Bidi.isolateInlineCode(input)
        assertEquals("سطر اول `${lri}a$pdi`\n\nسطر ثاني `${lri}b$pdi`\n", out)
    }

    // MARK: left-to-right runs in Arabic prose (dates, amounts, identifiers, paths, URLs)

    @Test
    fun aDateIsKeptWholeAndItsTrailingColonStaysInTheProse() {
        val input = "أكتوبر ما زال في متناوله حتى 2026-11-20: لو دفع الـ25$ قبله"
        val out = Bidi.isolateLtrRuns(input)
        assertTrue(out.contains("${lri}2026-11-20$pdi:"))
        assertTrue(out.contains("${lri}25\$$pdi"))
    }

    @Test
    fun anEnglishPhraseStaysOneRun() {
        val input = "ثم Now the controller يعيد التشغيل"
        assertEquals("ثم ${lri}Now the controller$pdi يعيد التشغيل", Bidi.isolateLtrRuns(input))
    }

    @Test
    fun identifiersPathsAndVersionsAreIsolated() {
        val out = Bidi.isolateLtrRuns("استخدم www-data مع state.db على v0.22.5 داخل /root/.hermes")
        for (run in listOf("www-data", "state.db", "v0.22.5", "/root/.hermes")) {
            assertTrue(out.contains("${lri}$run$pdi"), "missing isolate around $run in: $out")
        }
    }

    @Test
    fun aBareUrlIsIsolatedAndItsSentencePeriodIsNot() {
        val out = Bidi.isolateLtrRuns("افتح https://example.com/docs. ثم أكمل.")
        assertTrue(out.contains("$lri[https://example.com/docs](https://example.com/docs)$pdi. ثم"), out)
        assertTrue(out.endsWith("أكمل."))
    }

    @Test
    fun aBareUrlLinksToItsAddressWithoutTheIsolates() {
        // An isolated autolink would run on to the next space and take the closing isolate into its address.
        val text = Bidi.isolateLtrRuns("افتح https://example.com/search?q=1&x=2 ثم www.example.com/a، وبعدها")
        val links = mutableListOf<String>()
        fun walk(node: ASTNode) {
            // The address the renderer opens; an autolink in the link's text only shows it.
            if (node.type == MarkdownElementTypes.LINK_DESTINATION) links += node.getTextInNode(text).toString()
            if (node.type == GFMTokenTypes.GFM_AUTOLINK) assertFalse(node.getTextInNode(text).any { it == lri || it == pdi }, text)
            node.children.forEach(::walk)
        }
        walk(MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text))
        assertEquals(listOf("https://example.com/search?q=1&x=2", "http://www.example.com/a"), links)
        assertEquals(text, Bidi.isolateLtrRuns(text))
    }

    @Test
    fun aUrlAfterAnEnglishPhraseIsARunOfItsOwn() {
        val out = Bidi.isolateLtrRuns("راجع the docs https://example.com/x هنا")
        assertTrue(out.contains("${lri}the docs$pdi $lri[https://example.com/x]"), out)
    }

    @Test
    fun aTaskBoxOrAComparisonDoesNotHideTheRunsAfterIt() {
        assertTrue(Bidi.isolateLtrRuns("- [ ] مهمة 2026-11-20 (مهم)").contains("${lri}2026-11-20$pdi"))
        val out = Bidi.isolateLtrRuns("إذا كان 3 < 5 و v2.1 > 2")
        assertTrue(out.contains("${lri}5$pdi") && out.contains("${lri}v2.1$pdi"), out)
    }

    @Test
    fun aLinkWhoseAddressHasBracketsIsLeftWhole() {
        val input = "راجع [هذا](https://example.com/wiki/Foo_(bar)) و 2026"
        assertEquals("راجع [هذا](https://example.com/wiki/Foo_(bar)) و ${lri}2026$pdi", Bidi.isolateLtrRuns(input))
    }

    @Test
    fun aNestedListItemIsProseNotIndentedCode() {
        val input = "- الأول\n    - شغّل `hermes update` الآن\n    1. بعد 2026-11-20"
        val out = Bidi.isolateLtrRuns(Bidi.isolateInlineCode(input))
        assertTrue(out.contains("`${lri}hermes update$pdi`"), out)
        assertTrue(out.contains("    1. بعد ${lri}2026-11-20$pdi"), out)
    }

    @Test
    fun indentedCodeAfterABlankLineIsStillCode() {
        val input = "قبل:\n\n    chown www-data `x`\nبعد"
        assertEquals(input, Bidi.isolateLtrRuns(Bidi.isolateInlineCode(input)))
    }

    @Test
    fun inlineCodeOnALeftToRightLineIsLeftAloneUnlessTheReplyIsRightToLeft() {
        val input = "Run `npm install` first"
        assertEquals(input, Bidi.isolateInlineCode(input))
        assertEquals("Run `${lri}npm install$pdi` first", Bidi.isolateInlineCode(input, rightToLeft = true))
    }

    @Test
    fun anArabicGreetingDoesNotTurnAnEnglishReplyRightToLeft() {
        assertFalse(Bidi.prefersRightToLeft("أهلاً وسهلاً بك، Here is the full explanation of how the gateway prunes old sessions."))
    }

    @Test
    fun aUrlOrAPathCountsAsOneWord() {
        assertTrue(Bidi.prefersRightToLeft("الموعد حتى 2026-11-20 افتح https://example.com/search?q=1 للتفاصيل"))
        assertTrue(Bidi.prefersRightToLeft("راجع الملف /root/.hermes/config.yaml الآن"))
    }

    @Test
    fun aParagraphWithoutWordsHasNoDirectionOfItsOwn() {
        assertEquals(null, Bidi.proseDirection("12:30 — 409"))
        assertEquals(null, Bidi.proseDirection("1. 2026\n2. 2027"))
        assertEquals(true, Bidi.proseDirection("409 MB مكتوبة صراحةً"))
        assertEquals(false, Bidi.proseDirection("أهلاً، here is the full explanation"))
    }

    @Test
    fun codeDoesNotOutvoteTheArabicAroundIt() {
        val reply = "هذا مثال قصير جدا:\n\n```kotlin\nfun main() { println(\"hello world from the gateway client\") }\n" +
            "val items = listOf(one, two, three, four)\n```\n\nبعد 2026-11-20 افتح الملف"
        assertTrue(Bidi.prefersRightToLeft(reply))
        assertTrue(Bidi.prefersRightToLeft("شغّل `hermes sessions prune --older-than 30d --dry-run` الآن"))
    }

    // MARK: markdown the passes must leave as it parses

    private fun isolatesInside(markdown: String, vararg types: IElementType): List<String> {
        val found = mutableListOf<String>()
        fun walk(node: ASTNode) {
            val text = node.getTextInNode(markdown)
            if (node.type in types && text.any { it == lri || it == pdi }) found += text.toString()
            node.children.forEach(::walk)
        }
        walk(MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown))
        return found
    }

    private fun prepared(markdown: String) = Bidi.isolateLtrRuns(Bidi.isolateInlineCode(markdown, rightToLeft = true))

    @Test
    fun indentedCodeAfterAHeadingGetsNoIsolates() {
        val out = prepared("## مثال على الأمر\n    chown www-data /srv\n\nثم شغل الخدمة")
        assertEquals(emptyList(), isolatesInside(out, MarkdownElementTypes.CODE_BLOCK))
    }

    @Test
    fun indentedCodeThatLooksLikeAListGetsNoIsolates() {
        val out = prepared("شرح الملف هنا:\n\n    - run 2026-11-20\n    - stop")
        assertEquals(emptyList(), isolatesInside(out, MarkdownElementTypes.CODE_BLOCK))
        // A list line inside a fence doesn't start a list that outlives it.
        val fenced = prepared("```\n- one\n```\n\n    chown www-data /srv")
        assertEquals(emptyList(), isolatesInside(fenced, MarkdownElementTypes.CODE_BLOCK))
    }

    @Test
    fun aLinkReferenceDefinitionKeepsItsAddress() {
        val input = "راجع [الدليل][1] الآن\n\n[1]: https://example.com/docs"
        val out = prepared(input)
        assertTrue(out.endsWith("\n[1]: https://example.com/docs"), out)
        assertTrue(out.startsWith("راجع [الدليل][1] الآن"), out)
        assertEquals(emptyList(), isolatesInside(out, MarkdownElementTypes.LINK_DEFINITION))
    }

    @Test
    fun aTickedTaskBoxStaysACheckbox() {
        val out = prepared("مهام اليوم:\n\n- [x] تم النشر 2026\n- [ ] لم يتم")
        assertTrue(out.contains("- [x] تم النشر ${lri}2026$pdi"), out)
        assertTrue(isolatesInside(out, GFMTokenTypes.CHECK_BOX).isEmpty())
    }

    @Test
    fun aUrlGluedToAWordKeepsTheIsolatesOutOfItsAddress() {
        val out = prepared("افتح الصفحة Docs:https://example.com/x ثم site:www.example.com أكمل")
        assertEquals(emptyList(), isolatesInside(out, MarkdownElementTypes.LINK_DESTINATION, GFMTokenTypes.GFM_AUTOLINK))
        assertTrue(out.contains("${lri}Docs$pdi:$lri[https://example.com/x](https://example.com/x)$pdi"), out)
    }

    @Test
    fun aLinkDestinationIsNotTouched() {
        val input = "راجع [الدليل](https://example.com/a_b) بعدين"
        assertEquals(input, Bidi.isolateLtrRuns(input))
    }

    @Test
    fun codeSpansAreLeftAsTheyAre() {
        val input = "قبل `${lri}hermes update$pdi` بعد"
        assertEquals(input, Bidi.isolateLtrRuns(input))
    }

    @Test
    fun fencedCodeIsLeftAsItIs() {
        val input = "```\nchown www-data /root/.hermes\n```\nثم ${lri}www-data$pdi"
        assertEquals(input, Bidi.isolateLtrRuns(input))
    }

    @Test
    fun aRunInsideArabicLettersEndsAtThem() {
        val out = Bidi.isolateLtrRuns("المجلدhome/data هنا")
        assertTrue(out.contains("${lri}home/data$pdi"))
        assertTrue(out.startsWith("المجلد"))
    }

    // MARK: what must stay untouched

    @Test
    fun anOrderedListMarkerStaysAtTheStartOfTheLine() {
        val out = Bidi.isolateLtrRuns("1. الخطوة الأولى\n2) ثم الثانية\n10. والأخيرة")
        assertEquals("1. الخطوة الأولى\n2) ثم الثانية\n10. والأخيرة", out)
    }

    @Test
    fun aNumberThatOnlyLooksLikeAMarkerIsStillIsolated() {
        val out = Bidi.isolateLtrRuns("التاريخ 1.5 ثم")
        assertTrue(out.contains("${lri}1.5$pdi"))
    }

    @Test
    fun aFormulaImageIsLeftExactlyAsItIs() {
        val input = "قبل ![math](herald-math:i:YWJj) بعد"
        assertEquals(input, Bidi.isolateLtrRuns(input))
    }

    @Test
    fun aLinkWhoseTextIsLatinIsLeftAsItIs() {
        val input = "راجع [the docs](https://example.com/x) بعدين"
        assertEquals(input, Bidi.isolateLtrRuns(input))
    }

    @Test
    fun isolatesAreStrippedForTextThatLeavesTheScreen() {
        val wrapped = "قبل ${lri}hermes update$pdi بعد"
        assertEquals("قبل hermes update بعد", stripBidiIsolates(wrapped))
        assertEquals("clean", stripBidiIsolates("clean"))
    }
}
