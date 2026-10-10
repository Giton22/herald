package dev.hermeskotlin.core.voice

/** [pcm] (16-bit little-endian mono samples at [sampleRate]) as a plain WAV file, which every speech-to-text provider takes. */
fun pcm16Wav(pcm: ByteArray, sampleRate: Int): ByteArray {
    // Whole 16-bit samples, which also keeps the data chunk even, as RIFF wants.
    require(pcm.size % 2 == 0) { "16-bit PCM has an even number of bytes." }
    val header = ByteArray(WAV_HEADER)
    fun text(at: Int, value: String) = value.encodeToByteArray().copyInto(header, at)
    fun int(at: Int, value: Int, bytes: Int = 4) {
        for (i in 0 until bytes) header[at + i] = (value ushr (8 * i)).toByte()
    }
    text(0, "RIFF")
    int(4, 36 + pcm.size)
    text(8, "WAVE")
    text(12, "fmt ")
    int(16, 16)
    int(20, 1, 2) // PCM
    int(22, 1, 2) // mono
    int(24, sampleRate)
    int(28, sampleRate * 2)
    int(32, 2, 2)
    int(34, 16, 2)
    text(36, "data")
    int(40, pcm.size)
    return header + pcm
}

/**
 * The samples of a WAV [file]: its `data` chunk, past whatever other chunks the writer added (Apple's adds
 * padding). Null when it isn't a WAV file or has no data.
 */
fun wavSamples(file: ByteArray): ByteArray? {
    if (file.size < 12 || file.decodeToString(0, 4) != "RIFF" || file.decodeToString(8, 12) != "WAVE") return null
    var at = 12
    while (at + 8 <= file.size) {
        val id = file.decodeToString(at, at + 4)
        val size = (0 until 4).fold(0L) { sum, i -> sum or ((file[at + 4 + i].toLong() and 0xFF) shl (8 * i)) }
        val start = at + 8
        // A writer that stopped early can leave the size too large: take what is there.
        val end = minOf(file.size.toLong(), start + size).toInt()
        if (id == "data") return file.copyOfRange(start, end)
        // Chunks are padded to an even length.
        at = (start + size + (size and 1)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    return null
}

private const val WAV_HEADER = 44
