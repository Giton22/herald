package dev.hermeskotlin.ui.chat

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SavedFileTextTest {
    private lateinit var file: File
    private lateinit var resolver: ContentResolver
    private lateinit var provider: LocalTextProvider
    private val uri = Uri.parse("content://saved-files/report.txt")

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        resolver = context.contentResolver
        file = File.createTempFile("saved-report-", ".txt", context.cacheDir)
        provider = LocalTextProvider(file)
        provider.attachInfo(context, ProviderInfo().apply { authority = "saved-files" })
        ShadowContentResolver.registerProviderInternal("saved-files", provider)
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun utf8BomIsNotShown() = runTest {
        file.writeBytes(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "Report".toByteArray())
        assertEquals("Report", assertIs<FileLoad.Ready>(readSavedText(resolver, uri)).text.text)
    }

    @Test
    fun utf16BomIsDecodedInBothByteOrders() = runTest {
        for (charset in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val bom = if (charset == Charsets.UTF_16LE) byteArrayOf(-1, -2) else byteArrayOf(-2, -1)
            file.writeBytes(bom + "Report café 🌍".toByteArray(charset))
            val result = assertIs<FileLoad.Ready>(readSavedText(resolver, uri)).text
            assertEquals("Report café 🌍", result.text)
            assertEquals(false, result.binary)
        }
    }

    @Test
    fun zipAndOfficeDisguisedAsTextAreBinary() = runTest {
        for (header in listOf(byteArrayOf(0x50, 0x4b, 3, 4), byteArrayOf(0x50, 0x4b, 5, 6),
            byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte()))) {
            file.writeBytes(header + "not plain text".toByteArray())
            val result = assertIs<FileLoad.Ready>(readSavedText(resolver, uri)).text
            assertEquals(true, result.binary)
            assertEquals("", result.text)
        }
    }

    @Test
    fun nullAndOtherControlBytesAreBinary() = runTest {
        for (bytes in listOf(byteArrayOf(65, 0, 66), byteArrayOf(1, 2, 3, 4))) {
            file.writeBytes(bytes)
            assertEquals(true, assertIs<FileLoad.Ready>(readSavedText(resolver, uri)).text.binary)
        }
    }

    @Test
    fun malformedUtf8IsNotDisplayedAsText() = runTest {
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertEquals(true, assertIs<FileLoad.Ready>(readSavedText(resolver, uri)).text.binary)
    }

    @Test
    fun truncatedMultibyteCharacterDoesNotAddReplacementText() = runTest {
        file.writeText("x".repeat(512 * 1024 - 1) + "🌍")
        val result = assertIs<FileLoad.Ready>(readSavedText(resolver, uri)).text
        assertEquals("x".repeat(512 * 1024 - 1), result.text)
        assertEquals(false, result.binary)
        assertEquals(true, result.truncated)
    }

    @Test
    fun exactCapIsNotTruncated() = runTest {
        file.writeText("x".repeat(512 * 1024))
        assertEquals(false, assertIs<FileLoad.Ready>(readSavedText(resolver, uri)).text.truncated)
    }

    @Test
    fun emptySavedFileIsValidText() = runTest {
        val result = assertIs<FileLoad.Ready>(readSavedText(resolver, uri)).text
        assertEquals("", result.text)
        assertEquals(0L, result.byteSize)
        assertEquals(false, result.binary)
    }

    @Test
    fun missingFileReturnsPoliteFailure() = runTest {
        file.delete()
        assertEquals("Couldn't read this saved file. It may have been moved or deleted.", assertIs<FileLoad.Failed>(readSavedText(resolver, uri)).message)
        assertIs<FileDownload.Failed>(readSavedBytes(resolver, uri))
    }

    @Test
    fun revokedPermissionReturnsFailure() = runTest {
        provider.failure = SecurityException("No permission")
        assertIs<FileLoad.Failed>(readSavedText(resolver, uri))
        assertIs<FileDownload.Failed>(readSavedBytes(resolver, uri))
    }

    @Test
    fun cancellationIsNotTurnedIntoFailure() = runTest {
        provider.failure = kotlinx.coroutines.CancellationException("Cancelled")
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { readSavedText(resolver, uri) }
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { readSavedBytes(resolver, uri) }
    }

    @Test
    fun nonContentUrisAreRejectedWithoutOpeningAnything() = runTest {
        for (other in listOf(Uri.fromFile(file), Uri.parse("https://example.invalid/report.txt"))) {
            assertIs<FileLoad.Failed>(readSavedText(resolver, other))
            assertIs<FileDownload.Failed>(readSavedBytes(resolver, other))
        }
        assertEquals(emptyList(), provider.opened)
    }

    @Test
    fun byteReaderReturnsTheActualSavedBytesAtTheLimit() = runTest {
        val bytes = byteArrayOf(0, 1, 2, -1)
        file.writeBytes(bytes)
        kotlin.test.assertContentEquals(bytes, assertIs<FileDownload.Ready>(readSavedBytes(resolver, uri, 4)).bytes)
    }

    @Test
    fun byteReaderRejectsFilesLargerThanItsLimit() = runTest {
        file.writeBytes(ByteArray(5))
        assertIs<FileDownload.Failed>(readSavedBytes(resolver, uri, 4))
    }

    @Test
    fun defaultByteLimitRejectsLargeSavedFiles() = runTest {
        java.io.RandomAccessFile(file, "rw").use { it.setLength(32L * 1024 * 1024 + 1) }
        assertIs<FileDownload.Failed>(readSavedBytes(resolver, uri))
    }

    @Test
    fun zeroByteLimitAcceptsOnlyEmptyFiles() = runTest {
        assertEquals(0, assertIs<FileDownload.Ready>(readSavedBytes(resolver, uri, 0)).bytes.size)
        file.writeText("x")
        assertIs<FileDownload.Failed>(readSavedBytes(resolver, uri, 0))
    }

    @Test
    fun negativeByteLimitFailsPolitely() = runTest {
        assertIs<FileDownload.Failed>(readSavedBytes(resolver, uri, -1))
    }

    @Test
    fun capsThePreviewButReportsTheWholeSavedSize() = runTest {
        file.writeText("x".repeat(512 * 1024 + 100))
        val result = assertIs<FileLoad.Ready>(readSavedText(resolver, uri)).text
        assertEquals(512 * 1024, result.text.length)
        assertEquals(file.length(), result.byteSize)
        assertEquals(true, result.truncated)
    }

    @Test
    fun readsTheSavedContentAsUtf8() = runTest {
        val text = "# Report\nSaved locally: café 🌍\n"
        file.writeText(text)
        val result = assertIs<FileLoad.Ready>(readSavedText(resolver, uri)).text
        assertEquals(text, result.text)
        assertEquals(file.length(), result.byteSize)
        assertEquals(false, result.truncated)
        assertEquals(false, result.binary)
        assertEquals(listOf(uri), provider.opened)
    }
}

private class LocalTextProvider(private val file: File) : ContentProvider() {
    val opened = mutableListOf<Uri>()
    var failure: Exception? = null
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        failure?.let { throw it }
        opened += uri
        check(mode == "r")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "text/plain"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read only")
}
