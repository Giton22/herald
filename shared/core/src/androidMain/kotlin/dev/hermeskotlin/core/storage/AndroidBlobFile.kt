package dev.hermeskotlin.core.storage

import dev.hermeskotlin.core.settings.BlobFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A [BlobFile] in the app's private files. Writes land in a temporary file first, so a crash never leaves half an image. */
class AndroidBlobFile(private val file: File) : BlobFile {

    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        file.takeIf { it.isFile }?.readBytes()
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val partial = File(file.parentFile, "${file.name}.part")
        partial.writeBytes(bytes)
        if (!partial.renameTo(file)) {
            partial.delete()
            error("Couldn't save ${file.name}.")
        }
    }

    override suspend fun delete() = withContext(Dispatchers.IO) {
        file.delete()
        Unit
    }
}
