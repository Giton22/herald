package dev.hermeskotlin.core.voice

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WavTest {

    @Test
    fun headerDescribes16BitMono() {
        val wav = pcm16Wav(byteArrayOf(1, 2, 3, 4), 16_000)
        assertEquals(48, wav.size)
        assertEquals("RIFF", wav.decodeToString(0, 4))
        assertEquals(40, wav[4].toInt())
        assertEquals("WAVEfmt ", wav.decodeToString(8, 16))
        // 16 000 Hz, little-endian.
        assertContentEquals(byteArrayOf(0x80.toByte(), 0x3E, 0, 0), wav.copyOfRange(24, 28))
        assertEquals("data", wav.decodeToString(36, 40))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), wavSamples(wav))
    }

    @Test
    fun samplesSkipOtherChunksAndTheirPadding() {
        val plain = pcm16Wav(byteArrayOf(9, 8), 16_000)
        // An odd-sized chunk before the data, as Apple's writer adds a padding chunk.
        val filler = "FLLR".encodeToByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0)
        val file = plain.copyOfRange(0, 36) + filler + plain.copyOfRange(36, plain.size)
        assertContentEquals(byteArrayOf(9, 8), wavSamples(file))
    }

    @Test
    fun aTruncatedDataChunkKeepsWhatIsThere() {
        val wav = pcm16Wav(byteArrayOf(1, 2, 3, 4), 16_000)
        assertContentEquals(byteArrayOf(1, 2), wavSamples(wav.copyOfRange(0, 46)))
    }

    @Test
    fun notAWavFileHasNoSamples() {
        assertNull(wavSamples("not a wav file at all".encodeToByteArray()))
        assertNull(wavSamples(ByteArray(4)))
    }
}
