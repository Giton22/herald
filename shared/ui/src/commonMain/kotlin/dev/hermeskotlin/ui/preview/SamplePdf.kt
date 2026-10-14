package dev.hermeskotlin.ui.preview

/**
 * A small PDF written out by hand, for previews of the PDF viewer: each page is a content stream of
 * drawing operators on US Letter, with Helvetica as /F1 and Helvetica-Bold as /F2. ASCII only, so a
 * character is a byte and the cross-reference offsets are plain string lengths.
 */
internal fun samplePdf(pages: List<String>): ByteArray {
    val objects = mutableListOf<String>()
    val firstPage = 5
    val kids = pages.indices.joinToString(" ") { "${firstPage + it * 2} 0 R" }
    objects += "<< /Type /Catalog /Pages 2 0 R >>"
    objects += "<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>"
    objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
    objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>"
    pages.forEachIndexed { index, content ->
        objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
            "/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents ${firstPage + index * 2 + 1} 0 R >>"
        objects += "<< /Length ${content.length} >>\nstream\n$content\nendstream"
    }
    val out = StringBuilder("%PDF-1.4\n")
    val offsets = objects.mapIndexed { index, body ->
        val at = out.length
        out.append("${index + 1} 0 obj\n$body\nendobj\n")
        at
    }
    val xref = out.length
    out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
    offsets.forEach { out.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
    out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return out.toString().encodeToByteArray()
}

/** Operators for one page, with helpers for the few marks a sample needs. y counts up from the bottom. */
internal class PdfPageBuilder {
    private val ops = StringBuilder()

    fun text(x: Int, y: Int, text: String, size: Int = 11, bold: Boolean = false, gray: Float = 0.1f) {
        val escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        ops.append("$gray g BT /${if (bold) "F2" else "F1"} $size Tf $x $y Td ($escaped) Tj ET\n")
    }

    fun rect(x: Int, y: Int, width: Int, height: Int, r: Float, g: Float, b: Float) {
        ops.append("$r $g $b rg $x $y $width $height re f\n")
    }

    fun line(x1: Int, y1: Int, x2: Int, y2: Int, gray: Float = 0.8f) {
        ops.append("$gray G 1 w $x1 $y1 m $x2 $y2 l S\n")
    }

    fun build(): String = ops.toString().trimEnd()
}

internal fun pdfPage(draw: PdfPageBuilder.() -> Unit): String = PdfPageBuilder().apply(draw).build()
