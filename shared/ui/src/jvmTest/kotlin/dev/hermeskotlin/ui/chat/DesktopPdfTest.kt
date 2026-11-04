package dev.hermeskotlin.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.onNodeWithText
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.ProvideAppLanguage
import dev.hermeskotlin.ui.applyAppLanguage
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopPdfTest {
    @Test
    fun desktopLimitGuidanceUsesSpanish() = runComposeUiTest {
        val locale = Locale.getDefault()
        try {
            setContent {
                ProvideAppLanguage("es") {
                    HermesTheme {
                        FileViewerPage(GatewayFile("/fixture.pdf"), {}, { error("PDFs download whole") },
                            { FileDownload.Ready(byteArrayOf(1)) },
                            openPdf = { PdfOpen.Failed(PDF_DESKTOP_LIMIT) })
                    }
                }
            }
            onNodeWithText("Este PDF supera los límites del visor de escritorio. Guárdalo para abrirlo en otra aplicación.").assertExists()
        } finally {
            applyAppLanguage(null)
            Locale.setDefault(locale)
        }
    }

    @Test
    fun desktopOpenerDrawsPdfPages() = runComposeUiTest {
        lateinit var open: suspend (ByteArray) -> PdfOpen
        setContent { open = rememberPdfOpener() }
        waitForIdle()
        runBlocking {
            val result = assertIs<PdfOpen.Ready>(open(pdf()))
            result.pages.use { pages ->
                assertEquals(listOf(0.5f, 2f), pages.aspects)
                val image = assertNotNull(pages.render(0, 100, 200))
                assertEquals(100, image.width)
                assertEquals(200, image.height)
                assertEquals(Color.Red, image.toPixelMap()[50, 100])
                assertNotNull(pages.render(1, 200, 100))
                assertNotNull(pages.render(0, 250, 500))
            }
        }
    }

    @Test
    fun malformedAndEmptyDocumentsHaveAnExplanation() = runBlocking {
        assertEquals(PdfOpen.Failed(PDF_BROKEN), openDesktopPdf("not a PDF".toByteArray()))
        assertEquals(PdfOpen.Failed(PDF_BROKEN), openDesktopPdf(byteArrayOf()))
    }

    @Test
    fun protectedDocumentsKeepTheSaveFallback() = runBlocking {
        val encrypted = Loader.loadPDF(pdf()).use { document ->
            document.protect(StandardProtectionPolicy("owner", "reader", AccessPermission()))
            ByteArrayOutputStream().also(document::save).toByteArray()
        }
        assertEquals(PdfOpen.Failed(PDF_LOCKED), openDesktopPdf(encrypted))
    }

    @Test
    fun textFontsRenderIntoPixels() = runBlocking {
        val bytes = PDDocument().use { document ->
            val page = PDPage().also(document::addPage)
            org.apache.pdfbox.pdmodel.PDPageContentStream(document, page).use {
                it.beginText()
                it.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 24f)
                it.newLineAtOffset(50f, 700f)
                it.showText("PDF preview")
                it.endText()
            }
            ByteArrayOutputStream().also(document::save).toByteArray()
        }
        assertIs<PdfOpen.Ready>(openDesktopPdf(bytes)).pages.use {
            val pixels = assertNotNull(it.render(0, 612, 792)).toPixelMap()
            assertTrue((50..220).any { x -> (70..100).any { y -> pixels[x, y] != Color.White } })
        }
    }

    @Test
    fun rotatedPageShapeAndPixelsAgree() = runBlocking {
        val bytes = Loader.loadPDF(pdf()).use { document ->
            document.getPage(0).rotation = 90
            ByteArrayOutputStream().also(document::save).toByteArray()
        }
        val pages = assertIs<PdfOpen.Ready>(openDesktopPdf(bytes)).pages
        pages.use {
            assertEquals(2f, it.aspects.first())
            assertEquals(Color.Red, assertNotNull(it.render(0, 200, 100)).toPixelMap()[100, 50])
        }
    }

    @Test
    fun limitsRejectAllocationAndLeaveValidPagesUsable() = runBlocking {
        var launched = false
        assertIs<PdfOpen.Failed>(openDesktopPdf(ByteArray(PdfWire.MAX_BYTES + 1)) {
            launched = true
            error("must not launch")
        })
        assertFalse(launched)
        val pages = assertIs<PdfOpen.Ready>(openDesktopPdf(pdf())).pages
        pages.use {
            assertNull(it.render(-1, 100, 100))
            assertNull(it.render(2, 100, 100))
            assertNull(it.render(0, 0, 100))
            assertNull(it.render(0, Int.MAX_VALUE, Int.MAX_VALUE))
            assertNull(it.render(0, 3000, 3000))
            assertNotNull(it.render(0, 100, 200))
        }
        assertNull(pages.render(0, 100, 200))
        pages.close()
    }

    @Test
    fun pageCountIsBounded() = runBlocking {
        val bytes = PDDocument().use { document ->
            repeat(PdfWire.MAX_PAGES + 1) { document.addPage(PDPage()) }
            ByteArrayOutputStream().also(document::save).toByteArray()
        }
        assertEquals(PdfOpen.Failed(PDF_DESKTOP_LIMIT), openDesktopPdf(bytes))
    }

    @Test
    fun closingRepeatedDocumentsRetiresTheirProcesses() = runBlocking {
        repeat(3) {
            lateinit var process: Process
            val pages = assertIs<PdfOpen.Ready>(openDesktopPdf(pdf()) {
                fixture("ready").also { process = it }
            }).pages
            assertTrue(process.isAlive)
            pages.close()
            pages.close()
            assertTrue(process.waitFor(3, TimeUnit.SECONDS))
            assertNull(pages.render(0, 10, 10))
        }
    }

    @Test
    fun deadlineRetiresAWorkerStuckDuringOpen() = runBlocking {
        lateinit var process: Process
        assertEquals(PdfOpen.Failed(PDF_DESKTOP_LIMIT), withTimeout(5_000) {
            openDesktopPdf(pdf(), timeoutMillis = 300) { fixture("hang-open").also { process = it } }
        })
        assertTrue(process.waitFor(3, TimeUnit.SECONDS))
    }

    @Test
    fun cancellationDuringOpenRetiresTheWorker() = runBlocking {
        val started = CompletableDeferred<Process>()
        val opening = async {
            openDesktopPdf(pdf()) { fixture("hang-open").also { started.complete(it) } }
        }
        val process = withTimeout(5_000) { started.await() }
        withTimeout(5_000) { opening.cancelAndJoin() }
        assertTrue(opening.isCancelled)
        assertTrue(process.waitFor(3, TimeUnit.SECONDS))
    }

    @Test
    fun cancellationDuringRenderRetiresTheWorker() = runBlocking {
        lateinit var process: Process
        var launches = 0
        val pages = assertIs<PdfOpen.Ready>(openDesktopPdf(pdf()) {
            fixture(if (launches++ == 0) "ready" else "draw").also { process = it }
        }).pages
        pages.use {
            val rendering = async { it.render(0, 100, 200) }
            // The fixture acknowledges the request by closing stderr, independently of the response pipe.
            withTimeout(5_000) { kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) { process.errorStream.read() } }
            withTimeout(5_000) { rendering.cancelAndJoin() }
            assertTrue(rendering.isCancelled)
            assertTrue(process.waitFor(3, TimeUnit.SECONDS))
            assertNotNull(it.render(0, 100, 200), "A new zoom target must still render after cancellation")
            assertEquals(2, launches)
        }
    }

    @Test
    fun renderDeadlineAndExplicitCloseUnblockPipeReads() = runBlocking {
        for (explicitClose in listOf(false, true)) {
            lateinit var process: Process
            val pages = assertIs<PdfOpen.Ready>(openDesktopPdf(pdf(), timeoutMillis = 500) {
                fixture("ready").also { process = it }
            }).pages
            pages.use {
                val rendering = async { it.render(0, 100, 200) }
                withTimeout(5_000) {
                    kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) { process.errorStream.read() }
                }
                if (explicitClose) it.close()
                assertNull(withTimeout(5_000) { rendering.await() })
                assertTrue(process.waitFor(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun closeDuringReplacementStartupCannotPublishALiveWorker() = runBlocking {
        var launches = 0
        val replacement = CompletableDeferred<Process>()
        val release = java.util.concurrent.CountDownLatch(1)
        lateinit var process: Process
        val pages = assertIs<PdfOpen.Ready>(openDesktopPdf(pdf(), timeoutMillis = 300) {
            fixture("ready").also {
                process = it
                if (++launches == 2) {
                    replacement.complete(it)
                    release.await(3, TimeUnit.SECONDS)
                }
            }
        }).pages
        try {
            assertNull(pages.render(0, 100, 200))
            assertTrue(process.waitFor(3, TimeUnit.SECONDS))
            val rendering = async { pages.render(0, 100, 200) }
            val next = withTimeout(5_000) { replacement.await() }
            pages.close()
            release.countDown()
            assertNull(withTimeout(5_000) { rendering.await() })
            assertTrue(next.waitFor(3, TimeUnit.SECONDS))
            assertNull(pages.render(0, 100, 200))
            assertEquals(2, launches)
        } finally {
            release.countDown()
            pages.close()
            process.destroyForcibly()
        }
    }

    @Test
    fun cancelledDeliveryOfOpenedPagesRetiresTheWorker() = runBlocking {
        val ownerThread = Thread.currentThread()
        val delivery = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (Thread.currentThread() != ownerThread) context[Job]!!.cancel()
                block.run()
            }
        }
        lateinit var process: Process
        val opening = async {
            withContext(delivery) {
                openDesktopPdf(pdf()) { fixture("ready").also { process = it } }
            }
        }
        opening.join()
        assertTrue(opening.isCancelled)
        try {
            assertTrue(process.waitFor(2, TimeUnit.SECONDS), "Undelivered pages must not leave a worker alive")
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun invalidWorkerHeadersAreRejectedAndRetired() = runBlocking {
        for (mode in listOf("huge-count", "bad-aspect", "wrong-dimensions", "early-exit")) {
            lateinit var process: Process
            val opened = openDesktopPdf(pdf()) { fixture(mode).also { process = it } }
            if (mode == "wrong-dimensions") {
                assertIs<PdfOpen.Ready>(opened).pages.use { assertNull(it.render(0, 10, 20)) }
            } else {
                assertIs<PdfOpen.Failed>(opened)
            }
            assertTrue(process.waitFor(3, TimeUnit.SECONDS))
        }
    }

    private fun fixture(mode: String): Process {
        val paths = linkedSetOf<String>()
        paths.addAll(System.getProperty("java.class.path").split(File.pathSeparator))
        generateSequence(javaClass.classLoader) { it.parent }.forEach { loader ->
            (loader as? URLClassLoader)?.urLs?.filter { it.protocol == "file" }?.forEach { paths.add(File(it.toURI()).path) }
        }
        val executable = if (File.separatorChar == '\\') "java.exe" else "java"
        return ProcessBuilder(File(System.getProperty("java.home"), "bin/$executable").path,
            "-cp", paths.joinToString(File.pathSeparator), PdfWorkerFixture::class.java.name, mode).start()
    }

    private fun pdf(): ByteArray {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 200] /Contents 5 0 R >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Contents 5 0 R >>",
            "<< /Length 27 >>\nstream\n1 0 0 rg 0 0 100 200 re f\nendstream",
        )
        val body = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, value ->
            body.length.also { body.append("${index + 1} 0 obj\n$value\nendobj\n") }
        }
        val xref = body.length
        body.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { body.append("%010d 00000 n \n".format(it)) }
        body.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return body.toString().toByteArray(Charsets.US_ASCII)
    }
}
