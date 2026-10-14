package dev.hermeskotlin.ui.chat

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal actual fun rememberPdfOpener(): suspend (ByteArray) -> PdfOpen {
    val cache = LocalContext.current.applicationContext.cacheDir
    return remember(cache) { { bytes -> openPdf(cache, bytes) } }
}

/**
 * PdfRenderer reads from a seekable file, so the bytes go to the cache for as long as it takes to open
 * them: the open descriptor keeps them readable after the file is gone.
 */
private suspend fun openPdf(cache: File, bytes: ByteArray): PdfOpen = withContext(Dispatchers.IO) {
    if (bytes.isEmpty()) return@withContext PdfOpen.Failed(PDF_BROKEN)
    var file: File? = null
    try {
        // A full or missing cache is a failure to show, not a crash.
        val descriptor = try {
            file = File.createTempFile("viewer-", ".pdf", cache).apply { writeBytes(bytes) }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            return@withContext PdfOpen.Failed(PDF_NO_ROOM)
        }
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (e: SecurityException) {
            descriptor.close()
            return@withContext PdfOpen.Failed(PDF_LOCKED)
        } catch (e: Exception) {
            descriptor.close()
            return@withContext PdfOpen.Failed(PDF_BROKEN)
        }
        val aspects = try {
            List(renderer.pageCount) { index -> renderer.openPage(index).use { it.width.toFloat() / it.height } }
        } catch (e: Exception) {
            null
        }
        if (aspects.isNullOrEmpty()) {
            renderer.close()
            PdfOpen.Failed(PDF_BROKEN)
        } else {
            PdfOpen.Ready(AndroidPdfPages(renderer, aspects))
        }
    } finally {
        file?.delete()
    }
}

/** One page open at a time, as PdfRenderer allows: every render and the close run on one thread in turn. */
private class AndroidPdfPages(private val renderer: PdfRenderer, override val aspects: List<Float>) : PdfPages {
    private val thread = Dispatchers.IO.limitedParallelism(1)
    private val closer = CoroutineScope(SupervisorJob() + thread)
    private var closed = false

    override suspend fun render(index: Int, width: Int, height: Int): ImageBitmap? = withContext(thread) {
        if (closed) return@withContext null
        try {
            renderer.openPage(index).use { page ->
                // With no transform the page is scaled to fill the bitmap, whatever its own proportions.
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // Pages draw onto what's there; most expect paper underneath.
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap()
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    // Queued behind a page being drawn, never under it. Also closes the descriptor.
    override fun close() {
        closer.launch {
            if (!closed) {
                closed = true
                renderer.close()
            }
            closer.cancel()
        }
    }
}
