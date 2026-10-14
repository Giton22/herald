package dev.hermeskotlin.ui.chat

import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.max

/** A decoded TIFF: [width] × [height] ARGB pixels, and the file's Orientation tag (1–8, as EXIF numbers it). */
internal class TiffImage(val width: Int, val height: Int, val argb: IntArray, val orientation: Int)

/** True when [bytes] start with a classic TIFF header, little (`II*\0`) or big endian (`MM\0*`). */
internal fun isTiff(bytes: ByteArray): Boolean = bytes.size >= 4 && (
    bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 42.toByte() && bytes[3] == 0.toByte() ||
        bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() && bytes[2] == 0.toByte() && bytes[3] == 42.toByte()
    )

/**
 * The first picture of a baseline TIFF, which Android's own decoders can't read. Strips only, uncompressed,
 * PackBits, LZW or Deflate (with or without the horizontal predictor); 8-bit gray, gray + alpha, RGB and RGBA;
 * 1-, 2-, 4- and 8-bit gray and palette. Anything else, or a broken file, is null.
 *
 * The bytes come from an agent's reply, so nothing in the file is trusted: a picture over [maxPixels] is refused
 * from its header, before anything is allocated, every read is bounds-checked, and a strip is read a row at a
 * time, so a compressed strip that claims more than its rows can't grow. The result is box-filtered down so its
 * long edge is at most [maxEdge].
 */
internal fun decodeTiff(bytes: ByteArray, maxEdge: Int, maxPixels: Long = MAX_TIFF_PIXELS): TiffImage? {
    if (!isTiff(bytes) || maxEdge <= 0) return null
    return try {
        TiffReader(bytes).decode(maxEdge, maxPixels)
    } catch (e: Malformed) {
        null
    }
}

/** Larger than any phone photo; at 4 bytes a pixel, still a picture the decode can hold. */
internal const val MAX_TIFF_PIXELS = 40_000_000L

private class Malformed : Exception() {
    // Thrown for every broken file; the stack trace is never looked at.
    override fun fillInStackTrace(): Throwable = this
}

private fun bad(): Nothing = throw Malformed()

private class TiffReader(private val data: ByteArray) {
    private val bigEndian = data[0] == 'M'.code.toByte()
    private val entries = HashMap<Int, Entry>()

    /** A directory entry: its [type], [count] values, and where the first value is. */
    private class Entry(val type: Int, val count: Long, val at: Long)

    fun decode(maxEdge: Int, maxPixels: Long): TiffImage {
        readDirectory(u32(4))
        val width = number(WIDTH) ?: bad()
        val height = number(HEIGHT) ?: bad()
        // Each side is checked first: two 32-bit sides can multiply past a Long.
        if (width <= 0 || height <= 0 || width > maxPixels || height > maxPixels || width * height > maxPixels) bad()
        val w = width.toInt()
        val h = height.toInt()

        val samples = (number(SAMPLES_PER_PIXEL) ?: 1).toInt()
        if (samples !in 1..MAX_SAMPLES) bad()
        val depth = entries[BITS_PER_SAMPLE]?.let { bits ->
            if (bits.count < samples) bad()
            val first = value(bits, 0)
            for (i in 1 until samples) if (value(bits, i) != first) bad()
            first.toInt()
        } ?: 1
        if ((number(PLANAR) ?: 1) != 1L || (number(FILL_ORDER) ?: 1) != 1L || TILE_WIDTH in entries) bad()
        if ((number(SAMPLE_FORMAT) ?: 1) != 1L) bad()
        val predictor = number(PREDICTOR) ?: 1
        if (predictor != 1L && !(predictor == 2L && depth == 8)) bad()

        val photometric = number(PHOTOMETRIC) ?: bad()
        val pixels = Pixels.of(photometric, samples, depth, extraAlpha(), if (photometric == PALETTE) palette(depth) else null)
        val rowBytes = (w.toLong() * samples * depth + 7) / 8
        if (rowBytes > MAX_ROW_BYTES) bad()
        val compression = (number(COMPRESSION) ?: 1).toInt()
        if (compression !in COMPRESSIONS) bad()

        val rowsPerStrip = (number(ROWS_PER_STRIP) ?: height).coerceAtMost(height)
        if (rowsPerStrip <= 0) bad()
        val strips = ((height + rowsPerStrip - 1) / rowsPerStrip).toInt()
        // Each strip costs a little on its own; millions of one-byte strips would take minutes.
        if (strips > MAX_STRIPS) bad()
        val offsets = entries[STRIP_OFFSETS] ?: bad()
        val counts = entries[STRIP_BYTE_COUNTS]
        if (offsets.count < strips || counts != null && counts.count < strips) bad()
        if (counts == null && compression != NONE) bad()

        val out = Downscaler(w, h, maxEdge)
        val row = ByteArray(rowBytes.toInt())
        // One reader for the whole picture, started again at each strip.
        val strip = when (compression) {
            NONE -> RawStrip(data)
            PACKBITS -> PackBitsStrip(data)
            LZW -> LzwStrip(data)
            else -> DeflateStrip(data)
        }
        try {
            for (s in 0 until strips) {
                val rows = minOf(rowsPerStrip, height - s * rowsPerStrip).toInt()
                val start = value(offsets, s)
                val length = counts?.let { value(it, s) } ?: (rowBytes * rows)
                if (start < 0 || length < 0 || start + length > data.size) bad()
                strip.start(start.toInt(), length.toInt())
                repeat(rows) {
                    if (!strip.fill(row)) bad()
                    if (predictor == 2L) for (i in samples until row.size) row[i] = (row[i] + row[i - samples]).toByte()
                    out.addRow { x -> pixels.argb(row, x) }
                }
            }
        } finally {
            strip.close()
        }
        val orientation = (number(ORIENTATION) ?: 1).toInt().takeIf { it in 1..8 } ?: 1
        return TiffImage(out.width, out.height, out.pixels, orientation)
    }

