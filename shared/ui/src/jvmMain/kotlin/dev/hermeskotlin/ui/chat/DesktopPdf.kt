package dev.hermeskotlin.ui.chat

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicBoolean

internal const val PDF_DESKTOP_LIMIT = "This PDF exceeds the desktop viewer's limits. Save it to open it elsewhere."

internal suspend fun openDesktopPdf(
    bytes: ByteArray,
    timeoutMillis: Long = 15_000,
    start: () -> Process = ::startPdfWorker,
): PdfOpen {
    var worker: DesktopPdfPages? = null
    var handedOff = false
    try {
        val result = withContext(Dispatchers.IO) {
            if (bytes.isEmpty()) return@withContext PdfOpen.Failed(PDF_BROKEN)
            if (bytes.size > PdfWire.MAX_BYTES) return@withContext PdfOpen.Failed(PDF_DESKTOP_LIMIT)
            val pages = try {
                DesktopPdfPages(start(), timeoutMillis).also { worker = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext PdfOpen.Failed(PDF_DESKTOP_LIMIT)
            }
            pages.open(bytes)
        }
        handedOff = result is PdfOpen.Ready
        return result
    } finally {
        // withContext may discard a successful result when delivery to its caller is cancelled.
        if (!handedOff) worker?.close()
    }
}

private fun startPdfWorker(): Process {
    val java = File(System.getProperty("java.home"), "bin/" + if (File.separatorChar == '\\') "java.exe" else "java")
    // Gradle's test loader carries URLs; installed launchers supply java.class.path directly.
    val paths = linkedSetOf<String>()
    paths.addAll(System.getProperty("java.class.path").split(File.pathSeparator))
    generateSequence(PdfWorker::class.java.classLoader) { it.parent }.forEach { loader ->
        (loader as? URLClassLoader)?.urLs?.filter { it.protocol == "file" }?.forEach { paths.add(File(it.toURI()).path) }
    }
    return ProcessBuilder(
        java.path, "-Xmx256m", "-XX:MaxDirectMemorySize=64m", "-XX:+ExitOnOutOfMemoryError",
        "-Djava.awt.headless=true", "-cp", paths.joinToString(File.pathSeparator), PdfWorker::class.java.name,
    ).apply {
        val inherited = environment().filterKeys { it in setOf("LANG", "LC_ALL", "TMPDIR", "TEMP", "TMP", "SystemRoot", "WINDIR") }
        environment().clear()
        environment().putAll(inherited)
        redirectError(ProcessBuilder.Redirect.DISCARD)
    }.start()
}

private class DesktopPdfPages(private val process: Process, private val timeoutMillis: Long) : PdfPages {
    private val input = DataInputStream(process.inputStream.buffered())
    private val output = DataOutputStream(process.outputStream.buffered())
    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()
    override var aspects: List<Float> = emptyList()
        private set

    suspend fun open(bytes: ByteArray): PdfOpen {
        val status = transaction {
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
            val status = input.readInt()
            if (status == PdfWire.READY) {
                val count = input.readInt()
                require(count in 1..PdfWire.MAX_PAGES)
                aspects = List(count) { input.readFloat().also { require(it.isFinite() && it > 0) } }
            }
            status
        }
        return when (status) {
            PdfWire.READY -> PdfOpen.Ready(this)
            PdfWire.LOCKED -> PdfOpen.Failed(PDF_LOCKED)
            PdfWire.BROKEN -> PdfOpen.Failed(PDF_BROKEN)
            else -> PdfOpen.Failed(PDF_DESKTOP_LIMIT)
        }
    }

    override suspend fun render(index: Int, width: Int, height: Int): ImageBitmap? {
        if (index !in aspects.indices || !PdfWire.dimensions(width, height)) return null
        return transaction {
            output.writeInt(index)
            output.writeInt(width)
            output.writeInt(height)
            output.flush()
            require(input.readInt() == width && input.readInt() == height)
            val pixels = ByteArray(width * height * 4).also(input::readFully)
            Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE), pixels, width * 4)
                .toComposeImageBitmap()
        }
    }

    private suspend fun <T> transaction(block: () -> T): T? = mutex.withLock {
        if (closed.get()) return@withLock null
        try {
            withTimeoutOrNull(timeoutMillis) {
                coroutineScope {
                    val finished = AtomicBoolean(false)
                    // Pipe reads may ignore interruption. Cancellation must kill the writer as well.
                    val retirement = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { if (!finished.get()) close() }
                    }
                    try {
                        runInterruptible(Dispatchers.IO) { block() }
                    } finally {
                        finished.set(true)
                        retirement.cancel()
                    }
                }
            }.also { if (it == null) close() }
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (_: Exception) {
            close()
            null
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            process.destroyForcibly()
            // Never wait for a worker or a locked pipe on the UI thread.
            process.onExit().thenRun {
                runCatching { input.close() }
                runCatching { output.close() }
            }
        }
    }
}
