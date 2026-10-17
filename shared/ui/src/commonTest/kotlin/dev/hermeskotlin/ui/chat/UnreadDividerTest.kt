package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.TranscriptEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where the "New messages" line goes when a chat opens with messages since it was last read. */
class UnreadDividerTest {

    private fun prompt(key: String, at: Double?) = ChatMessage.User(key, text = key, timestamp = at)
    private fun reply(key: String, at: Double?, streaming: Boolean = false) =
        ChatMessage.Assistant(key, text = key, streaming = streaming, timestamp = at)
    private fun fromBot(key: String, at: Double?) =
        ChatMessage.Event(key, TranscriptEvent.FromBot(sender = "Scribe", handle = "scribe", body = key), timestamp = at)
    private fun failedTurn(key: String) = ChatMessage.Event(key, TranscriptEvent.FailedTurn("boom"))

    @Test
    fun theLineGoesAboveTheFirstMessageAfterTheLastLook() {
        val messages = listOf(prompt("p1", 100.0), reply("r1", 110.0), prompt("p2", 200.0), reply("r2", 210.0))

        assertEquals("p2", firstUnreadKey(messages, readUpTo = 150.0))
    }

    @Test
    fun nothingNewMeansNoLine() {
        val messages = listOf(prompt("p1", 100.0), reply("r1", 110.0))

        assertNull(firstUnreadKey(messages, readUpTo = 110.0))
    }

    @Test
    fun timesWithinTheRoundingSlackCountAsRead() {
        val messages = listOf(reply("r1", 110.5), reply("r2", 140.0))

        assertEquals("r2", firstUnreadKey(messages, readUpTo = 110.0))
    }

    @Test
    fun aReplyStillBeingWrittenIsNew() {
        val messages = listOf(prompt("p1", 100.0), reply("r1", at = null, streaming = true))

        assertEquals("r1", firstUnreadKey(messages, readUpTo = 105.0))
    }

    @Test
    fun olderPagesLoadWhileEverythingLoadedIsStillNew() {
        val allNew = listOf(prompt("p9", 900.0), reply("r9", 910.0))

        assertTrue(needsOlderForUnread(allNew, readUpTo = 100.0, olderMessages = true))
        assertFalse(needsOlderForUnread(allNew, readUpTo = 100.0, olderMessages = false))
    }

    @Test
    fun noOlderPageIsNeededOnceTheLastLookIsInView() {
        val messages = listOf(prompt("p1", 100.0), reply("r1", 110.0), reply("r2", 300.0))

        assertFalse(needsOlderForUnread(messages, readUpTo = 150.0, olderMessages = true))
    }

    @Test
    fun anotherBotsMessageThatGotNoAnswerStillGetsTheLine() {
        val messages = listOf(prompt("p1", 100.0), reply("r1", 110.0), fromBot("b1", 200.0))

        assertEquals("b1", firstUnreadKey(messages, readUpTo = 150.0))
    }

    @Test
    fun theLineGoesAboveAnotherBotsMessageNotBelowIt() {
        val messages = listOf(prompt("p1", 100.0), reply("r1", 110.0), fromBot("b1", 200.0), reply("r2", 210.0))

        assertEquals("b1", firstUnreadKey(messages, readUpTo = 150.0))
    }

    @Test
    fun theMachinerysOwnRowsAreNotWhereTheLineGoes() {
        val messages = listOf(prompt("p1", 100.0), failedTurn("f1"), reply("r2", 210.0))

        assertEquals("r2", firstUnreadKey(messages, readUpTo = 150.0))
    }

    @Test
    fun anOlderBotMessageCountsAsReadAndTellsPagingTheLastLookIsInView() {
        val messages = listOf(fromBot("b0", 100.0), reply("r1", 300.0))

        assertFalse(needsOlderForUnread(messages, readUpTo = 150.0, olderMessages = true))
        assertEquals("r1", firstUnreadKey(messages, readUpTo = 150.0))
    }

    @Test
    fun olderPagesAreFetchedUpToTheLimit() {
        val allNew = listOf(prompt("p9", 900.0), reply("r9", 910.0))

        assertEquals(UnreadLineStep.LoadOlder, unreadLineStep(allNew, readUpTo = 100.0, olderMessages = true, pagesLoaded = 0))
        assertEquals(UnreadLineStep.LoadOlder, unreadLineStep(allNew, readUpTo = 100.0, olderMessages = true, pagesLoaded = UNREAD_PAGE_LIMIT - 1))
    }

    @Test
    fun pastThePageLimitThereIsNoLineRatherThanAWrongOne() {
        // Still nothing older than the last look after every page worth fetching: the first new message is
        // further back, so a line above the oldest loaded one would have new messages above it.
        val allNew = listOf(prompt("p9", 900.0), reply("r9", 910.0))

        assertEquals(UnreadLineStep.None, unreadLineStep(allNew, readUpTo = 100.0, olderMessages = true, pagesLoaded = UNREAD_PAGE_LIMIT))
    }

    @Test
    fun theWholeHistoryLoadedAndAllNewPutsTheLineAtTheTop() {
        val allNew = listOf(prompt("p1", 900.0), reply("r1", 910.0))

        assertEquals(UnreadLineStep.Above("p1"), unreadLineStep(allNew, readUpTo = 100.0, olderMessages = false, pagesLoaded = 0))
    }

    @Test
    fun onceTheLastLookIsInViewTheLineIsPlaced() {
        val messages = listOf(prompt("p1", 100.0), reply("r1", 110.0), reply("r2", 300.0))

        assertEquals(UnreadLineStep.Above("r2"), unreadLineStep(messages, readUpTo = 150.0, olderMessages = true, pagesLoaded = 0))
    }

    @Test
    fun theLineStaysWhereItWasPlaced() {
        // Reading on doesn't move it, though another message is now the first new one by time.
        val messages = listOf(prompt("p1", 100.0), reply("r1", 110.0), reply("r2", 300.0))

        assertEquals("r2", unreadLineKey(messages, unreadFrom = "r2", readUpTo = 50.0))
    }

    @Test
    fun aSavedStepFoldedIntoTheLiveReplyPassesTheLineOn() {
        // The line went above the running turn's saved step, which then folded into the live reply repeating it.
        val messages = listOf(prompt("p1", 100.0), reply("r1", 110.0), prompt("p2", 140.0), reply("live-3", at = null, streaming = true))

        assertEquals("live-3", unreadLineKey(messages, unreadFrom = "row-4", readUpTo = 150.0))
    }

    @Test
    fun anAnswerLeftOldBySplittingOffTheSavedStepPassesTheLineOn() {
        // Merged with the running turn's step, the read answer r1 was dated by the step and got the line; split off
        // once the turn's start was known, it's the old answer again.
        val messages = listOf(prompt("p1", 100.0), reply("r1", 110.0), reply("live-3", at = null, streaming = true))

        assertEquals("live-3", unreadLineKey(messages, unreadFrom = "r1", readUpTo = 150.0))
    }
}
