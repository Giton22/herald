package dev.hermeskotlin.ui.chat

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogParent
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import kotlinx.coroutines.CancellationException
import java.awt.Frame
import java.io.File

/** The window file dialogs belong to; the app sets it when its window opens. */
var dialogOwner: Frame? = null

// The system's own dialogs (Explorer's on Windows), centred on the app's window; AWT's are the old-style ones.
private val fileKit by lazy { FileKit.init("Herald") }

private fun settings(title: String): FileKitDialogSettings {
    fileKit
    return FileKitDialogSettings(title = title, parent = dialogOwner?.let(FileKitDialogParent::awt))
}

/** The system's Open dialog; empty when cancelled. [extensions] narrows it to those kinds of file. */
internal suspend fun openFiles(title: String, multiple: Boolean, extensions: Set<String>? = null): List<File> {
    val type = extensions?.let { FileKitType.File(it) } ?: FileKitType.File()
    val picked = if (multiple) {
        FileKit.openFilePicker(type, FileKitMode.Multiple(), dialogSettings = settings(title)).orEmpty()
    } else {
        listOfNotNull(FileKit.openFilePicker(type, dialogSettings = settings(title)))
    }
    return picked.map { it.file }
}

/** The system's Save dialog, starting at [name]; null when cancelled. */
internal suspend fun saveFile(title: String, name: String): File? {
    // A kind of file, as "md" is; not the end of a name with a dot in it, as "2 notes" is in "v1.2 notes".
    // A name that is all extension (".env") keeps its name.
    val extension = name.substringAfterLast('.', "").takeIf {
        it.isNotEmpty() && it.length <= 8 && it.all(Char::isLetterOrDigit) && name.length > it.length + 1
    }
    val base = if (extension != null) name.removeSuffix(".$extension") else name
    return FileKit.openFileSaver(base, defaultExtension = extension, dialogSettings = settings(title))?.file
}

/** What [block] answers, or why a dialog couldn't open; a cancelled coroutine stays cancelled, not a failure. */
internal suspend fun <T> fromDialog(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    } catch (e: LinkageError) {
        // The system dialog's native library missing or not loadable on this machine.
        Result.failure(e)
    }

internal val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
