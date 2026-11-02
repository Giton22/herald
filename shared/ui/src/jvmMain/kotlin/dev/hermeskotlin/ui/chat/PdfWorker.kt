package dev.hermeskotlin.ui.chat

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.io.DataOutputStream

/** Private, fixed-size messages. No document paths, URLs or serialized Java objects cross this pipe. */
internal object PdfWire {
    const val MAX_BYTES = 64 * 1024 * 1024
    const val MAX_PAGES = 500
    const val MAX_PIXELS = 8_000_000L
    const val READY = 1
    const val LOCKED = 2
    const val BROKEN = 3
    const val LIMIT = 4

    fun dimensions(width: Int, height: Int) =
        width in 1..16384 && height in 1..16384 && width.toLong() * height <= MAX_PIXELS
}

/** Only the child loads PDFBox documents. The parent can retire a parser that stops responding. */
internal object PdfWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        val input = DataInputStream(System.`in`.buffered())
        val output = DataOutputStream(System.out.buffered())
        try {
            val size = input.readInt()
            require(size in 1..PdfWire.MAX_BYTES)
            val bytes = ByteArray(size).also(input::readFully)
            Loader.loadPDF(bytes).use { document ->
                require(document.numberOfPages in 1..PdfWire.MAX_PAGES)
                val shapes = document.pages.map { page ->
                    val box = page.cropBox
                    val rotated = page.rotation % 180 != 0
                    val width = if (rotated) box.height else box.width
                    val height = if (rotated) box.width else box.height
                    require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
                    (width / height).also { require(it.isFinite() && it > 0) }
                }
                output.writeInt(PdfWire.READY)
                output.writeInt(shapes.size)
                shapes.forEach(output::writeFloat)
                output.flush()
                val renderer = PDFRenderer(document).apply { isSubsamplingAllowed = true }
                while (true) {
                    val index = input.readInt()
                    val width = input.readInt()
                    val height = input.readInt()
                    require(index in shapes.indices && PdfWire.dimensions(width, height))
                    val page = document.getPage(index)
                    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                    val graphics = image.createGraphics()
                    try {
                        graphics.color = Color.WHITE
                        graphics.fillRect(0, 0, width, height)
                        renderer.renderPageToGraphics(index, graphics, width / page.width(), height / page.height())
                    } finally {
                        graphics.dispose()
                    }
                    output.writeInt(width)
                    output.writeInt(height)
                    // Raw RGBA avoids sending a compressed image for the parent to parse or expand.
                    val row = IntArray(width)
                    for (y in 0 until height) {
                        image.getRGB(0, y, width, 1, row, 0, width)
                        row.forEach { output.writeInt((it shl 8) or 0xff) }
                    }
                    output.flush()
                    image.flush()
                }
            }
        } catch (_: InvalidPasswordException) {
            output.writeInt(PdfWire.LOCKED)
            output.flush()
        } catch (_: IllegalArgumentException) {
            output.writeInt(PdfWire.LIMIT)
            output.flush()
        } catch (_: Exception) {
            // EOF is also the normal end of the parent connection; nothing sensitive goes to stderr.
            runCatching { output.writeInt(PdfWire.BROKEN); output.flush() }
        }
    }

    private fun PDPage.width() = if (rotation % 180 != 0) cropBox.height else cropBox.width
    private fun PDPage.height() = if (rotation % 180 != 0) cropBox.width else cropBox.height
}