    /** Whether the extra sample after the colors is alpha, and if it's premultiplied. */
    private fun extraAlpha(): Alpha {
        val extra = entries[EXTRA_SAMPLES] ?: return Alpha.None
        if (extra.count < 1) return Alpha.None
        return when (value(extra, 0)) {
            1L -> Alpha.Premultiplied
            2L -> Alpha.Straight
            else -> Alpha.None
        }
    }

    /** The color map as ARGB, for a palette picture of [depth] bits; null when the file has none. */
    private fun palette(depth: Int): IntArray? {
        val map = entries[COLOR_MAP] ?: return null
        if (depth !in 1..8 || map.type != SHORT) bad()
        val size = 1 shl depth
        if (map.count != 3L * size) bad()
        return IntArray(size) { i ->
            val r = value(map, i).toInt() ushr 8
            val g = value(map, size + i).toInt() ushr 8
            val b = value(map, 2 * size + i).toInt() ushr 8
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun readDirectory(at: Long) {
        if (at < 8) bad()
        val count = u16(at)
        for (i in 0 until count) {
            val entry = at + 2 + 12L * i
            val type = u16(entry + 2)
            val n = u32(entry + 4)
            val size = n * typeSize(type)
            entries[u16(entry)] = Entry(type, n, if (size in 0..4) entry + 8 else u32(entry + 8))
        }
    }

    /** The single value of [tag], if the file has it. */
    private fun number(tag: Int): Long? = entries[tag]?.let { if (it.count < 1) bad() else value(it, 0) }

    private fun value(entry: Entry, index: Int): Long {
        if (index >= entry.count) bad()
        return when (entry.type) {
            BYTE -> u8(entry.at + index).toLong()
            SHORT -> u16(entry.at + 2L * index).toLong()
            LONG -> u32(entry.at + 4L * index)
            else -> bad()
        }
    }

    private fun typeSize(type: Int): Long = when (type) {
        BYTE, ASCII, SBYTE, UNDEFINED -> 1
        SHORT, SSHORT -> 2
        LONG, SLONG, FLOAT -> 4
        RATIONAL, SRATIONAL, DOUBLE -> 8
        else -> 0
    }

    private fun u8(at: Long): Int {
        if (at < 0 || at >= data.size) bad()
        return data[at.toInt()].toInt() and 0xFF
    }

    private fun u16(at: Long): Int =
        if (bigEndian) (u8(at) shl 8) or u8(at + 1) else u8(at) or (u8(at + 1) shl 8)

    private fun u32(at: Long): Long =
        if (bigEndian) (u16(at).toLong() shl 16) or u16(at + 2).toLong() else u16(at).toLong() or (u16(at + 2).toLong() shl 16)
}

private enum class Alpha { None, Straight, Premultiplied }

/** Turns one decoded row's samples into ARGB. */
private sealed class Pixels {
    abstract fun argb(row: ByteArray, x: Int): Int

    /** Gray of 1–8 bits, white or black as zero, with an 8-bit alpha after it when [alpha]. */
    class Gray(private val depth: Int, private val whiteIsZero: Boolean, private val samples: Int, private val alpha: Alpha) : Pixels() {
        private val top = (1 shl depth) - 1

        override fun argb(row: ByteArray, x: Int): Int {
            var v = if (depth == 8) row[x * samples].toInt() and 0xFF else bits(row, x, depth) * 255 / top
            if (whiteIsZero) v = 255 - v
            val a = if (alpha != Alpha.None) row[x * samples + 1].toInt() and 0xFF else 0xFF
            if (alpha == Alpha.Premultiplied) v = unpremultiply(v, a)
            return (a shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    class Palette(private val depth: Int, private val colors: IntArray) : Pixels() {
        override fun argb(row: ByteArray, x: Int): Int = colors[bits(row, x, depth)]
    }

    class Rgb(private val samples: Int, private val alpha: Alpha) : Pixels() {
        override fun argb(row: ByteArray, x: Int): Int {
            val i = x * samples
            var r = row[i].toInt() and 0xFF
            var g = row[i + 1].toInt() and 0xFF
            var b = row[i + 2].toInt() and 0xFF
            val a = if (alpha != Alpha.None) row[i + 3].toInt() and 0xFF else 0xFF
            if (alpha == Alpha.Premultiplied) {
                r = unpremultiply(r, a)
                g = unpremultiply(g, a)
                b = unpremultiply(b, a)
            }
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    companion object {
        fun of(photometric: Long, samples: Int, depth: Int, alpha: Alpha, palette: IntArray?): Pixels = when (photometric) {
            WHITE_IS_ZERO, BLACK_IS_ZERO -> when {
                samples == 1 && depth in GRAY_DEPTHS -> Gray(depth, photometric == WHITE_IS_ZERO, 1, Alpha.None)
                samples >= 2 && depth == 8 -> Gray(8, photometric == WHITE_IS_ZERO, samples, alpha)
                else -> bad()
            }
            RGB -> if (samples >= 3 && depth == 8) Rgb(samples, if (samples >= 4) alpha else Alpha.None) else bad()
            PALETTE -> if (samples == 1 && depth in GRAY_DEPTHS && palette != null) Palette(depth, palette) else bad()
            else -> bad()
        }

        /** Sample [x] of a row packed [depth] bits each, first pixel in the high bits. */
        private fun bits(row: ByteArray, x: Int, depth: Int): Int {
            val bit = x * depth
            val byte = row[bit ushr 3].toInt() and 0xFF
            return (byte ushr (8 - depth - (bit and 7))) and ((1 shl depth) - 1)
        }

        private fun unpremultiply(c: Int, a: Int): Int = if (a == 0) 0 else minOf(255, c * 255 / a)
    }
}

/**
 * Box-filters rows of [width] × [height] pixels into blocks of `step` × `step`, so the long edge ends up at most
 * [maxEdge]. Only one output row of sums is kept while the rows stream in.
 */
private class Downscaler(private val sourceWidth: Int, private val sourceHeight: Int, maxEdge: Int) {
    private val step = (max(sourceWidth, sourceHeight) + maxEdge - 1) / maxEdge
    val width = (sourceWidth + step - 1) / step
    val height = (sourceHeight + step - 1) / step
    val pixels = IntArray(width * height)
    private val sums = LongArray(if (step == 1) 0 else width * 4)
    private var y = 0

    inline fun addRow(pixel: (Int) -> Int) {
        if (step == 1) {
            val base = y * width
            for (x in 0 until width) pixels[base + x] = pixel(x)
        } else {
            // Colors are weighted by their alpha, so a clear pixel's color doesn't tint the edge of what's around it.
            for (x in 0 until sourceWidth) {
                val p = pixel(x)
                val a = (p ushr 24).toLong()
                val i = (x / step) * 4
                sums[i] += a
                sums[i + 1] += (p ushr 16 and 0xFF) * a
                sums[i + 2] += (p ushr 8 and 0xFF) * a
                sums[i + 3] += (p and 0xFF) * a
            }
            val rows = y % step + 1
            if (rows == step || y == sourceHeight - 1) flush(rows)
        }
        y++
    }

    private fun flush(rows: Int) {
        val base = (y / step) * width
        for (x in 0 until width) {
            val n = minOf(step, sourceWidth - x * step).toLong() * rows
            val i = x * 4
            val alpha = sums[i]
            pixels[base + x] = if (alpha == 0L) {
                0
            } else {
                ((alpha / n).toInt() shl 24) or ((sums[i + 1] / alpha).toInt() shl 16) or
                    ((sums[i + 2] / alpha).toInt() shl 8) or (sums[i + 3] / alpha).toInt()
            }
        }
        sums.fill(0)
    }
}

/** Fills one row at a time from the strip it was last [start]ed on; false once the strip runs out first. */
private abstract class StripReader(protected val data: ByteArray) {
    protected var at = 0
    protected var end = 0

    open fun start(offset: Int, length: Int) {
        at = offset
        end = offset + length
    }

    abstract fun fill(row: ByteArray): Boolean
    open fun close() {}
}

private class RawStrip(data: ByteArray) : StripReader(data) {
    override fun fill(row: ByteArray): Boolean {
        if (end - at < row.size) return false
        System.arraycopy(data, at, row, 0, row.size)
        at += row.size
        return true
    }
}

/** PackBits (compression 32773): a run may carry on into the next row. */
private class PackBitsStrip(data: ByteArray) : StripReader(data) {
    private var literal = 0
    private var repeat = 0
    private var repeated: Byte = 0

    override fun start(offset: Int, length: Int) {
        super.start(offset, length)
        literal = 0
        repeat = 0
    }

    override fun fill(row: ByteArray): Boolean {
        var n = 0
        while (n < row.size) {
            when {
                literal > 0 -> {
                    val take = minOf(literal, row.size - n)
                    if (end - at < take) return false
                    System.arraycopy(data, at, row, n, take)
                    at += take
                    n += take
                    literal -= take
                }
                repeat > 0 -> {
                    val take = minOf(repeat, row.size - n)
                    row.fill(repeated, n, n + take)
                    n += take
                    repeat -= take
                }
                else -> {
                    if (at >= end) return false
                    val header = data[at++].toInt()
                    if (header >= 0) {
                        literal = header + 1
                    } else if (header != -128) {
                        if (at >= end) return false
                        repeated = data[at++]
                        repeat = 1 - header
                    }
                }
            }
        }
        return true
    }
}

/** TIFF's LZW (compression 5): codes of 9–12 bits, high bit first, widening one code early. */
private class LzwStrip(data: ByteArray) : StripReader(data) {
    private var bit = 0L
    private var bitEnd = 0L
    private val prefix = IntArray(TABLE)
    private val suffix = ByteArray(TABLE)
    private val first = ByteArray(TABLE)
    private val lengths = IntArray(TABLE)
    private var next = FIRST_CODE
    private var width = 9
    private var previous = -1
    private var ended = false

    // The current code's string, written back to front; [pending] bytes of it are still to be copied out.
    private val string = ByteArray(TABLE)
    private var pending = 0
    private var stringStart = 0

    init {
        // The first 256 codes never change; only codes from FIRST_CODE on are written.
        for (i in 0 until 256) {
            suffix[i] = i.toByte()
            first[i] = i.toByte()
            lengths[i] = 1
        }
    }

    override fun start(offset: Int, length: Int) {
        super.start(offset, length)
        bit = offset.toLong() * 8
        bitEnd = end.toLong() * 8
        next = FIRST_CODE
        width = 9
        previous = -1
        ended = false
        pending = 0
    }

    override fun fill(row: ByteArray): Boolean {
        var n = 0
        while (n < row.size) {
            if (pending > 0) {
                val take = minOf(pending, row.size - n)
                System.arraycopy(string, stringStart, row, n, take)
                stringStart += take
                pending -= take
                n += take
                continue
            }
            if (ended) return false
            val code = readCode()
            when {
                code < 0 || code == END -> ended = true
                code == CLEAR -> {
                    next = FIRST_CODE
                    width = 9
                    previous = -1
                }
                previous < 0 -> {
                    if (code > 255) bad()
                    emit(code)
                    previous = code
                }
                code < next -> {
                    add(previous, first[code])
                    emit(code)
                    previous = code
                }
                code == next -> {
                    add(previous, first[previous])
                    emit(code)
                    previous = code
                }
                else -> bad()
            }
        }
        return true
    }

    private fun readCode(): Int {
        if (bit + width > bitEnd) return -1
        // A code of at most 12 bits, starting anywhere in a byte, lies within 3 bytes; past the strip reads as 0.
        val i = (bit ushr 3).toInt()
        val window = (byteAt(i) shl 16) or (byteAt(i + 1) shl 8) or byteAt(i + 2)
        val code = (window ushr (24 - (bit and 7).toInt() - width)) and ((1 shl width) - 1)
        bit += width
        return code
    }

    private fun byteAt(i: Int): Int = if (i < end) data[i].toInt() and 0xFF else 0

    private fun add(prefixCode: Int, last: Byte) {
        // A full table without a Clear keeps its codes, as libtiff does.
        if (next >= TABLE) return
        prefix[next] = prefixCode
        suffix[next] = last
        first[next] = first[prefixCode]
        lengths[next] = lengths[prefixCode] + 1
        next++
        if (next >= (1 shl width) - 1 && width < 12) width++
    }

    private fun emit(code: Int) {
        val length = lengths[code]
        var c = code
        for (i in length - 1 downTo 0) {
            string[i] = suffix[c]
            c = prefix[c]
        }
        stringStart = 0
        pending = length
    }

    private companion object {
        const val TABLE = 4096
        const val CLEAR = 256
        const val END = 257
        const val FIRST_CODE = 258
    }
}

/** Deflate (compression 8, or 32946 from older writers), inflated a row at a time. */
private class DeflateStrip(data: ByteArray) : StripReader(data) {
    private val inflater = Inflater()

    override fun start(offset: Int, length: Int) {
        inflater.reset()
        inflater.setInput(data, offset, length)
    }

    override fun fill(row: ByteArray): Boolean {
        var n = 0
        try {
            while (n < row.size) {
                val read = inflater.inflate(row, n, row.size - n)
                if (read == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) return false
                n += read
            }
        } catch (e: DataFormatException) {
            return false
        }
        return true
    }

    override fun close() = inflater.end()
}

private const val BYTE = 1
private const val ASCII = 2
private const val SHORT = 3
private const val LONG = 4
private const val RATIONAL = 5
private const val SBYTE = 6
private const val UNDEFINED = 7
private const val SSHORT = 8
private const val SLONG = 9
private const val SRATIONAL = 10
private const val FLOAT = 11
private const val DOUBLE = 12

private const val WIDTH = 256
private const val HEIGHT = 257
private const val BITS_PER_SAMPLE = 258
private const val COMPRESSION = 259
private const val PHOTOMETRIC = 262
private const val FILL_ORDER = 266
private const val STRIP_OFFSETS = 273
private const val ORIENTATION = 274
private const val SAMPLES_PER_PIXEL = 277
private const val ROWS_PER_STRIP = 278
private const val STRIP_BYTE_COUNTS = 279
private const val PLANAR = 284
private const val PREDICTOR = 317
private const val COLOR_MAP = 320
private const val TILE_WIDTH = 322
private const val EXTRA_SAMPLES = 338
private const val SAMPLE_FORMAT = 339

private const val NONE = 1
private const val LZW = 5
private const val DEFLATE = 8
private const val ADOBE_DEFLATE = 32946
private const val PACKBITS = 32773
private val COMPRESSIONS = setOf(NONE, LZW, DEFLATE, ADOBE_DEFLATE, PACKBITS)

private const val WHITE_IS_ZERO = 0L
private const val BLACK_IS_ZERO = 1L
private const val RGB = 2L
private const val PALETTE = 3L
private val GRAY_DEPTHS = setOf(1, 2, 4, 8)
private const val MAX_SAMPLES = 8

/** A strip per row of a picture a million rows tall; more is only ever a file built to be slow. */
private const val MAX_STRIPS = 1 shl 20

/** One row's samples, the only part of a strip held at once: a picture 2 million pixels wide at 8 samples. */
private const val MAX_ROW_BYTES = 16L * 1024 * 1024
