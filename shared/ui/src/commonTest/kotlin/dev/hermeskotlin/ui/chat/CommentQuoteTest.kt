package dev.hermeskotlin.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class CommentQuoteTest {

    @Test
    fun quotesIntoAnEmptyComposerAndLeavesABlankLineForTheComment() {
        assertEquals("> retry with backoff\n\n", withCommentQuote("", "retry with backoff"))
    }

    @Test
    fun quotesEveryLineAndKeepsBlankLinesInsideTheQuote() {
        assertEquals("> first\n>\n> second\n\n", withCommentQuote("", "first\n\nsecond"))
    }

    @Test
    fun trimsTheSelectionAndTheEndsOfItsLines() {
        assertEquals("> one\n> two\n\n", withCommentQuote("", "  one  \ntwo \n"))
    }

    @Test
    fun stacksUnderWhatIsAlreadyTyped() {
        val first = withCommentQuote("", "add a flag") + "Skip this one."
        assertEquals("> add a flag\n\nSkip this one.\n\n> fixed delay\n\n", withCommentQuote(first, "fixed delay"))
    }

    @Test
    fun dropsTrailingBlankLinesBeforeTheNextQuote() {
        assertEquals("Hi\n\n> x\n\n", withCommentQuote("Hi\n\n\n", "x"))
    }
}
