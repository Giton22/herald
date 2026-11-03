package dev.hermeskotlin.core.storage

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** Verified same-directory replacement; no delete-first or non-atomic fallback. */
internal class AtomicSettingsFile(
    private val file: File,
    private val syncParent: (Path) -> Unit = { parent -> FileChannel.open(parent, READ).use { it.force(true) } },
    private val replace: (Path, Path) -> Unit = { from, to -> Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING); Unit },
) {
    companion object {
        private const val MAX_BYTES = 16 * 1024 * 1024
        fun digest(bytes: ByteArray?) = bytes?.let { MessageDigest.getInstance("SHA-256").digest(it) }
        fun same(expected: ByteArray?, actual: ByteArray?) =
            if (expected == null) actual == null else actual != null && MessageDigest.isEqual(expected, actual)
    }

    fun read(path: Path = file.toPath()): ByteArray? = try {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.size() > MAX_BYTES) throw SecureStorageException(SecureStorageException.Reason.Read)
        val bytes = Channels.newInputStream(Files.newByteChannel(path, setOf(READ, LinkOption.NOFOLLOW_LINKS)))
            .use { it.readNBytes(MAX_BYTES + 1) }
        if (bytes.size > MAX_BYTES) throw SecureStorageException(SecureStorageException.Reason.Read)
        bytes
    } catch (_: NoSuchFileException) { null }
      catch (_: IOException) { throw SecureStorageException(SecureStorageException.Reason.Read) }

    fun commit(bytes: ByteArray, expected: ByteArray?, verify: (ByteArray) -> Unit): ByteArray {
        // Keep this inode: deleting a lock file can let another process lock a different one.
        val lockPath = File(file.absoluteFile.parentFile, file.name + ".lock").toPath()
        var committing = false
        try {
            return FileChannel.open(lockPath, setOf(WRITE, CREATE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    ?: throw SecureStorageException(SecureStorageException.Reason.Changed)
                lock.use { committing = true; commitLocked(bytes, expected, verify) }
            }
        } catch (failure: SecureStorageException) { throw failure }
          catch (_: IOException) { throw SecureStorageException(
              if (committing) SecureStorageException.Reason.Uncertain else SecureStorageException.Reason.Write) }
    }

    private fun commitLocked(bytes: ByteArray, expected: ByteArray?, verify: (ByteArray) -> Unit): ByteArray {
        if (bytes.size > MAX_BYTES) throw SecureStorageException(SecureStorageException.Reason.Write)
        var replacing = false
        try {
            val current = read()
            val actual = digest(current)
            current?.fill(0)
            if (!same(expected, actual)) throw SecureStorageException(SecureStorageException.Reason.Changed)
            val parent = file.absoluteFile.parentFile.toPath()
            // A write killed before its rename leaves its temp file; under this lock no other write owns one.
            Files.newDirectoryStream(parent, file.name + ".*.encrypted.part").use { leftovers ->
                leftovers.forEach { Files.deleteIfExists(it) }
            }
            val partial = Files.createTempFile(parent, file.name + ".", ".encrypted.part",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            AutoCloseable { Files.deleteIfExists(partial) }.use {
                FileChannel.open(partial, WRITE).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                val saved = read(partial) ?: throw SecureStorageException(SecureStorageException.Reason.Write)
                try { verify(saved) } catch (_: Exception) { throw SecureStorageException(SecureStorageException.Reason.Write) }
                finally { saved.fill(0) }
                replacing = true
                replace(partial, file.toPath())
            }
        } catch (failure: SecureStorageException) { throw failure }
          catch (_: IOException) {
            if (!replacing) throw SecureStorageException(SecureStorageException.Reason.Write)
            val after = try { read() } catch (_: SecureStorageException) {
                throw SecureStorageException(SecureStorageException.Reason.Uncertain)
            }
            try {
                if (after == null || !MessageDigest.isEqual(after, bytes)) {
                    if (same(expected, digest(after))) throw SecureStorageException(SecureStorageException.Reason.Write)
                    throw SecureStorageException(SecureStorageException.Reason.Uncertain)
                }
            } finally { after?.fill(0) }
        }
        // Visible matching bytes do not prove that the directory entry survived a crash.
        syncDirectory()
        return digest(bytes)!!
    }

    fun syncDirectory() {
        try { syncParent(file.absoluteFile.parentFile.toPath()) }
        catch (_: IOException) { throw SecureStorageException(SecureStorageException.Reason.Uncertain) }
        catch (_: UnsupportedOperationException) { throw SecureStorageException(SecureStorageException.Reason.Uncertain) }
    }
}
