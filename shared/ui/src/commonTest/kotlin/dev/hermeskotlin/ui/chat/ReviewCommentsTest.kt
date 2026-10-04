package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.designsystem.components.TextHighlight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewCommentsTest {

    private val reply = CommentSource("r1", "your last reply", markdown = PLAN)

    /** The blocks a reply like [PLAN] draws: list numbers on their own, each item, the code. */
    private val blocks = listOf(
        "1.", "Fetch changes so the worker can retry later.",
        "2.", "Apply changes, with a bounded retry for failures.",
        "3.", "Log each retry. Then stop.",
        "fun sync() {\n    val retryLimit = 3\n}",
    )

    private fun select(block: Int, word: String, from: Int = 0): SelectionAnchor {
        val start = blocks[block].indexOf(word, from)
        return SelectionAnchor(blocks, block, start, block, start + word.length)
    }

    @Test
    fun namesTheListItemAndQuotesTheSentenceAroundTheRightOccurrence() {
        val comment = newComment(1, reply, select(3, "retry"))
        assertEquals("retry", comment.quote)
        assertEquals("item 2", comment.where)
        assertEquals("Apply changes, with a bounded «retry» for failures.", comment.context)
        assertEquals(listOf(TextHighlight(blocks[3], 30, 35)), comment.highlights)
    }

    @Test
    fun takesOnlyTheSentenceTheSelectionIsIn() {
        assertEquals("Log each «retry».", newComment(1, reply, select(5, "retry")).context)
    }

    @Test
    fun namesTheLineOfACodeBlockAndQuotesThatLine() {
        val comment = newComment(1, reply, select(6, "retryLimit"))
        assertEquals("line 2 of the kotlin block", comment.where)
        assertEquals("    val «retryLimit» = 3", comment.context)
    }

    @Test
    fun countsLinesInOutput() {
        val output = "total 3\ndrwx a\n-rw- b"
        val anchor = SelectionAnchor(listOf(output), 0, output.indexOf("drwx"), 0, output.length)
        val comment = newComment(1, CommentSource("c1", "the output of `ls`", code = true), anchor)
        assertEquals("lines 2–3", comment.where)
    }

    @Test
    fun oneLineOutputNeedsNoLineNumber() {
        val output = "Sun Oct  4 16:20:56 UTC 2026\n"
        val anchor = SelectionAnchor(listOf(output), 0, output.indexOf("UTC"), 0, output.indexOf("UTC") + 3)
        assertNull(newComment(1, CommentSource("t1", "the output of your terminal tool call", code = true), anchor).where)
    }

    @Test
    fun leavesOutContextThatWouldOnlyRepeatTheSelection() {
        val whole = SelectionAnchor.whole("Say hi.")
        assertNull(newComment(1, CommentSource("u1", "my message"), whole).context)
    }

    @Test
    fun aSelectionAcrossBlocksQuotesEachOnItsOwnLine() {
        val anchor = SelectionAnchor(blocks, 1, blocks[1].indexOf("retry"), 3, 5)
        assertEquals("retry later.\n2.\nApply", anchor.text)
        assertEquals(3, anchor.highlights.size)
    }

    @Test
    fun formatsCommentsForTheAgentAndReadsThemBack() {
        val first = newComment(1, reply, select(3, "retry"), note = "Use exponential backoff.")
        val second = newComment(2, reply, select(6, "retryLimit"))
        val text = formatReview(listOf(first, second), "  Thanks!  ")
        assertEquals(
            """
            <comments>
            <comment on="your last reply" where="item 2">
            > Apply changes, with a bounded «retry» for failures.
            Use exponential backoff.
            </comment>
            <comment on="your last reply" where="line 2 of the kotlin block">
            >     val «retryLimit» = 3
            </comment>
            </comments>

            Thanks!
            """.trimIndent(),
            text,
        )
        val review = parseReview(text)!!
        assertEquals("", review.before)
        assertEquals("Thanks!", review.after)
        assertEquals(
            listOf(
                SentComment("your last reply", "item 2", "Apply changes, with a bounded «retry» for failures.", "Use exponential backoff."),
                SentComment("your last reply", "line 2 of the kotlin block", "    val «retryLimit» = 3", ""),
            ),
            review.comments,
        )
    }

    @Test
    fun keepsQuotesInLabelsIntact() {
        val source = CommentSource("r0", "your earlier reply that starts \"Sure & done\"")
        val text = formatReview(listOf(newComment(1, source, SelectionAnchor.whole("done"))), "")
        assertEquals("your earlier reply that starts \"Sure & done\"", parseReview(text)!!.comments.single().on)
    }

    @Test
    fun ordinaryTextHasNoReview() {
        assertNull(parseReview("Just a prompt about <comments> in HTML."))
    }

    @Test
    fun openingWordsDropMarkdownAndStopAfterEightWords() {
        assertEquals("Here's the plan for the sync worker you…", openingWords("## **Here's** the plan for the sync worker you asked about"))
    }

    private companion object {
        val PLAN = """
            1. Fetch changes so the worker can retry later.
            2. Apply changes, with a bounded retry for failures.
            3. Log each retry. Then stop.

            ```kotlin
            fun sync() {
                val retryLimit = 3
            }
            ```
        """.trimIndent()
    }
}
