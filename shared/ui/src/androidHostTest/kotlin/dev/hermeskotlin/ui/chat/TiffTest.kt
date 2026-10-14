package dev.hermeskotlin.ui.chat

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TiffTest {
    @Test
    fun everyCompressionGivesTheSamePixels() {
        val expected = IntArray(W * H) { rgb(it % W, it / W) }
        listOf(
            "rgb-none", "rgb-packbits", "rgb-packbits-strips", "rgb-lzw", "rgb-lzw-strips", "rgb-deflate",
            "rgb-deflate-strips", "rgb-lzw-predictor", "rgb-deflate-predictor", "rgb-bigendian",
        ).forEach { name ->
            val image = assertNotNull(decodeTiff(fixture(name), maxEdge = 100), name)
            assertEquals(W, image.width, name)
            assertEquals(H, image.height, name)
            assertContentEquals(expected, image.argb, name)
            assertEquals(1, image.orientation, name)
        }
    }

    @Test
    fun grayPaletteBilevelAndAlpha() {
        val gray = assertNotNull(decodeTiff(fixture("gray-deflate"), 100))
        assertContentEquals(IntArray(W * H) { gray(it % W * 50 + it / W) }, gray.argb)

        val colors = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt())
        val palette = assertNotNull(decodeTiff(fixture("palette-packbits"), 100))
        assertContentEquals(IntArray(W * H) { colors[(it % W + it / W) % 4] }, palette.argb)

        val bilevel = assertNotNull(decodeTiff(fixture("bilevel-none"), 100))
        assertContentEquals(IntArray(W * H) { if ((it % W + it / W) % 2 == 0) gray(255) else gray(0) }, bilevel.argb)

        val rgba = assertNotNull(decodeTiff(fixture("rgba-lzw"), 100))
        assertEquals(0x800A141E.toInt(), rgba.argb[0])
        assertContentEquals(IntArray(W * H - 1) { rgb((it + 1) % W, (it + 1) / W) }, rgba.argb.copyOfRange(1, W * H))
    }

    @Test
    fun longLzwStripsWidenTheirCodesAndClearTheTable() {
        // libtiff's LZW of 48 KB: codes reach 12 bits, the table fills and is cleared.
        val image = assertNotNull(decodeTiff(fixture("gray-lzw-large"), maxEdge = 256))
        assertEquals(256, image.width)
        assertEquals(192, image.height)
        val expected = IntArray(256 * 192) { i ->
            val x = i % 256
            val y = i / 256
            gray(if ((x / 32 + y / 32) % 2 == 0) (x + y) and 255 else (((x * 73856093L) xor (y * 19349663L)) and 0xFFFF).toInt() shr 4 and 255)
        }
        assertContentEquals(expected, image.argb)
    }

    @Test
    fun aLargePictureIsAveragedDownToTheEdge() {
        // 5 × 4 into an edge of 2: blocks of 3 × 3, the last ones narrower and shorter.
        val image = assertNotNull(decodeTiff(fixture("rgb-lzw"), maxEdge = 2))
        assertEquals(2, image.width)
        assertEquals(2, image.height)
        assertEquals(argb(60, 80, 215), image.argb[0])
        // x 3–4, y 3: r (180 + 240) / 2, g 240, b (135 + 95) / 2.
        assertEquals(argb(210, 240, 115), image.argb[3])
    }

    @Test
    fun averagingWeighsColorsByAlpha() {
        // All 20 pixels in one: the half-clear one counts for half as much color.
        val image = assertNotNull(decodeTiff(fixture("rgba-lzw"), maxEdge = 1))
        val pixels = (0 until W * H).map { if (it == 0) intArrayOf(128, 10, 20, 30) else rgb(it % W, it / W).let { p -> intArrayOf(255, p shr 16 and 255, p shr 8 and 255, p and 255) } }
        val alpha = pixels.sumOf { it[0] }
        fun channel(c: Int) = pixels.sumOf { it[c] * it[0] } / alpha
        assertEquals(((alpha / (W * H)) shl 24) or (channel(1) shl 16) or (channel(2) shl 8) or channel(3), image.argb.single())
    }

    @Test
    fun aColorMapOnAPictureThatIsNotPaletteIsIgnored() {
        val strip = ByteArray(16) { (it * 16).toByte() }
        val image = assertNotNull(decodeTiff(grayTiff(4, 4, strip, compression = 1, extra = listOf(Tag(320, 3, 7, 0))), 100))
        assertEquals(gray(0x10), image.argb[1])
    }

    @Test
    fun brokenFilesAreNull() {
        listOf(
            "broken-truncated", "broken-header-only", "broken-huge", "broken-lying-strip", "broken-lzw-noise",
            "broken-garbage", "broken-ifd-offset", "broken-ifd-count",
        ).forEach { assertNull(decodeTiff(fixture(it), 100), it) }
        assertNull(decodeTiff(ByteArray(0), 100))
        assertNull(decodeTiff("not a tiff".encodeToByteArray(), 100))
    }

    @Test
    fun aPictureOverTheCapIsRefusedFromItsHeader() {
        assertNull(decodeTiff(fixture("rgb-none"), 100, maxPixels = W * H - 1L))
        assertNotNull(decodeTiff(fixture("rgb-none"), 100, maxPixels = W * H.toLong()))
        // A whole, valid 100 × 100 strip, refused by the cap alone.
        val strip = deflate(ByteArray(10_000), times = 1)
        assertNull(decodeTiff(grayTiff(100, 100, strip, compression = 8), 100, maxPixels = 9_999))
        assertNotNull(decodeTiff(grayTiff(100, 100, strip, compression = 8), 100, maxPixels = 10_000))
        // Two sides whose product passes a Long.
        assertNull(decodeTiff(grayTiff(0xFFFFFFFFL, 0xFFFFFFFFL, ByteArray(16), compression = 1), 100))
    }

    @Test
    fun aMillionTinyStripsAreQuickAndMoreAreRefused() {
        val start = System.nanoTime()
        val image = assertNotNull(decodeTiff(manyStrips(MAX_STRIPS_IN_TEST), 100))
        assertEquals(gray(0), image.argb[0])
        assertTrue(System.nanoTime() - start < 5_000_000_000L, "took ${(System.nanoTime() - start) / 1_000_000} ms")
        assertNull(decodeTiff(manyStrips(MAX_STRIPS_IN_TEST + 1), 100))
    }

    /**
     * A 1 × [rows] LZW picture with a strip per row. Every StripOffsets and StripByteCounts value is the byte 200
     * from one shared array, and 200 bytes of zeros at 200 are a strip whose first 9-bit code is a literal 0.
     */
    private fun manyStrips(rows: Int): ByteArray {
        val array = 400L
        val file = grayTiff(1, rows.toLong(), ByteArray(0), compression = 5, stripTags = listOf(Tag(273, 1, rows.toLong(), array), Tag(279, 1, rows.toLong(), array)))
        return file.copyOf(array.toInt() + rows).also { it.fill(200.toByte(), array.toInt(), it.size) }
    }

    @Test
    fun aStripThatInflatesPastItsRowsIsOnlyReadForThem() {
        val image = assertNotNull(decodeTiff(grayTiff(4, 4, deflate(ByteArray(1 shl 20), times = 64), compression = 8), 100))
        assertContentEquals(IntArray(16) { gray(0) }, image.argb)
    }

    @Test
    fun changedOrCutFilesNeverThrow() {
        val random = Random(42)
        val good = listOf("rgb-packbits", "rgb-lzw", "rgb-lzw-strips", "rgb-deflate-predictor", "rgb-bigendian", "palette-packbits", "bilevel-none", "rgba-lzw")
            .map(::fixture)
        repeat(5000) { i ->
            val bytes = good[i % good.size].copyOf()
            val mutant = if (random.nextInt(5) == 0) {
                bytes.copyOf(random.nextInt(4, bytes.size))
            } else {
                repeat(random.nextInt(1, 9)) { bytes[random.nextInt(4, bytes.size)] = random.nextInt(256).toByte() }
                bytes
            }
            // Null or a picture; any exception fails the test.
            decodeTiff(mutant, maxEdge = 64)?.let { assertTrue(it.width in 1..64 && it.height in 1..64) }
        }
    }

    @Test
    fun recognisesBothByteOrders() {
        assertTrue(isTiff(fixture("rgb-none")))
        assertTrue(isTiff(fixture("rgb-bigendian")))
        assertFalse(isTiff(byteArrayOf(0x49, 0x49, 0x2B, 0x00)))
        assertFalse(isTiff(byteArrayOf(0x49, 0x49, 0x2A)))
    }

    private fun fixture(name: String): ByteArray =
        assertNotNull(javaClass.getResourceAsStream("/tiff/$name.tif"), name).use { it.readBytes() }

    private fun rgb(x: Int, y: Int) = argb(x * 60, y * 80, 255 - x * 40)

    private fun gray(v: Int) = argb(v, v, v)

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun deflate(chunk: ByteArray, times: Int): ByteArray {
        val deflater = Deflater(9)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        repeat(times) {
            deflater.setInput(chunk)
            while (!deflater.needsInput()) out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.finish()
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    /** One directory entry; [value] is the value itself when it fits in 4 bytes, else where it is. */
    private data class Tag(val tag: Int, val type: Int, val count: Long, val value: Long)

    /**
     * A little-endian 8-bit gray TIFF whose one strip, [strip], follows the directory, with [extra] entries.
     * [stripTags] replace the StripOffsets and StripByteCounts that point at [strip], and RowsPerStrip is then 1.
     */
    private fun grayTiff(
        width: Long,
        height: Long,
        strip: ByteArray,
        compression: Int,
        extra: List<Tag> = emptyList(),
        stripTags: List<Tag>? = null,
    ): ByteArray {
        val count = 7 + 2 + extra.size
        val dataAt = 8L + 2 + 12 * count + 4
        val entries = listOf(
            Tag(256, 4, 1, width), Tag(257, 4, 1, height), Tag(258, 3, 1, 8), Tag(259, 3, 1, compression.toLong()),
            Tag(262, 3, 1, 1), Tag(277, 3, 1, 1), Tag(278, 4, 1, if (stripTags == null) height else 1),
        ) + (stripTags ?: listOf(Tag(273, 4, 1, dataAt), Tag(279, 4, 1, strip.size.toLong()))) + extra
        val out = ByteArrayOutputStream()
        fun le(value: Long, bytes: Int) = repeat(bytes) { out.write((value ushr (8 * it)).toInt() and 0xFF) }
        out.write("II".encodeToByteArray())
        le(42, 2)
        le(8, 4)
        le(entries.size.toLong(), 2)
        entries.forEach { (tag, type, n, value) ->
            le(tag.toLong(), 2)
            le(type.toLong(), 2)
            le(n, 4)
            le(value, 4)
        }
        le(0, 4)
        out.write(strip)
        return out.toByteArray()
    }

    private companion object {
        const val W = 5
        const val H = 4

        /** The decoder's own strip limit. */
        const val MAX_STRIPS_IN_TEST = 1 shl 20
    }
}
