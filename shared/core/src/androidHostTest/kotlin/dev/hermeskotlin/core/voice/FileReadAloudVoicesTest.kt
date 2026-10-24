package dev.hermeskotlin.core.voice

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileReadAloudVoicesTest {

    private val reply = "gw\nwork\nBuilt it. All green."

    @Test
    fun aReplyReadAgainAfterTheAppWasClosedIsTheSameTake() = runTest {
        val dir = Files.createTempDirectory("read-aloud").toFile()
        FileReadAloudVoices(dir).apply {
            keep(reply, "Built it.", SpokenAudio(byteArrayOf(1, 2, 3), "audio/mpeg"))
            keep(reply, "All green.", SpokenAudio(byteArrayOf(4, 5), "audio/ogg;codecs=opus"))
        }

        // A new one over the same folder, as after the app was let go of.
        val again = FileReadAloudVoices(dir)
        val first = again.piece(reply, "Built it.")!!
        assertContentEquals(byteArrayOf(1, 2, 3), first.bytes)
        assertEquals("audio/mpeg", first.mimeType)
        assertEquals("audio/ogg;codecs=opus", again.piece(reply, "All green.")!!.mimeType)
        assertNull(again.piece(reply, "Built it. All green."))
        assertNull(again.piece("gw\nhome\nBuilt it. All green.", "Built it."))
        // Nothing half-written is left beside the pieces.
        assertTrue(dir.walk().none { it.name.startsWith("partial-") })
        dir.deleteRecursively()
    }

    @Test
    fun onlyTheLastFewRepliesAreKept() = runTest {
        val dir = Files.createTempDirectory("read-aloud").toFile()
        var clock = System.currentTimeMillis()
        val voices = FileReadAloudVoices(dir, replies = 2, now = { clock })
        for (reply in listOf("one", "two", "three")) {
            voices.keep(reply, reply, SpokenAudio(reply.encodeToByteArray(), "audio/mpeg"))
            clock += 10_000
        }

        assertEquals(2, dir.listFiles()!!.size)
        assertNull(FileReadAloudVoices(dir, now = { clock }).piece("one", "one"))
        dir.deleteRecursively()
    }

    @Test
    fun anOldTakeIsVoicedAnew() = runTest {
        val dir = Files.createTempDirectory("read-aloud").toFile()
        var clock = System.currentTimeMillis()
        FileReadAloudVoices(dir, now = { clock }).keep(reply, "All green.", SpokenAudio(byteArrayOf(1), "audio/mpeg"))

        clock += 25 * 60 * 60 * 1000L
        val later = FileReadAloudVoices(dir, now = { clock })
        assertNull(later.piece(reply, "All green."))

        // The new take, of another type, takes the old one's place.
        later.keep(reply, "All green.", SpokenAudio(byteArrayOf(2), "audio/wav"))
        assertContentEquals(byteArrayOf(2), FileReadAloudVoices(dir, now = { clock }).piece(reply, "All green.")!!.bytes)
        assertEquals(1, dir.walk().count(File::isFile))
        dir.deleteRecursively()
    }
}
