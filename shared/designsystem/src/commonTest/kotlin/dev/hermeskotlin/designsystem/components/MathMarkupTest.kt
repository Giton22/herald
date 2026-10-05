package dev.hermeskotlin.designsystem.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MathMarkupTest {

    /** The formulas [MathMarkup.prepare] put in [markdown], in order. */
    private fun formulas(markdown: String): List<MathFormula> =
        Regex("""\((herald-math:[^)]*)\)""").findAll(MathMarkup.prepare(markdown)).map { MathMarkup.formula(it.groupValues[1])!! }.toList()

    @Test
    fun pricesStayAsTheyAre() {
        for (text in listOf(
            "It costs $5 and $10 with tax.",
            "Between $5-$10 a month",
            "Pay $5 + $10 now",
            "Prices: $1,000 to $2,500.",
            "Only $3.50, or $4 after noon",
            // Escaped dollars stay dollars.
            "A \\$5 bill and a \\\$x\\$ sign",
        )) {
            assertEquals(text, MathMarkup.prepare(text), text)
        }
    }

    @Test
    fun inlineDollarMathIsFound() {
        assertEquals(listOf(MathFormula("x^2 + y^2 = r^2", display = false)), formulas("A circle: \$x^2 + y^2 = r^2\$."))
        assertEquals(listOf(MathFormula("x", false), MathFormula("\\alpha", false)), formulas("Let \$x\$ be \$\\alpha\$"))
        assertEquals(listOf(MathFormula("a+b", false)), formulas("Sum \$a+b\$ then"))
    }

    @Test
    fun aPriceNextToMathStaysAPrice() {
        val prepared = MathMarkup.prepare("It's $5, and \$E = mc^2\$ holds.")
        assertTrue(prepared.startsWith("It's $5, and ![math]("), prepared)
        assertEquals(listOf(MathFormula("E = mc^2", false)), formulas("It's $5, and \$E = mc^2\$ holds."))
    }

    @Test
    fun parenthesisAndBracketDelimiters() {
        assertEquals(listOf(MathFormula("\\frac{1}{2}", false)), formulas("Half is \\(\\frac{1}{2}\\)."))
        assertEquals(listOf(MathFormula("\\sum_{i=1}^n i", true)), formulas("\\[ \\sum_{i=1}^n i \\]"))
    }

    @Test
    fun displayMathOnItsOwnLinesBecomesItsOwnParagraph() {
        val prepared = MathMarkup.prepare("The area:\n$$\n\\pi r^2\n$$\nwhere r is the radius.")
        val link = prepared.substringAfter("The area:\n\n").substringBefore("\n")
        assertTrue(link.startsWith("![math](herald-math:d:"), prepared)
        assertTrue(prepared.endsWith(")\n\nwhere r is the radius."), prepared)
        assertEquals(listOf(MathFormula("\\pi r^2", true)), formulas("The area:\n$$\n\\pi r^2\n$$\nwhere r is the radius."))
    }

    @Test
    fun displayMathInAListItemKeepsItsIndent() {
        val prepared = MathMarkup.prepare("1. First\n   $$ a = b $$\n2. Second")
        assertTrue(prepared.startsWith("1. First\n   \n   ![math](herald-math:d:"), prepared)
        assertTrue(prepared.endsWith(")\n\n2. Second"), prepared)
    }

    @Test
    fun displayMathMidSentenceStaysInTheLine() {
        val prepared = MathMarkup.prepare("So $$" + "x$$ is it")
        assertTrue(prepared.startsWith("So ![math](herald-math:d:"), prepared)
        assertTrue(prepared.endsWith(") is it"), prepared)
    }

    @Test
    fun codeIsLeftAlone() {
        val text = "Use `\$x^2\$` in code, and:\n```bash\necho \$HOME \$PATH_x\n$$ not math $$\n```\nDone \$y_1\$"
        val prepared = MathMarkup.prepare(text)
        assertTrue(prepared.startsWith("Use `\$x^2\$` in code, and:\n```bash\necho \$HOME \$PATH_x\n$$ not math $$\n```\nDone "), prepared)
        assertEquals(listOf(MathFormula("y_1", false)), formulas(text))
    }

    @Test
    fun indentedCodeIsLeftAlone() {
        val text = "Shell:\n\n    echo \$HOME_DIR/\$USER\n\n    \$x_1\$\nDone \$y_1\$"
        val prepared = MathMarkup.prepare(text)
        assertTrue(prepared.startsWith("Shell:\n\n    echo \$HOME_DIR/\$USER\n\n    \$x_1\$\nDone "), prepared)
        assertEquals(listOf(MathFormula("y_1", false)), formulas(text))
        // Under a list item the indent is the item's, not code.
        assertEquals(listOf(MathFormula("x_1", false)), formulas("- Item\n\n    Then \$x_1\$"))
    }

    @Test
    fun aFenceClosesOnlyOnABareRunAtLeastAsLong() {
        val nested = "````markdown\n```kotlin\n\$a_b\$\n```\n````\nAfter \$c_d\$"
        assertEquals(listOf(MathFormula("c_d", false)), formulas(nested))
        assertEquals(listOf(MathFormula("c_d", false)), formulas("```\n```kotlin\n\$a_b\$\n```\nAfter \$c_d\$"))
    }

    @Test
    fun aStrayBacktickDoesNotHideTheNextParagraph() {
        assertEquals(
            listOf(MathFormula("x^2", false), MathFormula("y^2", false)),
            formulas("Press the ` key.\n\nThen \$x^2\$ and `code` and \$y^2\$."),
        )
    }

    @Test
    fun escapedBracketsAndParenthesesStayText() {
        for (text in listOf("See \\[1\\] and \\[2\\] for details", "Press \\[Enter\\]", "The regex \\(foo\\) matches")) {
            assertEquals(text, MathMarkup.prepare(text), text)
        }
        assertEquals(listOf(MathFormula("x", false)), formulas("Let \\(x\\) be"))
    }

    @Test
    fun anUnclosedFormulaStaysTextWhileItStreams() {
        assertEquals("The sum $$\\sum_i", MathMarkup.prepare("The sum $$\\sum_i"))
        assertEquals("Let \$x^", MathMarkup.prepare("Let \$x^"))
        // A fence still open runs to the end, so nothing in it is math yet.
        assertEquals("```\n\$x^2\$", MathMarkup.prepare("```\n\$x^2\$"))
    }

    @Test
    fun spacesInsideDollarsAreNotMath() {
        assertEquals("a $ x^2 $ b", MathMarkup.prepare("a $ x^2 $ b"))
    }

    @Test
    fun formulaLinksRoundTripAnySource() {
        val latex = "\\int_0^1 f(x)\\,dx = \\left[ F \\right]_0^1 \\; ü ∑"
        assertEquals(listOf(MathFormula(latex, true)), formulas("$$$latex$$"))
        assertNull(MathMarkup.formula("https://example.com/x.png"))
        assertNull(MathMarkup.formula("herald-math:x:abc"))
    }

    @Test
    fun looksLikeMathHeuristics() {
        assertTrue(MathMarkup.looksLikeMath("x"))
        assertTrue(MathMarkup.looksLikeMath("x_1"))
        assertTrue(MathMarkup.looksLikeMath("f = (a, b)"))
        assertTrue(MathMarkup.looksLikeMath("(1, 2)"))
        assertTrue(MathMarkup.looksLikeMath("a < b"))
        assertFalse(MathMarkup.looksLikeMath("5 and "))
        assertFalse(MathMarkup.looksLikeMath("1,000 to "))
        assertFalse(MathMarkup.looksLikeMath("hello world"))
        // Shell variables in prose: "$HOME_DIR/$USER".
        assertFalse(MathMarkup.looksLikeMath("HOME_DIR/"))
        assertEquals("Set \$HOME_DIR/\$USER/bin", MathMarkup.prepare("Set \$HOME_DIR/\$USER/bin"))
    }

    @Test
    fun textWithoutDollarsOrBackslashesIsReturnedAsIs() {
        val text = "Plain *markdown* here"
        assertTrue(MathMarkup.prepare(text) === text)
    }
}
