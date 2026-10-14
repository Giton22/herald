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
        assertEquals("It isn't on the gateway, or it has moved since the reply was written.", fileError(ApiResult.Failed(404, "File not found")))
        assertEquals("The gateway keeps files like this one private.", fileError(ApiResult.Failed(403, "Access to sensitive files is not allowed")))
        assertEquals("The gateway won't share it: Path outside media roots.", fileError(ApiResult.Failed(403, "Path outside media roots")))
        assertEquals("Path points to a directory.", fileError(ApiResult.Failed(400, "Path points to a directory")))
        assertEquals("Sign in again to open files.", fileError(ApiResult.SessionExpired))
        assertEquals("Couldn't reach the gateway.", fileError(ApiResult.Unavailable("timeout")))
    }

    @Test
    fun tooLargeForTheWholeFileIsntCalledAPreviewLimit() {
        assertEquals(MediaApi.TOO_LARGE, fileError(ApiResult.Failed(413, MediaApi.TOO_LARGE), whole = true))
        assertEquals("It's too large for the gateway to preview. Save still gets it.", fileError(ApiResult.Failed(413, "Too large")))
        assertEquals("It's too large for the gateway to hand over.", fileError(ApiResult.Failed(413, "Too large"), whole = true))
    }

    @Test
    fun copyIsCutToWhatTheClipboardTakes() {
        val short = clipFor(FileText("hello", 5, truncated = false, binary = false))
        assertEquals("hello", short.text)
        assertNull(short.note)
        assertEquals("Copied the part shown. Save gets the whole file.", clipFor(FileText("hello", 900_000, truncated = true, binary = false)).note)

        val long = clipFor(FileText("abcdef", 6, truncated = false, binary = false), max = 4)
        assertEquals("abcd", long.text)
        assertEquals("Copied the first 4 characters. Save gets the whole file.", long.note)
        // A cut never leaves half of an emoji's surrogate pair.
        assertEquals("abc", clipFor(FileText("abc😀d", 6, truncated = false, binary = false), max = 4).text)
        assertEquals(
            "Copied the first 100,000 characters. Save gets the whole file.",
            clipFor(FileText("x".repeat(100_001), 100_001, truncated = false, binary = false)).note,
        )
    }

    @Test
    fun sizesReadShort() {
        assertEquals("900 B", formatBytes(900))
        assertEquals("1.4 KB", formatBytes(1_450))
        assertEquals("512 KB", formatBytes(524_288))
        assertEquals("3.2 MB", formatBytes(3_400_000))
    }
}
