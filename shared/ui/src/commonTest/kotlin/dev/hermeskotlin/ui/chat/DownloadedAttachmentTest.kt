package dev.hermeskotlin.ui.chat

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadedAttachmentTest {
    @Test
    fun repeatedTapsAndRecreatedControllerFetchOnlyOnce() = runTest {
        val actions = FakeAttachmentActions()
        var fetches = 0
        suspend fun tap() = useDownloadedAttachment(actions, "owner/source", "document.txt", share = false) {
            fetches++
            FileDownload.Ready("document".encodeToByteArray())
        }

        assertTrue(tap().saved)
        assertTrue(tap().saved)
        assertTrue(tap().saved)
        assertEquals(1, fetches)
        assertEquals(1, actions.saves)
        assertEquals(3, actions.opens)
    }

    @Test
    fun slowDownloadIsNotCutOffBeforeTheHttpTimeout() = runTest {
        val actions = FakeAttachmentActions()
        val result = useDownloadedAttachment(actions, "owner/source", "large.pdf", share = false) {
            delay(25_000) // Slower than the old 20-second cap, inside the HTTP client's 30 seconds.
            FileDownload.Ready(byteArrayOf(1))
        }
        assertTrue(result.saved)
        assertEquals(1, actions.saves)
    }

    @Test
    fun savedShareUsesLocalUriWithoutFetching() = runTest {
        val actions = FakeAttachmentActions().apply { saved = true }
        val result = useDownloadedAttachment(actions, "owner/source", "document.txt", share = true) {
            error("A saved attachment must not be fetched")
        }
        assertTrue(result.saved)
        assertEquals(1, actions.localShares)
        assertEquals(0, actions.byteShares)
    }

    @Test
    fun missingLocalCopyIsTheOnlyReasonToFetchAgain() = runTest {
        val actions = FakeAttachmentActions()
        var fetches = 0
        suspend fun tap() = useDownloadedAttachment(actions, "owner/source", "document.txt", share = false) {
            fetches++
            FileDownload.Ready(byteArrayOf(1))
        }
        tap()
        actions.saved = false // Deleted outside the chat, including while it was stopped.
        tap()
        assertEquals(2, fetches)
        assertEquals(2, actions.saves)
        assertEquals(2, actions.opens)
    }

    @Test
    fun failedSaveNeverMarksTheAttachmentOpen() = runTest {
        val actions = FakeAttachmentActions().apply { saveSucceeds = false }
        val result = useDownloadedAttachment(actions, "owner/source", "document.txt", share = false) {
            FileDownload.Ready(byteArrayOf(1))
        }
        assertEquals(false, result.saved)
        assertEquals("Couldn't save", result.notice)
        assertEquals(0, actions.opens)
    }

    @Test
    fun localOpenErrorDoesNotTriggerANetworkDownload() = runTest {
        val actions = FakeAttachmentActions().apply { saved = true; openError = true }
        val result = useDownloadedAttachment(actions, "owner/source", "document.txt", share = false) {
            error("An opener error is not a missing file")
        }
        assertTrue(result.saved)
        assertTrue(result.notice != null)
        assertEquals(0, actions.saves)
    }

    @Test
    fun downloadFailureAndCancellationAreNotSaved() = runTest {
        val actions = FakeAttachmentActions()
        val result = useDownloadedAttachment(actions, "owner/source", "document.txt", share = false) {
            FileDownload.Failed("Unavailable")
        }
        assertEquals("Unavailable", result.notice)
        assertEquals(false, result.saved)
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            useDownloadedAttachment(actions, "owner/source", "document.txt", share = false) {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        assertEquals(0, actions.saves)
    }

    @Test
    fun missingOwnerUsesLegacySaveWithoutPersistentIdentity() = runTest {
        val actions = FakeAttachmentActions()
        val result = useDownloadedAttachment(actions, null, "document.txt", share = false) {
            FileDownload.Ready(byteArrayOf(1))
        }
        assertEquals(false, result.saved)
        assertEquals(1, actions.legacySaves)
        assertEquals(0, actions.saves)
    }

    @Test
    fun sourceIdentityKeepsGatewayProfileAndExactPathSeparate() {
        val gateway = dev.hermeskotlin.core.gateway.GatewayUrl.parse("https://gateway.example")
        val original = downloadedAttachmentKey(gateway to "profile", "/one/document.txt")
        assertTrue(original != null)
        kotlin.test.assertNotEquals(original, downloadedAttachmentKey(gateway to "other", "/one/document.txt"))
        kotlin.test.assertNotEquals(original, downloadedAttachmentKey("https://other.example" to "profile", "/one/document.txt"))
        kotlin.test.assertNotEquals(original, downloadedAttachmentKey(gateway to "profile", "/two/document.txt"))
        kotlin.test.assertNotEquals(downloadedAttachmentKey("a" to "b:c", "d"), downloadedAttachmentKey("a:b" to "c", "d"))
        kotlin.test.assertNotEquals(downloadedAttachmentKey(gateway to null, "source"), downloadedAttachmentKey(gateway to "", "source"))
        assertEquals(null, downloadedAttachmentKey(null, "source"))
        assertEquals(null, downloadedAttachmentKey(Any(), "source"))
        assertEquals(original, downloadedAttachmentKey(gateway to "profile", "/one/document.txt"))
    }
}

private class FakeAttachmentActions : MediaActions {
    var saved = false
    var saveSucceeds = true
    var openError = false
    var saves = 0
    var legacySaves = 0
    var opens = 0
    var localShares = 0
    var byteShares = 0
    override suspend fun save(bytes: ByteArray, name: String): String {
        legacySaves++
        return "Saved"
    }
    override suspend fun saveDownloaded(bytes: ByteArray, name: String, sourceKey: String): String {
        saves++
        saved = saveSucceeds
        return if (saveSucceeds) "Saved" else "Couldn't save"
    }
    override suspend fun hasSaved(sourceKey: String) = saved
    override suspend fun openSaved(sourceKey: String): Boolean {
        if (openError) error("No viewer available")
        if (!saved) return false
        opens++
        return true
    }
    override suspend fun shareSaved(sourceKey: String): Boolean {
        if (!saved) return false
        localShares++
        return true
    }
    override fun share(bytes: ByteArray, name: String) { byteShares++ }
}
