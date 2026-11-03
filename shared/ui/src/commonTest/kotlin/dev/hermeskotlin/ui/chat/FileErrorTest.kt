package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.media.FileText
import dev.hermeskotlin.core.media.MediaApi
import dev.hermeskotlin.core.network.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileErrorTest {

    @Test
    fun theGatewaysRefusalsReadAsSentences() {
        assertEquals(FileProblem.Missing, fileError(ApiResult.Failed(404, "File not found")))
        assertEquals(FileProblem.Private, fileError(ApiResult.Failed(403, "Access to sensitive files is not allowed")))
        assertEquals(FileProblem.Refused("Path outside media roots"), fileError(ApiResult.Failed(403, "Path outside media roots")))
        assertEquals(FileProblem.Said("Path points to a directory."), fileError(ApiResult.Failed(400, "Path points to a directory")))
        assertEquals(FileProblem.SignInAgain, fileError(ApiResult.SessionExpired))
        assertEquals(FileProblem.Unreachable, fileError(ApiResult.Unavailable("timeout")))
    }

    @Test
    fun tooLargeForTheWholeFileIsntCalledAPreviewLimit() {
        assertEquals(FileProblem.Said(MediaApi.TOO_LARGE), fileError(ApiResult.Failed(413, MediaApi.TOO_LARGE), whole = true))
        assertEquals(FileProblem.TooLargeToPreview, fileError(ApiResult.Failed(413, "Too large")))
        assertEquals(FileProblem.TooLargeToHandOver, fileError(ApiResult.Failed(413, "Too large"), whole = true))
    }

    @Test
    fun copyIsCutToWhatTheClipboardTakes() {
        val short = clipFor(FileText("hello", 5, truncated = false, binary = false))
        assertEquals("hello", short.text)
        assertNull(short.note)
        assertEquals(ClipNote.PartShown, clipFor(FileText("hello", 900_000, truncated = true, binary = false)).note)

        val long = clipFor(FileText("abcdef", 6, truncated = false, binary = false), max = 4)
        assertEquals("abcd", long.text)
        assertEquals(ClipNote.FirstChars(4), long.note)
        // A cut never leaves half of an emoji's surrogate pair.
        assertEquals("abc", clipFor(FileText("abc😀d", 6, truncated = false, binary = false), max = 4).text)
        assertEquals(ClipNote.FirstChars(100_000), clipFor(FileText("x".repeat(100_001), 100_001, truncated = false, binary = false)).note)
    }

    @Test
    fun sizesReadShort() {
        assertEquals("900 B", formatBytes(900))
        assertEquals("1.4 KB", formatBytes(1_450))
        assertEquals("512 KB", formatBytes(524_288))
        assertEquals("3.2 MB", formatBytes(3_400_000))
    }
}
