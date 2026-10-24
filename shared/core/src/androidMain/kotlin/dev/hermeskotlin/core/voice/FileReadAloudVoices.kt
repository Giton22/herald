package dev.hermeskotlin.core.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration.Companion.milliseconds

/**
 * Kept in [dir] as well, so a reply read again is the same take after the app was closed or let go of: a folder
 * for each of the last [replies] replies, a file for each piece. A piece older than [maxAgeMs] is voiced anew, so a
 * voice changed on the gateway is heard by the next day. Android may empty a cache folder when space runs short;
 * the reply is then voiced anew too.
 */
class FileReadAloudVoices(
    private val dir: File,
    private val replies: Int = 30,
    private val maxAgeMs: Long = 24 * 60 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) : ReadAloudVoices {
    private val memory = InMemoryReadAloudVoices(maxAge = maxAgeMs.milliseconds)

    override suspend fun piece(reply: String, piece: String): SpokenAudio? {
        memory.piece(reply, piece)?.let { audio ->
            // Played from memory, it's still the reply read last, so it's the last one the disk lets go.
            withContext(Dispatchers.IO) { runCatching { File(dir, name(reply)).takeIf { it.exists() }?.setLastModified(now()) } }
            return audio
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val folder = File(dir, name(reply))
                val file = folder.listFiles()?.firstOrNull { it.name.substringBefore('.') == name(piece) } ?: return@runCatching null
                // An old one is left for [keep] to replace: deleting it here would make the folder look recent.
                val age = now() - file.lastModified()
                if (age > maxAgeMs) return@runCatching null
                folder.setLastModified(now())
                SpokenAudio(file.readBytes(), file.name.substringAfter('.').replaceFirst('_', '/')).also {
                    // Read again, it plays from memory rather than the disk, and is let go at the same time.
                    memory.keep(reply, piece, it, age.coerceAtLeast(0).milliseconds)
                }
            }.getOrNull()
        }
    }

    override suspend fun keep(reply: String, piece: String, audio: SpokenAudio) {
        memory.keep(reply, piece, audio)
        withContext(Dispatchers.IO) {
            runCatching {
                val folder = File(dir, name(reply))
                val added = !folder.exists()
                folder.mkdirs()
                val file = File(folder, "${name(piece)}.${audio.mimeType.replace('/', '_').filter { it.isLetterOrDigit() || it in "_.-+;=" }}")
                // Written beside it and moved into place, so an app stopped mid-write never leaves half a piece.
                val partial = File(folder, "partial-${name(piece)}")
                partial.writeBytes(audio.bytes)
                // The take it replaces goes first, whatever its type: renaming over a file fails on some systems.
                folder.listFiles()?.filter { it.name.substringBefore('.') == name(piece) }?.forEach { it.delete() }
                if (!partial.renameTo(file)) {
                    partial.delete()
                    return@runCatching
                }
                file.setLastModified(now())
                folder.setLastModified(now())
                // Only a new reply can put the folder past [replies].
                if (added) dir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }?.drop(replies)?.forEach { it.deleteRecursively() }
            }
        }
    }

    private fun name(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}
