package dev.hermeskotlin.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedContentTest {

    @Test
    fun sharedTextIsTheDraft() {
        assertEquals("hello", SharedContent(text = "  hello \n").draftText)
    }

    @Test
    fun aSharedPageKeepsItsTitleAboveTheLink() {
        val page = SharedContent(text = "https://example.com/post", subject = "A good post")
        assertEquals("A good post\nhttps://example.com/post", page.draftText)
    }

    @Test
    fun theSubjectIsDroppedWhenItAddsNothing() {
        assertEquals("A good post https://example.com", SharedContent(text = "A good post https://example.com", subject = "A good post").draftText)
        // Only a bare link gets one; an app's subject on plain text is usually its own name.
        assertEquals("some notes", SharedContent(text = "some notes", subject = "Notes app").draftText)
        assertEquals("Only a subject", SharedContent(subject = "Only a subject").draftText)
    }

    @Test
    fun runawayTextIsCut() {
        val long = "a".repeat(SharedContent.MAX_TEXT_CHARS + 50)
        assertEquals(SharedContent.MAX_TEXT_CHARS, SharedContent(text = long).draftText?.length)
    }

    @Test
    fun filesAreAttachedOnceAndUpToTheLimit() {
        val files = (1..12).map { "content://media/$it" }
        val shared = SharedContent(files = files + files.first())
        assertEquals(files.take(OutgoingAttachment.MAX_COUNT), shared.filesToAttach)
        assertEquals("Only the first 10 files were attached.", shared.leftOverNotice)
        assertNull(SharedContent(files = files.take(3)).leftOverNotice)
    }

    @Test
    fun nothingSharedIsEmpty() {
        assertTrue(SharedContent(text = "  ", subject = "").isEmpty)
        assertFalse(SharedContent(files = listOf("content://x")).isEmpty)
    }
}
