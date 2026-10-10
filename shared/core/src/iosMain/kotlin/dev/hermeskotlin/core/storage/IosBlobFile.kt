package dev.hermeskotlin.core.storage

import dev.hermeskotlin.core.platform.toByteArray
import dev.hermeskotlin.core.platform.toNSData
import dev.hermeskotlin.core.settings.BlobFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/** A [BlobFile] at [path]. An atomic write goes to a temporary file first, so a crash never leaves half an image. */
class IosBlobFile(private val path: String) : BlobFile {

    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        NSData.dataWithContentsOfFile(path)?.toByteArray()
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        check(bytes.toNSData().writeToFile(path, atomically = true)) { "Couldn't save ${path.substringAfterLast('/')}." }
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun delete() = withContext(Dispatchers.IO) {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
        Unit
    }
}

/** The app's private Application Support folder, made on first use; [name] is a file in it. */
@OptIn(ExperimentalForeignApi::class)
fun appSupportPath(name: String): String {
    val dir = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
    NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    return "$dir/$name"
}
